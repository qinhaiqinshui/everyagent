package dev.everyagent.plugin.ripgrepTool;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * ripgrep-tool 插件自带 rg 二进制的解析（Shell 工具收敛：rg 能力从各沙箱插件剥离至本插件）。
 *
 * <p>解析规则（三档，架构 §7.10「程序附属文件」口径，形态对照 sandbox-windows-codex 的
 * {@code CodexRg}）：
 * <ol>
 *   <li>{@code <pluginDir>/bin/rg.exe} 存在且可读 → 命中该档，{@code binary} = 该文件路径；</li>
 *   <li>插件未自带 → 回退程序根 {@code <runtimeDir>/bin/rg.exe}（= {@code WorkerConfig
 *       .resolveRuntimeDir()}，与核心 {@code RipgrepBinary} 完全同一位置）。<b>这一档是
 *       desktop 打包态的实际命中位</b>：构建链若漏搬插件 {@code bin/}，还有本档兜底；</li>
 *   <li>前两档都无 → 检查系统 PATH 是否已能找到 rg（{@code where rg} 语义，遍历
 *       {@code Path}/{@code PATH} 各目录）→ 命中时 {@code binary} = 命中的绝对路径；</li>
 *   <li>全都没有 → 判不可用并 log warn（不阻断启动），由调用方据此不再宣称 rg 可用。</li>
 * </ol>
 *
 * <p><b>与 CodexRg 的关键差异</b>：解析结果始终携带命中档 rg 二进制的绝对路径——
 * CodexRg 在系统 PATH 命中时返回 {@code Rg(null, true)} 丢弃了路径；本插件的 rg 工具
 * 需要<b>以绝对路径委托执行</b>，故系统 PATH 命中时 {@code binary} 也必须给出命中的
 * 绝对路径，仅在三档皆无时为 {@code binary=null, available=false}。
 */
public final class RgResolver {

    /**
     * rg 解析结果。
     *
     * @param binary  命中的 rg 二进制绝对路径（插件档 / 程序根档 = 该文件路径，系统 PATH 档 =
     *                PATH 上命中的绝对路径）；三档皆无时为 null
     * @param available rg 是否真的可用（恒等于 {@code binary != null}）
     */
    public record Resolved(Path binary, boolean available) {
    }

    private static final System.Logger LOG = System.getLogger(RgResolver.class.getName());

    private RgResolver() {
    }

    /**
     * 解析 rg 二进制（三档：插件根 {@code bin/} → 程序根 {@code runtime/bin/} → 系统 PATH）。
     *
     * @param pluginDir  插件根目录（{@code WorkerPluginContext.pluginDir()}），可为 null
     * @param runtimeDir 程序附属文件目录（{@code WorkerConfig.resolveRuntimeDir()}），可为 null
     * @return 解析结果（二进制绝对路径 + 可用性），恒非 null
     */
    public static Resolved resolve(Path pluginDir, Path runtimeDir) {
        String name = isWindows() ? "rg.exe" : "rg";
        Path bundled = pluginDir == null ? null : pluginDir.resolve("bin").resolve(name);
        if (bundled != null && Files.isReadable(bundled)) {
            return new Resolved(bundled, true);
        }
        // 程序根 runtime/bin/ —— desktop 打包态的 rg 就在 <resourcesPath>/runtime/bin/rg.exe，
        // 与核心 RipgrepBinary 完全同口径（§7.10「程序附属文件」）。少了这一档，一旦构建链
        // 漏搬插件 bin/，打包态就彻底没有 rg。
        Path shared = runtimeDir == null ? null : runtimeDir.resolve("bin").resolve(name);
        if (shared != null && Files.isReadable(shared)) {
            return new Resolved(shared, true);
        }
        Path onPath = findOnPath(name);
        if (onPath != null) {
            // 与 CodexRg 的关键差异：PATH 命中同样返回绝对路径，rg 工具以它委托执行，
            // 而非只声明「可用」
            return new Resolved(onPath, true);
        }
        LOG.log(System.Logger.Level.WARNING,
                "[ripgrep-tool] rg 二进制不可用（插件 {0} 与程序根 {1} 均不存在，系统 PATH 也未找到 {2}），"
                        + "rg 工具将不可用，不阻断启动",
                new Object[] { bundled, shared, name });
        return new Resolved(null, false);
    }

    /** Windows 判定（与 CodexRg / 核心 RipgrepBinary 同口径决定二进制文件名）。 */
    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    /**
     * 遍历系统 PATH（Path/PATH 各目录）定位指定文件名的 rg；命中则返回其绝对路径
     * （PATH 目录理论上可为相对路径，统一 {@code toAbsolutePath()} 收口），找不到返回 null。
     */
    private static Path findOnPath(String name) {
        String path = System.getenv("Path");
        if (path == null || path.isBlank()) {
            path = System.getenv("PATH");
        }
        if (path == null || path.isBlank()) {
            return null;
        }
        for (String dir : path.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (dir == null || dir.isBlank()) {
                continue;
            }
            Path candidate = Path.of(dir).resolve(name);
            if (Files.isReadable(candidate)) {
                return candidate.toAbsolutePath();
            }
        }
        return null;
    }
}
