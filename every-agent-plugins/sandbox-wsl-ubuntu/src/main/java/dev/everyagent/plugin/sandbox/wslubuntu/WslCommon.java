package dev.everyagent.plugin.sandbox.wslubuntu;

import dev.everyagent.plugin.api.config.WorkerConfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * WSL 沙箱共享类型与工具方法（从 {@code WslBwrapSandbox} 提取）。
 *
 * <p>提取了 {@link WslUbuntuSandbox}、{@link WslUmounter} 等类共同依赖的公共类型
 * 与工具方法：
 * <ul>
 *   <li>{@link OsResult}：命令执行结果 record；</li>
 *   <li>{@link ProbeResult}：探测结果 record；</li>
 *   <li>{@link Cause}：探测断因枚举；</li>
 *   <li>{@link DistroList}：发行版列表 record；</li>
 *   <li>{@link #effectiveDistro}、{@link #wslCmd}、{@link #distroLabel} 等工具方法。</li>
 * </ul>
 *
 * <p>镜像与启动器脚本由插件自己管理,按三级链定位({@code <pluginDir>/wsl/} →
 * 共享 runtime {@code resolveRuntimeDir()/wsl/}(打包 desktop 态)→ 配置 tarball,
 * 见 {@link #tarballFor}/{@link #resolveRunner})。镜像自动导入({@link #autoImport})
 * 也由插件自行承担。
 */
public final class WslCommon {

    private static final Logger log = LoggerFactory.getLogger(WslCommon.class);

    /** 托管发行版名（wsl --import 目标；镜像在位且未配置 distro 时自动启用）。 */
    public static final String MANAGED_DISTRO = "EveryAgent";

    /** 自动导入超时：~200MB 镜像解包，慢盘给足余量。 */
    private static final long IMPORT_TIMEOUT_MS = 300_000;

    private WslCommon() {
    }

    /** wsl.exe 前缀：distro 空 = 不带 -d（WSL 默认发行版）。 */
    public static List<String> wslCmd(String distro, String... rest) {
        List<String> c = new ArrayList<>();
        c.add("wsl.exe");
        if (distro != null && !distro.isBlank()) {
            c.add("-d");
            c.add(distro.trim());
        }
        c.addAll(List.of(rest));
        return c;
    }

    /** 日志用发行版标签：空 = 「默认发行版」。 */
    public static String distroLabel(String distro) {
        return distro == null || distro.isBlank() ? "默认发行版" : distro.trim();
    }

    /**
     * 托管镜像路径，三级链（架构 §7.10/§7.17）：
     * <ol>
     *   <li>插件目录 {@code <pluginDir>/wsl/eagent-rootfs.tar.gz}（.eap 安装/源码开发态）；</li>
     *   <li>共享 runtime {@code resolveRuntimeDir()/wsl/eagent-rootfs.tar.gz}（打包 desktop 态：
     *       插件 {@code runtime/} 子目录经构建链 copy-plugin-runtime 并入程序根 runtime/）；</li>
     *   <li>配置 {@code worker.sandbox.wsl.tarball}（相对系统目录解析，兼容手动场景）。</li>
     * </ol>
     * 均不存在返回 null（= 自动导入关闭）。
     */
    public static Path tarballFor(WorkerConfig props, Path pluginDir) {
        if (pluginDir != null) {
            Path bundled = pluginDir.resolve("wsl").resolve("eagent-rootfs.tar.gz");
            if (Files.isRegularFile(bundled)) {
                return bundled;
            }
        }
        if (props != null) {
            // 打包 desktop 态:插件资源不在 staging 插件目录,而在共享 runtime(与 rg 同根)
            Path shared = props.resolveRuntimeDir().resolve("wsl").resolve("eagent-rootfs.tar.gz");
            if (Files.isRegularFile(shared)) {
                return shared;
            }
        }
        String t = props == null ? null : props.sandbox().wsl().tarball();
        if (t == null || t.isBlank()) {
            return null;
        }
        Path p = Path.of(t.trim());
        Path abs = p.isAbsolute() ? p : props.resolveHomeDir().resolve(p);
        return Files.isRegularFile(abs) ? abs : null;
    }

    /**
     * 运行期目标发行版：显式配置 > (镜像在位 ? 托管 {@link #MANAGED_DISTRO} : WSL 默认)。
     */
    public static String effectiveDistro(WorkerConfig props, Path pluginDir) {
        String d = props.sandbox().wsl().distro();
        if (d != null && !d.isBlank()) {
            return d.trim();
        }
        return tarballFor(props, pluginDir) != null ? MANAGED_DISTRO : "";
    }

    /** 去 UTF-16LE 空字节（旧版 wsl.exe 不识别 WSL_UTF8 时的输出形态），供错误码匹配。 */
    public static String ascii(String s) {
        return s == null ? "" : s.replace("\0", "");
    }

    /** 错误诊断用合并视图：wsl.exe 错误可能落在 stdout 或 stderr。 */
    public static String mergeDiagnostics(String out, String err) {
        if (out == null || out.isEmpty()) {
            return err == null ? "" : err;
        }
        if (err == null || err.isEmpty()) {
            return out;
        }
        return out + "\n" + err;
    }

    /** wsl.exe 输出是否表示「发行版不存在」：新旧错误码 + 中英文文案，先剥 UTF-16 NUL。 */
    public static boolean isDistroNotFound(String rawOut) {
        String raw = ascii(rawOut);
        return raw.contains("WSL_E_DISTRO_NOT_FOUND") || raw.contains("DISTRO_NOT_FOUND")
                || raw.contains("no distribution") || raw.contains("not installed")
                || raw.contains("找不到发行版") || raw.contains("不存在")
                || raw.contains("没有已安装的分发");
    }

    /** wsl.exe 输出是否表示「服务拒绝调用方」。 */
    public static boolean isAccessDenied(String rawOut) {
        return ascii(rawOut).contains("E_ACCESSDENIED");
    }

    /**
     * eagent-run.py 定位,与 {@link #tarballFor} 同一三级链(架构 §7.10/§7.17):
     * 插件目录 {@code <pluginDir>/wsl/} → 共享 runtime {@code resolveRuntimeDir()/wsl/}
     * (打包 desktop 态)——均缺失才视为异常。
     */
    public static Path resolveRunner(WorkerConfig props, Path pluginDir) throws IOException {
        if (pluginDir != null) {
            Path target = pluginDir.resolve("wsl").resolve("eagent-run.py");
            if (Files.isRegularFile(target)) {
                return target;
            }
        }
        if (props != null) {
            Path shared = props.resolveRuntimeDir().resolve("wsl").resolve("eagent-run.py");
            if (Files.isRegularFile(shared)) {
                return shared;
            }
        }
        throw new IOException("eagent-run.py 未找到(已查插件目录 "
                + (pluginDir == null ? "(未知)" : pluginDir.resolve("wsl"))
                + " 与共享 runtime "
                + (props == null ? "(未知)" : props.resolveRuntimeDir().resolve("wsl"))
                + ";放置: <pluginDir>/wsl/eagent-run.py,或插件 runtime/wsl/ 经构建并入共享 runtime)");
    }

    /** 读 eagent-run.py 字节。 */
    public static byte[] runnerBytes(WorkerConfig props, Path pluginDir) throws IOException {
        return Files.readAllBytes(resolveRunner(props, pluginDir));
    }

    // ─────────────────────────────────────────────────────────
    // 托管镜像自动导入
    // ─────────────────────────────────────────────────────────

    /**
     * L2 自举：发行版缺失时的托管镜像自动导入。
     *
     * <p>只服务托管目标——未配置 distro 且镜像在位，或显式配置 {@code EveryAgent}；用户显式
     * 指定的其他名字不越权代装。流程：sha256 校验(fail-closed，镜像同目录
     * {@code .sha256}) → 清空安装目录残留 → {@code wsl --import EveryAgent <sandbox 根>/distro
     * <镜像> --version 2} → 由调用方重探。不可导入/失败返回可读断因(走统一回退日志)；
     * 镜像在位且发行版缺失通常意味着首次安装，一次性成本(解包 ≤ 数分钟)。
     *
     * @param reprobe 导入成功后由调用方提供的重探（通常为该后端的 probe 方法引用）
     */
    public static ProbeResult autoImport(WorkerConfig props, Path pluginDir,
            ProbeResult prior, Supplier<ProbeResult> reprobe) {
        Path tar = tarballFor(props, pluginDir);
        if (tar == null || !MANAGED_DISTRO.equals(effectiveDistro(props, pluginDir))) {
            return prior; // 镜像不在位，或用户显式指定了非托管发行版：不越权代装
        }
        log.info("[sandbox] 托管发行版缺失，自动导入：{} ← {}(sha256 校验中)",
                MANAGED_DISTRO, tar);
        String expect = parseSha256(sha256FileText(tar));
        if (expect == null || !sha256Matches(tar, expect)) {
            log.warn("[sandbox] 托管镜像 sha256 校验失败或缺 .sha256 伴生文件，放弃导入：{}", tar);
            return new ProbeResult(false, Cause.IMPORT_FAILED,
                    "sha256 校验失败：" + tar.getFileName() + ".sha256");
        }
        Path installDir = props.resolveSandboxPersistentRoot().resolve("distro");
        cleanDir(installDir); // 上次半途导入的残留：目录归 worker 所有，可安全清
        // wsl.exe --import 不会递归创建 InstallLocation 的父目录；父目录缺失时报
        // ERROR_PATH_NOT_FOUND(系统找不到指定的路径)。这里先递归建目录。
        try {
            Files.createDirectories(installDir);
        } catch (IOException e) {
            log.warn("[sandbox] 创建发行版安装目录失败：{}", installDir, e);
            return new ProbeResult(false, Cause.IMPORT_FAILED, "创建安装目录失败：" + installDir);
        }
        Capture c = runCapture(List.of("wsl.exe", "--import", MANAGED_DISTRO,
                installDir.toString(), tar.toString(), "--version", "2"), IMPORT_TIMEOUT_MS);
        if (c.rc() != 0) {
            log.warn("[sandbox] wsl --import 失败 rc={} out={}", c.rc(),
                    truncate(ascii(c.diagText()), 200));
            return new ProbeResult(false, Cause.IMPORT_FAILED,
                    truncate(ascii(c.diagText()), 200));
        }
        log.info("[sandbox] 托管发行版 {} 导入完成，重新探测", MANAGED_DISTRO);
        return reprobe.get();
    }

    private static String sha256FileText(Path tar) {
        try {
            return Files.readString(Path.of(tar + ".sha256"), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /** sha256sum 输出(<hex>  <file>)或裸 hex → 小写摘要；非法返回 null。 */
    static String parseSha256(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String hex = text.trim().split("\\s+")[0].toLowerCase(Locale.ROOT);
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
            return false; // 读失败/摘要器不可得：一律按校验不过处理
        }
    }

    /** 递归清空目录内容(自动导入的安装目录归 worker 所有)；目录不存在则空操作。 */
    private static void cleanDir(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                    .filter(p -> !p.equals(dir))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // 残留清理尽力而为：清不净时 wsl --import 自会报错
                        }
                    });
        } catch (IOException notExists) {
            // 目录不存在：无需清理
        }
    }

    // ─────────────────────────────────────────────────────────
    // 进程执行工具
    // ─────────────────────────────────────────────────────────

    /** runCapture 出口约定：-1 = 超时，-127 = 启动失败。 */
    private static final int RC_TIMEOUT = -1;
    private static final int RC_LAUNCH_FAIL = -127;

    /** 命令执行结果（内部用）。 */
    private record Capture(int rc, String out, String err) {
        String diagText() {
            return mergeDiagnostics(out, err);
        }
    }

    private static Capture runCapture(List<String> cmd, long timeoutMs) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(false);
            pb.environment().put("WSL_UTF8", "1");
            Process p = pb.start();
            StringBuilder outSb = new StringBuilder();
            StringBuilder errSb = new StringBuilder();
            Thread outReader = Thread.ofVirtual().start(() -> {
                try (InputStream in = p.getInputStream()) {
                    readInto(in, outSb);
                } catch (IOException ignored) {
                    // 进程被杀/管道断：读到多少算多少
                }
            });
            Thread errReader = Thread.ofVirtual().start(() -> {
                try (InputStream in = p.getErrorStream()) {
                    readInto(in, errSb);
                } catch (IOException ignored) {
                    // 进程被杀/管道断：读到多少算多少
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

    // ─────────────────────────────────────────────────────────
    // 共享类型
    // ─────────────────────────────────────────────────────────

    /** 命令执行结果（stdout / stderr / exitCode / aborted）。 */
    public record OsResult(String stdout, String stderr, int exitCode, boolean aborted) {
    }

    /** 探测断因枚举。 */
    public enum Cause {
        ACCESS_DENIED("WSL 服务拒绝调用方",
                "worker 须以普通 Medium-IL 用户进程运行"),
        WSL_UNAVAILABLE("wsl.exe 不可用/启动失败",
                "确认 wsl --status 可运行"),
        DISTRO_NOT_FOUND("发行版不存在",
                "worker.sandbox.wsl.distro 指向已有发行版"),
        PYTHON3_MISSING("发行版缺 python3", ""),
        BWRAP_MISSING("发行版缺 bwrap(bubblewrap)", ""),
        USERNS_FAILED("bwrap 冒烟失败(user namespace 不可用)", ""),
        TIMEOUT("探测超时(WSL 冷启动/卡死)", ""),
        IMPORT_FAILED("托管镜像自动导入失败",
                "检查 <pluginDir>/wsl/eagent-rootfs.tar.gz 与同名 .sha256 是否完好"),
        UNKNOWN("未归类失败", "复跑全量探针定位");

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

    /** probe 结果：ok=true；否则 cause + detail。 */
    public record ProbeResult(boolean ok, Cause cause, String detail) {

        /** 单行断因（日志用）。 */
        public String brief() {
            if (ok) {
                return "ok";
            }
            if (detail == null || detail.isBlank()) {
                return cause.desc();
            }
            return cause.desc() + ": " + truncate(detail.replaceAll("[\\r\\n]+", "; "), 200);
        }

        /** 对应修正动作（日志用）。 */
        public String fix() {
            return ok ? "" : cause.fix();
        }
    }

    /** wsl -l -v 输出解析结果。 */
    public record DistroList(List<String> names, String def) {
    }

    /** 字符串截断工具。 */
    public static String truncate(String s, int n) {
        return s == null ? "" : (s.length() <= n ? s : s.substring(0, n) + "...");
    }

    /** 输出截断工具。 */
    public static String capOutput(String s, int maxOut) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        return s.length() > maxOut ? s.substring(0, maxOut) + "\n[输出已截断至 " + maxOut + " 字符]" : s;
    }
}
