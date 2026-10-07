package dev.everyagent.plugin.sandbox.codex;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * codex 插件自带 rg 二进制的解析（Shell 工具收敛 §3.0：rg 归属下放各沙箱插件）。
 *
 * <p>解析规则（三档，架构 §7.10「程序附属文件」口径）：
 * <ol>
 *   <li>{@code <pluginDir>/bin/rg.exe} 存在且可读 → 返回该路径（其所在目录会前置进子进程 PATH）；</li>
 *   <li>插件未自带 → 回退程序根 {@code <runtimeDir>/bin/rg.exe}（= {@code WorkerConfig
 *       .resolveRuntimeDir()}，与核心 {@code RipgrepBinary} 完全同一位置）。<b>这一档是
 *       desktop 打包态的实际命中位</b>：{@code copy-plugins.mjs} 若漏搬插件 {@code bin/}，
 *       少了本档就会让安装包里 {@code powershell} 工具彻底没有 rg；</li>
 *   <li>前两档都无 → 检查系统 PATH 是否已能找到 rg（{@code where rg} 语义，遍历
 *       {@code Path}/{@code PATH} 各目录）→ 可用但无需注入；</li>
 *   <li>全都没有 → 判不可用并 log warn（不阻断启动），由调用方据此<b>不再在工具描述里
 *       宣称 rg 可用</b>（见 {@link CodexBashToolProvider}）。</li>
 * </ol>
 */
final class CodexRg {

    /**
     * rg 解析结果。
     *
     * @param injectPath 需前置进子进程 PATH 的 rg 路径；系统 PATH 已含时为 null
     * @param available  rg 在子进程里是否真的可用（{@code injectPath != null} 或系统 PATH 已含）
     */
    record Rg(Path injectPath, boolean available) {
    }

    private static final System.Logger LOG = System.getLogger(CodexRg.class.getName());

    private CodexRg() {
    }

    /**
     * 解析 rg 二进制（三档：插件根 {@code bin/} → 程序根 {@code runtime/bin/} → 系统 PATH）。
     *
     * @param pluginDir 插件根目录（{@code WorkerPluginContext.pluginDir()}），可为 null
     * @param runtimeDir 程序附属文件目录（{@code WorkerConfig.resolveRuntimeDir()}），可为 null
     * @return 解析结果（注入路径 + 可用性），恒非 null
     */
    static Rg resolve(Path pluginDir, Path runtimeDir) {
        String name = isWindows() ? "rg.exe" : "rg";
        Path bundled = pluginDir == null ? null : pluginDir.resolve("bin").resolve(name);
        if (bundled != null && Files.isReadable(bundled)) {
            return new Rg(bundled, true);
        }
        // 程序根 runtime/bin/ —— desktop 打包态的 rg 就在 <resourcesPath>/runtime/bin/rg.exe，
        // 与核心 RipgrepBinary 完全同口径（§7.10「程序附属文件」）。少了这一档，一旦
        // copy-plugins.mjs 漏搬插件 bin/，安装包里 powershell 工具就彻底没有 rg。
        Path shared = runtimeDir == null ? null : runtimeDir.resolve("bin").resolve(name);
        if (shared != null && Files.isReadable(shared)) {
            return new Rg(shared, true);
        }
        if (findOnPath(name) != null) {
            return new Rg(null, true); // 系统 PATH 已含 rg，无需注入
        }
        LOG.log(System.Logger.Level.WARNING,
                "[codex] rg 二进制不可用（插件 {0} 与程序根 {1} 均不存在，系统 PATH 也未找到 {2}），"
                        + "rg 命令将不可用，不阻断启动；工具描述会如实告知模型改用 Select-String",
                new Object[] { bundled, shared, name });
        return new Rg(null, false);
    }

    /** Windows 判定（与核心 RipgrepBinary 同口径决定二进制文件名）。 */
    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    /** 遍历系统 PATH（Path/PATH 各目录）定位指定文件名的 rg；找不到返回 null。 */
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
                return candidate;
            }
        }
        return null;
    }
}
