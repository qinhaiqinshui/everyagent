package dev.everyagent.worker.os.wsl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.everyagent.worker.config.WorkerProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * wsl-bwrap 沙箱后端(docs/ARCHITECTURE.md §7.10):命令执行整体迁入 WSL2
 * 发行版,经 bubblewrap 挂载命名空间运行。
 *
 * <p>调用链(worker 侧):载荷 JSON 经 stdin 传给 {@code wsl.exe -d <发行版> -e python3
 * /mnt/.../eagent-run.py}(脚本随 worker 打包、从 Windows 侧直接读取,发行版<b>零安装</b>;
 * 发行版只需 python3 + bwrap),发行版侧 setsid+pgid 登记+setrlimit 后 exec bwrap。
 * 沙箱语义 = 每次调用的参数:工作区/授权根经 {@code --bind} 白名单挂入({@code /workspace}
 * 规范挂载点 + {@code /mnt/<盘>} 原生形态双挂载),发行版基础层只读,{@code --unshare-net}
 * 网络硬拒,宿主 NTFS 零改动、零残留(MIC 标签/DACL ACE 类问题结构性消失)。
 *
 * <p>与 WindowsSandbox(MIC 后端)的关键差异:
 * <ul>
 *   <li><b>超时击杀</b>:wsl.exe 退出不保证杀死 Linux 后代(WSL 已知行为),必须
 *       {@code pkill -g <pgid>} 显式收割;pgid 由 eagent-run 登记在 /run/eagent/;</li>
 *   <li><b>环境</b>:{@code --clearenv} + 载荷白名单重建(Linux 侧 XDG 三件套,
 *       APPDATA/TEMP 一类 Windows 病灶不存在);代理 env 按 networkPolicy 剥除;</li>
 *   <li><b>前置条件</b>:worker 必须是非降权进程——WSL 服务拒绝 Low-IL/restricted
 *       调用方(真机实测 Wsl/E_ACCESSDENIED);发行版需 python3 + bwrap(探测见
 *       {@link #probe});发行版缺失且打包了托管镜像时启动自动导入
 *       ({@link #autoImport},免管理员、离线,sha256 把关);</li>
 *   <li><b>Job Object 能力映射</b>:进程数/内存→RLIMIT_NPROC/AS(粗),CPU→RLIMIT_CPU
 *       秒数;cgroup 细化是 Phase 2。</li>
 * </ul>
 *
 * <p>线程模型与 OsSandbox.runDirect 同构:stdin 载荷写入、stdout/stderr 分流捕获
 * 全部走共享虚拟线程池,任一管道只写不读都会写满缓冲卡死子进程。
 */
public final class WslBwrapSandbox {

    private static final Logger log = LoggerFactory.getLogger(WslBwrapSandbox.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 发行版内默认 PATH(bash 与 coreutils 所在;appendWindowsPath=false 保证无 Windows 注入)。 */
    private static final String DISTRO_PATH =
            "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin";
    /** networkPolicy=deny-all 时从 extraEnv 剥除的代理变量(与 OsSandbox.sanitizedEnv 同集)。 */
    private static final List<String> PROXY_KEYS = List.of(
            "HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy",
            "ALL_PROXY", "all_proxy", "NO_PROXY", "no_proxy");
    /** 探测超时:冷启动 VM 首次 wsl.exe 调用可能要数秒。 */
    private static final long PROBE_TIMEOUT_MS = 30_000;
    /** 失败后断因定位的额外调用超时(发行版列表 / P-B-S 标记)。 */
    private static final long LIST_TIMEOUT_MS = 15_000;
    private static final long DIAG_TIMEOUT_MS = 30_000;
    /** 托管发行版名(wsl --import 目标;镜像在位且未配置 distro 时自动启用)。 */
    public static final String MANAGED_DISTRO = "eagent";
    /** 沙箱内持久状态的固定挂载点(worker 级共享,见 {@link #applyPersistentState})。 */
    private static final String PERSIST_HOME_MOUNT = "/root";
    private static final String PERSIST_OPT_MOUNT = "/opt";
    private static final String PERSIST_USR_LOCAL_MOUNT = "/usr/local";
    private static final String PERSIST_RESOLV_MOUNT = "/etc/resolv.conf";
    /** 持久状态根下的子目录/文件(相对 persistentRoot)。 */
    private static final String PERSIST_DIR_HOME = "home";
    private static final String PERSIST_DIR_OPT = "opt";
    private static final String PERSIST_DIR_USR_LOCAL = "usr-local";
    private static final String PERSIST_FILE_RESOLV = "resolv.conf";
    private static final String PERSIST_FILE_ENV = "env";
    /** 自动导入超时:~200MB 镜像解包,慢盘给足余量。 */
    private static final long IMPORT_TIMEOUT_MS = 300_000;
    /** runCapture 出口约定:-1 = 超时,-127 = 启动失败(wsl.exe 不存在等)。 */
    private static final int RC_TIMEOUT = -1;
    private static final int RC_LAUNCH_FAIL = -127;
    /** 击杀命令自身的完成等待(防泄漏 wsl.exe 进程,尽力而为)。 */
    private static final long KILL_TIMEOUT_MS = 10_000;

    private WslBwrapSandbox() {
    }

    /** wsl.exe 前缀:distro 空 = 不带 -d(WSL 默认发行版,wsl -l -v 带 * 者)。 */
    static List<String> wslCmd(String distro, String... rest) {
        List<String> c = new ArrayList<>();
        c.add("wsl.exe");
        if (distro != null && !distro.isBlank()) {
            c.add("-d");
            c.add(distro.trim());
        }
        c.addAll(List.of(rest));
        return c;
    }

    /** 日志用发行版标签:空 = 「默认发行版」。 */
    public static String distroLabel(String distro) {
        return distro == null || distro.isBlank() ? "默认发行版" : distro.trim();
    }

    /**
     * 托管镜像路径:优先程序根 {@code <程序根>/runtime/wsl/eagent-rootfs.tar.gz}(随安装/解压分发,
     * 只读引用);其次配置 {@code worker.sandbox.wsl.tarball}(相对系统目录解析,兼容旧/手动场景)。
     * 均不存在返回 null(= 自动导入关闭)。
     */
    static Path tarballFor(WorkerProperties props) {
        Path bundled = props.resolveRuntimeDir().resolve("wsl").resolve("eagent-rootfs.tar.gz");
        if (Files.isRegularFile(bundled)) {
            return bundled;
        }
        String t = props.getSandbox().getWsl().getTarball();
        if (t == null || t.isBlank()) {
            return null;
        }
        Path p = Path.of(t.trim());
        Path abs = p.isAbsolute() ? p : props.resolveHomeDir().resolve(p);
        return Files.isRegularFile(abs) ? abs : null;
    }

    /** 运行期目标发行版:显式配置 > (镜像在位 ? 托管 {@link #MANAGED_DISTRO} : WSL 默认)。 */
    public static String effectiveDistro(WorkerProperties props) {
        String d = props.getSandbox().getWsl().getDistro();
        if (d != null && !d.isBlank()) {
            return d.trim();
        }
        return tarballFor(props) != null ? MANAGED_DISTRO : "";
    }

    /**
     * 后端可用性探测(一次性,结果由 OsSandbox 缓存):发行版可 exec + python3 在位 +
     * bwrap 可建非特权沙箱(user namespace 可用性的端到端验证)。
     *
     * <p>失败即定位断因:{@link Cause} 每项自带修正动作,回退日志单行可执行——
     * 「wsl-bwrap 不可用」不再让人猜是发行版名、缺包还是 userns。快路径(冒烟)一次
     * wsl.exe 调用;失败后的定位至多再加两次(发行版列表 + P/B/S 标记),仅启动时发生。
     * 发行版缺失时的托管镜像自动导入(L2 自举)由 {@link #autoImport} 承担,
     * OsSandbox 在 DISTRO_NOT_FOUND 断因后调用,此处不掺和。
     */
    /** bwrap 冒烟:与 eagent-run.py 的 RO_BASE + 基础挂载逐字同构。bwrap 的 root 是
     * 全新 tmpfs,只有显式 bind 的路径才存在——旧 root 的 /bin、/lib64 等 symlink
     * 不会带进去,ELF 解释器缺失即 execvp ENOENT(真机曾因此把好端端的 userns
     * 误报成故障)。改这里必须同步 eagent-run.py 的 RO_BASE(测试钉住关键要素)。 */
    static final String SMOKE_BWRAP = "bwrap --die-with-parent"
            + " --ro-bind-try /usr /usr --ro-bind-try /etc /etc --ro-bind-try /opt /opt"
            + " --ro-bind-try /var /var --ro-bind-try /bin /bin --ro-bind-try /sbin /sbin"
            + " --ro-bind-try /lib /lib --ro-bind-try /lib64 /lib64 --ro-bind-try /libx32 /libx32"
            + " --proc /proc --dev /dev --tmpfs /tmp --tmpfs /run -- /bin/true";

    public static ProbeResult probe(WorkerProperties props) {
        String distro = effectiveDistro(props);
        // bwrap 冒烟本身即 userns 探针——冒烟失败即生产失败,反之亦然
        String cmd = "command -v python3 >/dev/null && " + SMOKE_BWRAP;
        Capture c = runCapture(wslCmd(distro, "-e", "/bin/sh", "-c", cmd), PROBE_TIMEOUT_MS);
        return c.rc() == 0 ? new ProbeResult(true, null, "") : diagnose(distro, c);
    }

    /**
     * L2 自举:发行版缺失时的托管镜像自动导入(OsSandbox 在 DISTRO_NOT_FOUND 时调用)。
     *
     * <p>只服务托管目标——未配置 distro 且镜像在位,或显式配置 {@code eagent};用户显式
     * 指定的其他名字不越权代装。流程:sha256 校验(fail-closed,镜像同目录
     * {@code .sha256})→ 清空安装目录残留 → {@code wsl --import eagent <系统目录>/wsl/distro
     * <镜像> --version 2} → 由调用方重探。不可导入/失败返回可读断因(走统一回退日志);
     * 镜像在位且发行版缺失通常意味着首次安装,一次性成本(解包 ≤ 数分钟)。
     *
     * <p>重探函数由调用方注入:wsl-bwrap 与 wsl-direct 两种后端对发行版的要求不同
     * (bwrap 需 python3+bwrap,direct 只需 bash/mount/findmnt),导入成功后必须用
     * <b>发起导入的那个后端</b>自己的 probe 重探——历史 bug 是这里固定调
     * {@link #probe}(bwrap 冒烟),导致 direct 后端导入成功后被「缺 bwrap」误判回退。
     *
     * @param reprobe 导入成功后由调用方提供的重探(通常为该后端的 probe 方法引用)
     */
    public static ProbeResult autoImport(WorkerProperties props, ProbeResult prior,
            java.util.function.Supplier<ProbeResult> reprobe) {
        Path tar = tarballFor(props);
        if (tar == null || !MANAGED_DISTRO.equals(effectiveDistro(props))) {
            return prior; // 镜像不在位,或用户显式指定了非托管发行版:不越权代装
        }
        log.info("[sandbox] 托管发行版缺失,自动导入:{} ← {}(sha256 校验中)",
                MANAGED_DISTRO, tar);
        String expect = parseSha256(sha256FileText(tar));
        if (expect == null || !sha256Matches(tar, expect)) {
            log.warn("[sandbox] 托管镜像 sha256 校验失败或缺 .sha256 伴生文件,放弃导入:{}", tar);
            return new ProbeResult(false, Cause.IMPORT_FAILED,
                    "sha256 校验失败:" + tar.getFileName() + ".sha256");
        }
        Path installDir = props.resolveHomeDir().resolve("wsl").resolve("distro");
        cleanDir(installDir); // 上次半途导入的残留:目录归 worker 所有,可安全清
        // wsl.exe --import 不会递归创建 InstallLocation 的父目录;父目录缺失时报
        // ERROR_PATH_NOT_FOUND(系统找不到指定的路径)。这里先递归建目录。
        try {
            Files.createDirectories(installDir);
        } catch (IOException e) {
            log.warn("[sandbox] 创建发行版安装目录失败:{}", installDir, e);
            return new ProbeResult(false, Cause.IMPORT_FAILED, "创建安装目录失败:" + installDir);
        }
        Capture c = runCapture(List.of("wsl.exe", "--import", MANAGED_DISTRO,
                installDir.toString(), tar.toString(), "--version", "2"), IMPORT_TIMEOUT_MS);
        if (c.rc() != 0) {
            log.warn("[sandbox] wsl --import 失败 rc={} out={}", c.rc(),
                    truncate(ascii(c.diagText()), 200));
            return new ProbeResult(false, Cause.IMPORT_FAILED, truncate(ascii(c.diagText()), 200));
        }
        log.info("[sandbox] 托管发行版 {} 导入完成,重新探测", MANAGED_DISTRO);
        return reprobe.get();
    }

    private static String sha256FileText(Path tar) {
        try {
            return Files.readString(Path.of(tar + ".sha256"), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /** sha256sum 输出(<hex>  <file>)或裸 hex → 小写摘要;非法返回 null。 */
    static String parseSha256(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String hex = text.trim().split("\\s+")[0].toLowerCase(java.util.Locale.ROOT);
        return hex.matches("[0-9a-f]{64}") ? hex : null;
    }

    private static boolean sha256Matches(Path file, String expect) {
        try (InputStream in = Files.newInputStream(file)) {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                md.update(buf, 0, n);
            }
            return java.util.HexFormat.of().formatHex(md.digest()).equals(expect);
        } catch (Exception e) {
            return false; // 读失败/摘要器不可得:一律按校验不过处理
        }
    }

    /** 递归清空目录内容(自动导入的安装目录归 worker 所有);目录不存在则空操作。 */
    private static void cleanDir(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                    .filter(p -> !p.equals(dir))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // 残留清理尽力而为:清不净时 wsl --import 自会报错
                        }
                    });
        } catch (IOException notExists) {
            // 目录不存在:无需清理
        }
    }

    /** 冒烟失败后的断因定位:先认输出里的明确错误码,列表核对发行版在位性,最后 P/B-S 标记。 */
    private static ProbeResult diagnose(String distro, Capture smoke) {
        if (smoke.rc() == RC_LAUNCH_FAIL) {
            return new ProbeResult(false, Cause.WSL_UNAVAILABLE, smoke.diagText());
        }
        if (isAccessDenied(smoke.diagText())) {
            return new ProbeResult(false, Cause.ACCESS_DENIED, truncate(smoke.diagText(), 200));
        }
        if (smoke.rc() == RC_TIMEOUT) {
            return new ProbeResult(false, Cause.TIMEOUT, "");
        }
        // 发行版在位性以 wsl -l -v 为准:错误码作快路径,列表核对兜住任何 locale 文案
        DistroList dl = listDistros();
        boolean missing = dl != null && !distro.isBlank() && !dl.names().contains(distro);
        if (isDistroNotFound(smoke.diagText()) || missing) {
            return distroMissing(distro, dl, smoke);
        }
        String markers = "command -v python3 >/dev/null; echo P=$?; "
                + "command -v bwrap >/dev/null; echo B=$?; "
                + SMOKE_BWRAP + " >/dev/null 2>&1; echo S=$?";
        Capture d = runCapture(wslCmd(distro, "-e", "/bin/sh", "-c", markers), DIAG_TIMEOUT_MS);
        Cause cause = d.rc() == 0 ? parseMarkers(d.out()) : null;
        if (cause == Cause.USERNS_FAILED) {
            return new ProbeResult(false, cause, "bwrap 冒烟 rc=" + marker(d.out(), "S="));
        }
        if (cause != null) {
            return new ProbeResult(false, cause, "");
        }
        // 标记全过但冒烟失败:瞬时态(VM 冷启动竞态一类),带原始输出让人复跑
        return new ProbeResult(false, Cause.UNKNOWN, truncate(smoke.diagText(), 200));
    }

    /** wsl -l -v 列表;拿不到(rc!=0)返回 null,调用方不据此判定在位性。 */
    private static DistroList listDistros() {
        Capture l = runCapture(List.of("wsl.exe", "-l", "-v"), LIST_TIMEOUT_MS);
        return l.rc() == 0 ? parseDistros(l.out()) : null;
    }

    private static ProbeResult distroMissing(String distro, DistroList dl, Capture smoke) {
        if (dl == null) {
            return new ProbeResult(false, Cause.DISTRO_NOT_FOUND, truncate(smoke.diagText(), 200));
        }
        String installed = dl.names().isEmpty() ? "(无)"
                : String.join(", ", dl.names()) + (dl.def() == null ? "" : "(默认 " + dl.def() + ")");
        return new ProbeResult(false, Cause.DISTRO_NOT_FOUND,
                (distro.isBlank() ? "未配置 distro 且无发行版可用;已装: " : "已装: ") + installed);
    }

    /** 探测断因:desc = 是什么,fix = 怎么修(回退日志单行可执行)。 */
    public enum Cause {
        ACCESS_DENIED("WSL 服务拒绝调用方",
                "worker 须以普通 Medium-IL 用户进程运行(whoami /groups 查 S-1-16-8192);沙箱内降权嵌套运行必败,设计已知约束"),
        WSL_UNAVAILABLE("wsl.exe 不可用/启动失败",
                "确认 wsl --status 可运行(WSL 未安装或损坏)"),
        DISTRO_NOT_FOUND("发行版不存在",
                "worker.sandbox.wsl.distro 指向已有发行版(wsl -l -v 查看;留空 = 默认发行版),"
                        + "或导入托管发行版 powershell -File scripts\\wsl-sandbox-probe.ps1 -Distro eagent -Tarball rootfs.tar.gz"),
        PYTHON3_MISSING("发行版缺 python3",
                "powershell -File scripts\\wsl-sandbox-probe.ps1 -Distro <发行版> -Setup(装 python3/bubblewrap/ripgrep/git)"),
        BWRAP_MISSING("发行版缺 bwrap(bubblewrap)",
                "powershell -File scripts\\wsl-sandbox-probe.ps1 -Distro <发行版> -Setup(装 python3/bubblewrap/ripgrep/git)"),
        USERNS_FAILED("bwrap 冒烟失败(user namespace 不可用)",
                "Ubuntu 24.04 尝试 sudo sysctl kernel.apparmor_restrict_unprivileged_userns=0;"
                        + "详见 docs/ARCHITECTURE.md §7.10"),
        TIMEOUT("探测超时(WSL 冷启动/卡死)",
                "wsl --shutdown 后重启 worker 重试"),
        IMPORT_FAILED("托管镜像自动导入失败",
                "检查 <系统目录>/wsl/eagent-rootfs.tar.gz 与同名 .sha256 是否完好;或手动导入 "
                        + "powershell -File scripts\\wsl-sandbox-probe.ps1 -Distro eagent -Tarball <镜像>;"
                        + "详见 docs/ARCHITECTURE.md §7.10"),
        UNKNOWN("未归类失败",
                "复跑 powershell -File scripts\\wsl-sandbox-probe.ps1 全量探针定位");

        private final String desc;
        private final String fix;

        Cause(String desc, String fix) {
            this.desc = desc;
            this.fix = fix;
        }

        public String desc() {
            return desc;
        }

        public String fix() {
            return fix;
        }
    }

    /** probe 结果:ok=true;否则 cause + detail(命令输出摘录)。 */
    public record ProbeResult(boolean ok, Cause cause, String detail) {

        /** 单行断因(日志用):desc + 细节摘录。 */
        public String brief() {
            if (ok) {
                return "ok";
            }
            if (detail == null || detail.isBlank()) {
                return cause.desc();
            }
            return cause.desc() + ": " + truncate(detail.replaceAll("[\\r\\n]+", "; "), 200);
        }

        /** 对应修正动作(日志用)。 */
        public String fix() {
            return ok ? "" : cause.fix();
        }
    }

    /** 诊断标记(P/B/S)→ 断因;解析不出返回 null。 */
    static Cause parseMarkers(String out) {
        Integer p = marker(out, "P=");
        Integer b = marker(out, "B=");
        Integer s = marker(out, "S=");
        if (p == null || b == null || s == null) {
            return null;
        }
        if (p != 0) {
            return Cause.PYTHON3_MISSING;
        }
        if (b != 0) {
            return Cause.BWRAP_MISSING;
        }
        return s == 0 ? null : Cause.USERNS_FAILED;
    }

    private static Integer marker(String out, String key) {
        for (String line : ascii(out).split("\\r?\\n")) {
            String t = line.trim();
            if (t.startsWith(key)) {
                try {
                    return Integer.parseInt(t.substring(key.length()).trim());
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    /** wsl -l -v 输出解析:发行版名列表 + 默认发行版(带 * 者)。无表头 = 非列表输出(如「没有已安装的分发」)。 */
    static DistroList parseDistros(String listOut) {
        List<String> names = new ArrayList<>();
        String def = null;
        boolean header = false;
        for (String line : ascii(listOut).split("\\r?\\n")) {
            String t = line.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (t.startsWith("NAME") || t.contains("VERSION")) {
                header = true;
                continue; // 表头
            }
            if (!header) {
                continue; // 无表头:提示消息而非列表
            }
            boolean isDefault = t.startsWith("*");
            if (isDefault) {
                t = t.substring(1).trim();
            }
            String name = t.split("\\s+")[0];
            if (name.isEmpty()) {
                continue;
            }
            if (isDefault) {
                def = name;
            }
            names.add(name);
        }
        return new DistroList(names, def);
    }

    record DistroList(List<String> names, String def) {
    }

    /** 去 UTF-16LE 空字节(旧版 wsl.exe 不识别 WSL_UTF8 时的输出形态),供错误码匹配。 */
    static String ascii(String s) {
        return s == null ? "" : s.replace("\0", "");
    }

    /** 错误诊断用合并视图:wsl.exe 错误可能落在 stdout 或 stderr,合并后 contains 匹配语义不变。 */
    static String mergeDiagnostics(String out, String err) {
        if (out == null || out.isEmpty()) {
            return err == null ? "" : err;
        }
        if (err == null || err.isEmpty()) {
            return out;
        }
        return out + "\n" + err;
    }

    /** wsl.exe 输出是否表示「发行版不存在」:新旧错误码 + 中英文文案,先剥 UTF-16 NUL。 */
    static boolean isDistroNotFound(String rawOut) {
        String raw = ascii(rawOut);
        return raw.contains("WSL_E_DISTRO_NOT_FOUND") || raw.contains("DISTRO_NOT_FOUND")
                || raw.contains("no distribution") || raw.contains("not installed")
                || raw.contains("找不到发行版") || raw.contains("不存在")
                || raw.contains("没有已安装的分发");
    }

    /** wsl.exe 输出是否表示「服务拒绝调用方」(降权/提权进程,常见 Wsl/E_ACCESSDENIED)。 */
    static boolean isAccessDenied(String rawOut) {
        return ascii(rawOut).contains("E_ACCESSDENIED");
    }

    /**
     * 在 wsl-bwrap 沙箱中执行命令(shell 语义:bash -c;powershell 需发行版内 pwsh 且
     * 显式启用;cmd/auto 归一到 bash——后端选择已保证注册 BashTool)。
     *
     * @param extraRoots PermissionGate 已授权的命令 EXEC 根(Windows 域),逐个按 /mnt 原生形态挂入
     */
    public static OsResult run(String command, Path cwd, Map<String, String> extraEnv,
            WorkerProperties props, ExecutorService exec, int maxOut, String shell, List<Path> extraRoots,
            boolean allowNetwork, boolean allowPrivilege) {
        WorkerProperties.Sandbox cfg = props.getSandbox();
        String distro = effectiveDistro(props);

        List<String> argv = switch (shell == null ? "auto" : shell) {
            case "powershell" -> cfg.getWsl().isPwshEnabled()
                    ? List.of("pwsh", "-NoProfile", "-Command", command)
                    : null;
            // login-shell 开启时以登录 shell(bash -lc)执行:自动加载 /etc/profile 与 ~/.profile,
            // 使 profile 里 export 的环境变量对每条命令生效(白名单环境重建保留,profile export 可覆盖)
            default -> cfg.getWsl().isLoginShell()
                    ? List.of("bash", "-lc", command)
                    : List.of("bash", "-c", command); // bash / cmd / auto
        };
        if (argv == null) {
            return new OsResult("", "[sandbox] WSL 后端未启用 pwsh(worker.sandbox.wsl.pwsh-enabled),"
                    + "powershell 命令不可执行;请使用 bash 方言", 1, false);
        }

        String runId = "r" + Long.toUnsignedString(System.nanoTime(), 36);
        if (WslPathMapper.toWsl(cwd) == null) {
            return new OsResult("", "[sandbox] 工作区不在本地盘(UNC/相对路径),无法映射进发行版: " + cwd,
                    1, false);
        }
        Map<String, Object> payload = buildPayload(runId, argv, cwd, extraEnv, cfg, extraRoots, props,
                allowNetwork, allowPrivilege);
        String json;
        try {
            json = JSON.writeValueAsString(payload);
        } catch (IOException e) {
            return new OsResult("", "[sandbox] 载荷序列化失败: " + e.getMessage(), 1, false);
        }

        Path runner;
        try {
            runner = resolveRunner(props);
        } catch (IOException e) {
            return new OsResult("", "[sandbox] eagent-run 定位失败: " + e.getMessage(), 1, false);
        }
        String runnerInDistro = WslPathMapper.toWsl(runner);
        if (runnerInDistro == null) {
            return new OsResult("", "[sandbox] worker 系统目录不在本地盘,无法映射进发行版: " + runner, 1, false);
        }

        List<String> cmd = wslCmd(distro, "-e", "python3", runnerInDistro);
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.environment().remove("WSLENV"); // 环境走载荷白名单,wsl.exe 侧不中继
            Process p = pb.start();
            byte[] payloadBytes = json.getBytes(StandardCharsets.UTF_8);
            // stdin 载荷写入必须独立线程:载荷超过管道缓冲时阻塞写会死锁
            exec.submit(() -> {
                try (OutputStream os = p.getOutputStream()) {
                    os.write(payloadBytes);
                    os.flush();
                } catch (IOException e) {
                    // 子进程先退/管道断:写入失败无害,读取侧自然收尾
                }
            });
            Future<String> out = exec.submit(() -> drain(p.getInputStream()));
            Future<String> err = exec.submit(() -> drain(p.getErrorStream()));
            boolean aborted = false;
            String outText;
            String errText;
            try {
                outText = out.get(cfg.getTimeoutMs(), TimeUnit.MILLISECONDS);
                errText = awaitQuiet(err);
            } catch (TimeoutException e) {
                aborted = true;
                p.destroyForcibly(); // 只杀 wsl.exe;Linux 侧树靠 pgid 收割
                killGroup(distro, runId, exec);
                outText = awaitQuiet(out);
                errText = awaitQuiet(err) + "\n[exec 超时中止: >" + cfg.getTimeoutMs() + "ms]";
            } catch (java.util.concurrent.ExecutionException e) {
                outText = "";
                errText = awaitQuiet(err);
            }
            int code = aborted ? -1 : p.waitFor();
            return new OsResult(capOutput(outText, maxOut), capOutput(errText, maxOut), code, aborted);
        } catch (IOException e) {
            return new OsResult("", "exec 启动失败(wsl.exe / 发行版 " + distroLabel(distro) + "): " + e.getMessage(),
                    1, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new OsResult("", "exec 被中断", 1, false);
        }
    }

    /**
     * seccomp 提权授权回调:沙箱内进程尝试 exec setuid 二进制时由监听器上报,
     * 返回 true=放行该次 exec;false=EPERM 拒绝(与 PermissionGate 拒绝语义一致)。
     */
    @FunctionalInterface
    public interface PrivilegeAuthorizer {
        boolean authorize(String execPath, long pid, String syscall);
    }

    /**
     * seccomp 内核级提权拦截执行(docs/ARCHITECTURE.md §7.11):
     * 载荷加 {@code "seccomp": true} 让 eagent-run.py 进入 seccomp supervisor 模式;
     * stdout 为帧流(out/priv-ask/exit),stderr 原样;priv-ask 时经 stdin 回 priv-ans。
     * 与普通 {@link #run} 的差异:stdin 保持打开(载荷 + 响应),stdout 按帧解析。
     */
    public static OsResult runSeccomp(String command, Path cwd, Map<String, String> extraEnv,
            WorkerProperties props, ExecutorService exec, int maxOut, String shell, List<Path> extraRoots,
            boolean allowNetwork, PrivilegeAuthorizer authorizer) {
        WorkerProperties.Sandbox cfg = props.getSandbox();
        String distro = effectiveDistro(props);

        List<String> argv = switch (shell == null ? "auto" : shell) {
            case "powershell" -> cfg.getWsl().isPwshEnabled()
                    ? List.of("pwsh", "-NoProfile", "-Command", command)
                    : null;
            // login-shell 开启时以登录 shell(bash -lc)执行:自动加载 /etc/profile 与 ~/.profile,
            // 使 profile 里 export 的环境变量对每条命令生效(白名单环境重建保留,profile export 可覆盖)
            default -> cfg.getWsl().isLoginShell()
                    ? List.of("bash", "-lc", command)
                    : List.of("bash", "-c", command); // bash / cmd / auto
        };
        if (argv == null) {
            return new OsResult("", "[sandbox] WSL 后端未启用 pwsh(worker.sandbox.wsl.pwsh-enabled),"
                    + "powershell 命令不可执行;请使用 bash 方言", 1, false);
        }

        String runId = "r" + Long.toUnsignedString(System.nanoTime(), 36);
        if (WslPathMapper.toWsl(cwd) == null) {
            return new OsResult("", "[sandbox] 工作区不在本地盘(UNC/相对路径),无法映射进发行版: " + cwd,
                    1, false);
        }
        // seccomp 拦截语义 = 沙箱以普通用户运行、setuid 逐个经授权;不传全局 privileged
        Map<String, Object> payload = buildPayload(runId, argv, cwd, extraEnv, cfg, extraRoots, props,
                allowNetwork, false);
        payload.put("seccomp", true);
        String json;
        try {
            json = JSON.writeValueAsString(payload);
        } catch (IOException e) {
            return new OsResult("", "[sandbox] 载荷序列化失败: " + e.getMessage(), 1, false);
        }

        Path runner;
        try {
            runner = resolveRunner(props);
        } catch (IOException e) {
            return new OsResult("", "[sandbox] eagent-run 定位失败: " + e.getMessage(), 1, false);
        }
        String runnerInDistro = WslPathMapper.toWsl(runner);
        if (runnerInDistro == null) {
            return new OsResult("", "[sandbox] worker 系统目录不在本地盘,无法映射进发行版: " + runner, 1, false);
        }

        List<String> cmd = wslCmd(distro, "-e", "python3", runnerInDistro);
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.environment().remove("WSLENV"); // 环境走载荷白名单,wsl.exe 侧不中继
            Process p = pb.start();
            OutputStream stdin = p.getOutputStream();
            // 载荷以单行写入,stdin 保持打开供 priv-ans 响应(eagent-run 读首行载荷)
            stdin.write((json + "\n").getBytes(StandardCharsets.UTF_8));
            stdin.flush();

            StringBuilder outBuf = new StringBuilder();
            int[] exitCode = { 1 };
            String[] rerootPath = { null };  // priv-reroot 帧:授权 root 重跑(§6A.2),提前结束帧读取
            // block 形式显式 return null:void 方法不能作为 Callable<Void> 的表达式体(需 Void 值),
            // 而 throws IOException 又排除了 Runnable;只有 Callable<Void> 同时满足两者
            Future<Void> reader = exec.submit(() -> {
                readSeccompFrames(p, stdin, authorizer, outBuf, exitCode, rerootPath);
                return null;
            });
            Future<String> err = exec.submit(() -> drain(p.getErrorStream()));
            boolean aborted = false;
            String errText;
            try {
                reader.get(cfg.getTimeoutMs(), TimeUnit.MILLISECONDS);
                errText = awaitQuiet(err);
            } catch (TimeoutException e) {
                aborted = true;
                p.destroyForcibly(); // 只杀 wsl.exe;Linux 侧树靠 pgid 收割
                killGroup(distro, runId, exec);
                reader.cancel(true);
                errText = awaitQuiet(err) + "\n[exec 超时中止: >" + cfg.getTimeoutMs() + "ms]";
            } catch (java.util.concurrent.ExecutionException e) {
                errText = awaitQuiet(err);
            }
            int code = aborted ? -1 : exitCode[0];
            // 授权 root 重跑(§6A.2):supervisor 已 EPERM + 收沙箱 + exit 75;此处另起
            // wsl.exe -u root 在发行版内执行原始命令(root 下 sudo 直接生效,无需去 sudo),
            // 输出合并进本命令结果
            if (rerootPath[0] != null) {
                return rootExec(distro, command, outBuf.toString(), errText, maxOut);
            }
            return new OsResult(capOutput(outBuf.toString(), maxOut), capOutput(errText, maxOut),
                    code, aborted);
        } catch (IOException e) {
            return new OsResult("", "exec 启动失败(wsl.exe / 发行版 " + distroLabel(distro) + "): " + e.getMessage(),
                    1, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new OsResult("", "exec 被中断", 1, false);
        }
    }

    /** 读取并处理 seccomp 帧流:out→解码进输出;priv-ask→授权并回 priv-ans;exit→记录退出码并结束;
     * priv-reroot(授权 root 重跑,§6A.2)→记录路径并立即结束帧读取(后续由 rootExec 接管)。 */
    private static void readSeccompFrames(Process p, OutputStream stdin, PrivilegeAuthorizer authorizer,
            StringBuilder outBuf, int[] exitCode, String[] rerootPath) throws IOException {
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    JsonNode f = JSON.readTree(line);
                    String mk = f.path("mk").asText("");
                    switch (mk) {
                        case "out" -> outBuf.append(new String(
                                Base64.getDecoder().decode(f.path("d").asText("")), StandardCharsets.UTF_8));
                        case "priv-ask" -> {
                            String path = f.path("path").asText("");
                            long pid = f.path("pid").asLong();
                            String syscall = f.path("syscall").asText("execve");
                            // id 是内核通知号(u64 雪花范围,超 Java long):以字符串原样透传,
                            // Python json.loads 自动转 Python int(无溢出),勿改回 asLong
                            String id = f.path("id").asText("");
                            boolean ok = authorizer != null && authorizer.authorize(path, pid, syscall);
                            ObjectNode resp = JSON.createObjectNode();
                            resp.put("mk", "priv-ans");
                            resp.put("id", id);
                            resp.put("ok", ok);
                            resp.put("reroot", ok); // 授权语义(§6A.2):允许 = 以 root 重跑,沙箱内不提权
                            stdin.write((resp.toString() + "\n").getBytes(StandardCharsets.UTF_8));
                            stdin.flush();
                        }
                        case "priv-reroot" -> {
                            // supervisor 确认转入 root 重跑:沙箱已收,后续帧无意义
                            rerootPath[0] = f.path("path").asText("");
                            return;
                        }
                        case "exit" -> {
                            exitCode[0] = f.path("code").asInt(1);
                            return;
                        }
                        default -> {
                            // 未知帧忽略(前向兼容)
                        }
                    }
                } catch (RuntimeException e) {
                    log.debug("[sandbox] 忽略畸形 seccomp 帧: {}", line);
                }
            }
        }
    }

    /**
     * 授权后 root 重跑(§6A.2):wsl.exe -d <发行版> -u root 直接在发行版内执行原始命令。
     * <p>WSL 既有安全模型允许 Windows 用户以 root 进入发行版(与用户终端 sudo 等价),
     * 本方法只在 priv-ask 授权通过后才被调用,不构成新增权限面。root 下 sudo 直接生效,
     * 命令原样执行不去 sudo(-u 用户切换语义因此保留)。输出与本命令已收到的沙箱输出
     * 合并。
     */
    private static OsResult rootExec(String distro, String command, String sandboxOut, String sandboxErr,
            int maxOut) {
        List<String> cmd = wslCmd(distro, "-u", "root", "-e", "bash", "-c", command);
        StringBuilder out = new StringBuilder(sandboxOut);
        if (!out.isEmpty()) {
            out.append('\n');
        }
        StringBuilder err = new StringBuilder(sandboxErr == null ? "" : sandboxErr);
        int code = 1;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.environment().remove("WSLENV");
            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String l;
                while ((l = r.readLine()) != null) {
                    out.append(l).append('\n');
                }
            }
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
                String l;
                while ((l = r.readLine()) != null) {
                    err.append(l).append('\n');
                }
            }
            code = p.waitFor();
        } catch (IOException e) {
            err.append("[root-rerun] 启动失败(wsl -u root): ").append(e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            err.append("[root-rerun] 被中断");
        }
        return new OsResult(capOutput(out.toString(), maxOut),
                capOutput(err.toString(), maxOut), code, false);
    }

    /** OsSandbox.ExecResult 的值语义副本(避免 wsl 后端反向依赖门面类)。 */
    public record OsResult(String stdout, String stderr, int exitCode, boolean aborted) {
    }

    /** 组装发行版侧载荷:绑定白名单、环境白名单、网络策略、资源上限(§4.2 契约)。 */
    private static Map<String, Object> buildPayload(String runId, List<String> argv, Path cwd,
            Map<String, String> extraEnv, WorkerProperties.Sandbox cfg, List<Path> extraRoots,
            WorkerProperties props, boolean allowNetwork, boolean allowPrivilege) {
        String nativeWs = WslPathMapper.toWsl(cwd);
        Set<String> boundDest = new LinkedHashSet<>();
        List<Map<String, String>> binds = new ArrayList<>();
        List<Map<String, String>> roIslands = new ArrayList<>();

        // 工作区双挂载:规范入口 /workspace + 原生 /mnt/... (模型两种写法都可达)。
        // src 一律用发行版内可见的 /mnt 形态(bwrap 在发行版内解析源路径,Windows 形态不存在)
        binds.add(bind(nativeWs, WslPathMapper.WORKSPACE_MOUNT));
        boundDest.add(WslPathMapper.WORKSPACE_MOUNT);
        if (boundDest.add(nativeWs)) {
            binds.add(bind(nativeWs, nativeWs));
        }
        // worker 级共享持久状态:软件安装目录(/opt、/usr/local)、HOME(/root)、
        // resolv.conf 与持久 env 全部落在 worker 数据目录,跨任务/跨工作区共享(装一次处处可用)
        if (cfg.isPersistentState()) {
            applyPersistentState(props, binds, boundDest);
        }
        // 授权 EXEC 根:原生形态挂载(与命令文本一致);不可映射/不存在的跳过并告警
        for (Path root : extraRoots == null ? List.<Path>of() : extraRoots) {
            String dest = WslPathMapper.toWsl(root);
            if (dest == null) {
                log.warn("[sandbox] 授权根不可映射进发行版,跳过绑定: {}", root);
                continue;
            }
            if (!Files.exists(root)) {
                log.warn("[sandbox] 授权根不存在,跳过绑定: {}", root);
                continue;
            }
            if (boundDest.add(dest)) {
                binds.add(bind(dest, dest));
            }
        }
        // 只读岛(工作区相对路径,默认空——agent 需要提交,.git 不默认保护)
        for (String rel : cfg.getWsl().getRoIslands()) {
            Path srcWin = cwd.resolve(rel).normalize();
            if (!Files.exists(srcWin)) {
                continue;
            }
            String src = WslPathMapper.toWsl(srcWin);
            if (src == null) {
                continue;
            }
            String relPosix = rel.replace('\\', '/');
            roIslands.add(bind(src, WslPathMapper.WORKSPACE_MOUNT + "/" + relPosix));
            roIslands.add(bind(src, src));
        }

        // 环境:白名单重建 + XDG 三件套指向工作区内(pip/npm/dotnet 缓存不落发行版)
        ensureDirs(cwd);
        Map<String, String> env = new LinkedHashMap<>();
        env.put("PATH", DISTRO_PATH);
        boolean persistent = cfg.isPersistentState();
        String homeDir = persistent ? PERSIST_HOME_MOUNT : WslPathMapper.WORKSPACE_MOUNT + "/.eagent/home";
        String cacheDir = persistent ? PERSIST_HOME_MOUNT + "/.cache"
                : WslPathMapper.WORKSPACE_MOUNT + "/.eagent/cache";
        String configDir = persistent ? PERSIST_HOME_MOUNT + "/.config"
                : WslPathMapper.WORKSPACE_MOUNT + "/.eagent/config";
        String dataDir = persistent ? PERSIST_HOME_MOUNT + "/.local/share"
                : WslPathMapper.WORKSPACE_MOUNT + "/.eagent/data";
        env.put("HOME", homeDir);
        env.put("TMPDIR", "/tmp");
        env.put("LANG", "C.UTF-8");
        env.put("LC_ALL", "C.UTF-8");
        env.put("TERM", "dumb");
        env.put("XDG_CACHE_HOME", cacheDir);
        env.put("XDG_CONFIG_HOME", configDir);
        env.put("XDG_DATA_HOME", dataDir);
        if (persistent) {
            applyPersistentEnv(props, env);
        }
        if (extraEnv != null) {
            for (Map.Entry<String, String> e : extraEnv.entrySet()) {
                env.put(e.getKey(), e.getValue());
            }
        }
        if (!allowNetwork) {
            PROXY_KEYS.forEach(env::remove);
        }

        boolean netDeny = !allowNetwork;
        long timeoutSec = cfg.getTimeoutMs() / 1000 + 60; // CPU 秒上限≥墙钟,留余量
        Map<String, Object> limits = Map.of(
                // RLIMIT_NPROC 是「该 real uid 在发行版内的全部进程数」全局上限,不是
                // Windows Job Object 的「作业内活动进程数」;直接把 active-process-limit
                // (默认 32)映射过来,会把 uid 下既有进程(如其它 wsl 会话 / IDE server /
                // 默认用户服务)一并计入——bwrap 建 namespace 要 clone 新 helper 进程,
                // 轻易撞满 32 报 EAGAIN:『Creating new namespace failed:
                // Resource temporarily unavailable』(冒烟不经 setrlimit 因此通过)。
                // 进程树上限留给 cgroup pids-max(Phase 2),此处 0 = 不设 RLIMIT_NPROC,
                // 失控兜底仍由超时 pgid 击杀承担。
                "nproc", 0,
                "asMb", Math.max(0, cfg.getMemoryLimitMb()),
                "cpuSec", timeoutSec,
                "fsizeMb", 0);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", runId);
        payload.put("argv", argv);
        payload.put("cwd", WslPathMapper.WORKSPACE_MOUNT);
        payload.put("env", env);
        payload.put("binds", binds);
        payload.put("roIslands", roIslands);
        payload.put("network", netDeny ? "deny" : "open");
        payload.put("privileged", allowPrivilege);
        payload.put("limits", limits);
        return payload;
    }

    private static Map<String, String> bind(String src, String dest) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("src", src);
        m.put("dest", dest);
        return m;
    }

    /** 沙箱内持久状态的入口挂载点(便于 AI 直接读写持久 env / resolv,如 /persist/env)。 */
    static final String PERSIST_ROOT_MOUNT = "/persist";

    /**
     * worker 级共享持久状态(装一次、处处可用):把 &lt;persistentRoot&gt; 下的
     * home/opt/usr-local/resolv.conf 以读写绑定挂入固定挂载点,并预建目录与默认文件。
     * <p>设计:持久根落在 worker 数据目录(默认 &lt;dataDir&gt;/sandbox),宿主 NTFS 侧;
     * 每次命令经新的 bwrap 命名空间把这些目录 bind 进去——软件装入 /opt 或 /usr/local
     * 即跨任务/跨工作区共享,不随命令结束丢失。resolv.conf 持久化修复沙箱内 DNS
     * (发行版基座只读、/mnt/wsl 未挂载,原 resolv 悬空导致解析失败)。
     * <p>与「每次命令新建命名空间」的既有隔离语义兼容:持久根只暴露 worker 批准的
     * 固定目录,工作区读白名单不变。
     */
    static void applyPersistentState(WorkerProperties props, List<Map<String, String>> binds,
            Set<String> boundDest) {
        Path root = props.resolveSandboxPersistentRoot();
        Path home = root.resolve(PERSIST_DIR_HOME);
        Path opt = root.resolve(PERSIST_DIR_OPT);
        Path usrLocal = root.resolve(PERSIST_DIR_USR_LOCAL);
        Path resolv = root.resolve(PERSIST_FILE_RESOLV);
        Path envFile = root.resolve(PERSIST_FILE_ENV);
        try {
            Files.createDirectories(home);
            Files.createDirectories(opt);
            Files.createDirectories(usrLocal);
            if (!Files.exists(resolv)) {
                // WSL 默认 DNS;沙箱内 /mnt/wsl 未挂载,原 resolv.conf(→/mnt/wsl/resolv.conf)悬空
                Files.writeString(resolv, "nameserver 10.255.255.254\n", StandardCharsets.UTF_8);
            }
            if (!Files.exists(envFile)) {
                Files.writeString(envFile, "# 持久环境变量(worker 级共享):每行 KEY=VALUE\n",
                        StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.warn("[sandbox] 预建持久状态失败(persistentRoot={}): {}", root, e.getMessage());
        }
        // 绑定顺序:先挂 /persist(暴露整棵持久根),再挂固定子点(遮蔽宿主只读基座)
        addPersistentBind(binds, boundDest, root, PERSIST_ROOT_MOUNT);
        addPersistentBind(binds, boundDest, home, PERSIST_HOME_MOUNT);
        addPersistentBind(binds, boundDest, opt, PERSIST_OPT_MOUNT);
        addPersistentBind(binds, boundDest, usrLocal, PERSIST_USR_LOCAL_MOUNT);
        if (Files.isRegularFile(resolv)) {
            addPersistentBind(binds, boundDest, resolv, PERSIST_RESOLV_MOUNT);
        }
    }

    private static void addPersistentBind(List<Map<String, String>> binds, Set<String> boundDest,
            Path src, String dest) {
        String srcWsl = WslPathMapper.toWsl(src);
        if (srcWsl == null) {
            log.warn("[sandbox] 持久状态路径不可映射进发行版,跳过 {}: {}", dest, src);
            return;
        }
        if (boundDest.add(dest)) {
            binds.add(bind(srcWsl, dest));
        }
    }

    /**
     * 读取持久 env 文件(&lt;persistentRoot&gt;/env,KEY=VALUE 每行,# 注释/空行忽略)
     * 并合入命令环境;重复键覆盖基础 env。持久 env 由 AI 直接写 /persist/env 维护
     * (同一条命令内同时可见,用于长期注入 PATH/JAVA_HOME 等工具链变量)。
     */
    static void applyPersistentEnv(WorkerProperties props, Map<String, String> env) {
        Path envFile = props.resolveSandboxPersistentRoot().resolve(PERSIST_FILE_ENV);
        if (!Files.isRegularFile(envFile)) {
            return;
        }
        try (var lines = Files.lines(envFile, StandardCharsets.UTF_8)) {
            lines.map(String::trim)
                    .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                    .forEach(l -> {
                        int eq = l.indexOf('=');
                        if (eq > 0) {
                            env.put(l.substring(0, eq).trim(), l.substring(eq + 1).trim());
                        }
                    });
        } catch (IOException e) {
            log.warn("[sandbox] 读取持久 env 失败: {}", e.getMessage());
        }
    }

    /** HOME/XDG 目标目录预建(NTFS 侧创建,发行版内直接可用;幂等)。 */
    private static void ensureDirs(Path cwd) {
        for (String rel : List.of(".eagent/home", ".eagent/cache", ".eagent/config", ".eagent/data")) {
            try {
                Files.createDirectories(cwd.resolve(rel));
            } catch (IOException e) {
                log.debug("[sandbox] 预建 {} 失败(命令内 mkdir 兜底): {}", rel, e.getMessage());
            }
        }
    }

    /** 读 eagent-run.py 字节(程序根 runtime/wsl/,只读;wsl-direct 经 stdin 送进发行版用)。 */
    static byte[] runnerBytes(WorkerProperties props) throws IOException {
        return Files.readAllBytes(resolveRunner(props));
    }

    /**
     * eagent-run.py 定位(程序根 {@code <程序根>/runtime/wsl/eagent-run.py},随安装/解压分发、
     * 运行时只读引用,不再落地复制);缺失抛 IOException。
     */
    static Path resolveRunner(WorkerProperties props) throws IOException {
        Path target = props.resolveRuntimeDir().resolve("wsl").resolve("eagent-run.py");
        if (!Files.isRegularFile(target)) {
            throw new IOException("程序根缺少 eagent-run.py: " + target
                    + "(放置: <程序根>/runtime/wsl/eagent-run.py;程序根 = JVM 工作目录)");
        }
        return target;
    }

    /**
     * 显式收割发行版内进程组:wsl.exe 退出不保证杀死 Linux 后代,超时必须
     * {@code pkill -g <pgid>};pgid 登记文件一并清理。尽力而为,不抛出。
     */
    private static void killGroup(String distro, String runId, ExecutorService exec) {
        String sh = "cat /run/eagent/" + runId + ".pgid 2>/dev/null | xargs -r pkill -9 -g; "
                + "rm -f /run/eagent/" + runId + ".pgid";
        try {
            Process k = new ProcessBuilder(wslCmd(distro, "-e", "/bin/sh", "-c", sh))
                    .redirectErrorStream(true).start();
            exec.submit(() -> {
                try (InputStream ignored = k.getInputStream()) {
                    ignored.transferTo(OutputStream.nullOutputStream());
                } catch (IOException ignored) {
                    // 丢弃输出防管道阻塞
                }
                try {
                    k.waitFor(KILL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                k.destroyForcibly();
            });
        } catch (IOException e) {
            log.warn("[sandbox] 进程组击杀命令发起失败 runId={}: {}", runId, e.getMessage());
        }
    }

    /**
     * 探测/诊断用:跑一条命令拿 rc + 合并输出。
     *
     * <p>读取必须在独立线程:子进程不写也不退时 {@code in.read} 无限阻塞,主线程永远
     * 等不到 waitFor。出口约定:超时 {@link #RC_TIMEOUT}、启动失败(wsl.exe 不存在等)
     * {@link #RC_LAUNCH_FAIL}。输出上限 8K 字符,超限关闭管道(子进程 EPIPE 收尾)。
     */
    private static Capture runCapture(List<String> cmd, long timeoutMs) {
        try {
            // 分离两路输出:wsl.exe 的无害提示(如「检测到 localhost 代理配置」)走 stderr,
            // 列表/标记解析只看 stdout;错误码匹配在 Capture.diagText() 合并视图上做。
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(false);
            // wsl.exe 自身消息(非子进程输出)按 UTF-8 输出;旧版不识别则 UTF-16LE,ascii() 兼容匹配
            pb.environment().put("WSL_UTF8", "1");
            Process p = pb.start();
            StringBuilder outSb = new StringBuilder();
            StringBuilder errSb = new StringBuilder();
            Thread outReader = Thread.ofVirtual().start(() -> {
                try (InputStream in = p.getInputStream()) {
                    readInto(in, outSb);
                } catch (IOException ignored) {
                    // 进程被杀/管道断:读到多少算多少
                }
            });
            Thread errReader = Thread.ofVirtual().start(() -> {
                try (InputStream in = p.getErrorStream()) {
                    readInto(in, errSb);
                } catch (IOException ignored) {
                    // 进程被杀/管道断:读到多少算多少
                }
            });
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                joinQuiet(outReader, 500);
                joinQuiet(errReader, 500);
                return new Capture(RC_TIMEOUT, outSb.toString(), errSb.toString());
            }
            joinQuiet(outReader, 1000);
            joinQuiet(errReader, 1000);
            return new Capture(p.exitValue(), outSb.toString(), errSb.toString());
        } catch (IOException e) {
            return new Capture(RC_LAUNCH_FAIL, "", e.getMessage() == null ? "IOException" : e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Capture(RC_LAUNCH_FAIL, "", "interrupted");
        }
    }

    /** 将输入流按 UTF-8 读入 StringBuffer,上限 8K 字符(与旧合并读取语义一致,防超限撑爆内存)。 */
    private static void readInto(InputStream in, StringBuilder sb) throws IOException {
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) >= 0 && sb.length() < 8192) {
            sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
        }
    }

    private static void joinQuiet(Thread t, long ms) {
        try {
            t.join(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record Capture(int rc, String out, String err) {
        /** 错误匹配用合并视图:wsl.exe 错误可能落在 stdout 或 stderr。 */
        String diagText() {
            return mergeDiagnostics(out, err);
        }
    }

    /** 读任务收尾等待(进程已死后管道很快 EOF,超时/异常回退空串)。 */
    private static String awaitQuiet(Future<String> task) {
        try {
            return task.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "";
        }
    }

    private static String drain(InputStream in) throws IOException {
        try (java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return bos.toString(StandardCharsets.UTF_8);
        }
    }

    private static String capOutput(String s, int maxOut) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        return s.length() > maxOut ? s.substring(0, maxOut) + "\n[输出已截断至 " + maxOut + " 字符]" : s;
    }

    private static String truncate(String s, int n) {
        return s == null ? "" : (s.length() <= n ? s : s.substring(0, n) + "...");
    }
}
