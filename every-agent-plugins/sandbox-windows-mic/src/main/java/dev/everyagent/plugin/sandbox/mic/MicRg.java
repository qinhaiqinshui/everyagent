package dev.everyagent.plugin.sandbox.mic;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * mic 插件自带 rg 二进制的解析（Shell 工具收敛 §3.0：rg 归属下放各沙箱插件）。
 *
 * <p>解析规则：
 * <ol>
 *   <li>{@code <pluginDir>/bin/rg.exe} 存在且可读 → 返回该路径
 *       （其所在目录会前置进子进程 PATH）；</li>
 *   <li>不存在 → 检查系统 PATH 是否已能找到 rg.exe（{@code where rg} 语义，
 *       遍历 {@code Path}/{@code PATH} 各目录）→ 找到则无需注入，返回 null；</li>
 *   <li>都找不到 → 返回 null 并 log warn（rg 不可用，不阻断）。</li>
 * </ol>
 */
final class MicRg {

    private static final System.Logger LOG = System.getLogger(MicRg.class.getName());

    private MicRg() {
    }

    /**
     * 解析插件自带 rg 路径（需注入子进程 PATH 时返回非 null）。
     *
     * @param pluginDir 插件根目录（{@code WorkerPluginContext.pluginDir()}），可为 null
     * @return 需注入 PATH 的 rg 路径；系统 PATH 已含或插件未自带时返回 null
     */
    static Path resolve(Path pluginDir) {
        Path bundled = pluginDir == null ? null : pluginDir.resolve("bin").resolve("rg.exe");
        if (bundled != null && Files.isReadable(bundled)) {
            return bundled;
        }
        if (findOnPath() != null) {
            return null; // 系统 PATH 已含 rg，无需注入
        }
        LOG.log(System.Logger.Level.WARNING,
                "[mic] rg 二进制不可用（{0} 不存在且系统 PATH 未找到 rg.exe），"
                        + "rg 命令将不可用，不阻断启动",
                bundled);
        return null;
    }

    /** 遍历系统 PATH（Path/PATH 各目录）定位 rg.exe；找不到返回 null。 */
    private static Path findOnPath() {
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
            Path candidate = Path.of(dir).resolve("rg.exe");
            if (Files.isReadable(candidate)) {
                return candidate;
            }
        }
        return null;
    }
}
