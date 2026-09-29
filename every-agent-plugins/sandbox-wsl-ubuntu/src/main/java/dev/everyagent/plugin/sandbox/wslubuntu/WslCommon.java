package dev.everyagent.plugin.sandbox.wslubuntu;

import dev.everyagent.worker.config.WorkerProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
 */
public final class WslCommon {

    /** 托管发行版名（wsl --import 目标；镜像在位且未配置 distro 时自动启用）。 */
    public static final String MANAGED_DISTRO = "EveryAgent";

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
     * 托管镜像路径：优先程序根 {@code <程序根>/runtime/wsl/eagent-rootfs.tar.gz}；
     * 其次配置 {@code worker.sandbox.wsl.tarball}。均不存在返回 null。
     */
    public static Path tarballFor(WorkerProperties props) {
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

    /** 运行期目标发行版：显式配置 > (镜像在位 ? 托管 {@link #MANAGED_DISTRO} : WSL 默认)。 */
    public static String effectiveDistro(WorkerProperties props) {
        String d = props.getSandbox().getWsl().getDistro();
        if (d != null && !d.isBlank()) {
            return d.trim();
        }
        return tarballFor(props) != null ? MANAGED_DISTRO : "";
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

    /** eagent-run.py 定位（程序根 {@code <程序根>/runtime/wsl/eagent-run.py}）。 */
    public static Path resolveRunner(WorkerProperties props) throws IOException {
        Path target = props.resolveRuntimeDir().resolve("wsl").resolve("eagent-run.py");
        if (!Files.isRegularFile(target)) {
            throw new IOException("程序根缺少 eagent-run.py: " + target
                    + "(放置: <程序根>/runtime/wsl/eagent-run.py;程序根 = JVM 工作目录)");
        }
        return target;
    }

    /** 读 eagent-run.py 字节。 */
    public static byte[] runnerBytes(WorkerProperties props) throws IOException {
        return Files.readAllBytes(resolveRunner(props));
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
        IMPORT_FAILED("托管镜像自动导入失败", ""),
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
