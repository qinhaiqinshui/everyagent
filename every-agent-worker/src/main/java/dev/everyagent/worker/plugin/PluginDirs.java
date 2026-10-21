package dev.everyagent.worker.plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.stream.Stream;

/**
 * 外部插件目录的卸载墓碑与递归删除工具(卸载墓碑与启动自愈,架构 §8.5)。
 *
 * <p>墓碑文件 {@link #UNINSTALL_TOMBSTONE} 是「该插件目录已决定卸载」的落盘事实:
 * {@code plugin.uninstall} 删除目录<b>前</b>先写墓碑——删除成功墓碑随目录一起消失;
 * 删除失败(Windows 典型:进程把 CWD 停在插件目录或持有句柄,文件可删、目录删不掉)时
 * 墓碑留在目录内,{@code ExternalPluginScanner} 下次 worker 启动扫描时见墓碑即递归删除
 * 自愈(仍被占用则 WARN 跳过、绝不加载该目录),保证卸载意图跨重启最终一致。
 *
 * <p>本类只做无状态文件操作,供 loader({@code PluginRpcMethods})与
 * scanner({@code ExternalPluginScanner})两侧共用,避免墓碑文件名与删除语义两处漂移。
 */
public final class PluginDirs {

    /** 卸载墓碑文件名(插件目录内;存在 = 该目录已决定卸载,扫描器不得加载)。 */
    public static final String UNINSTALL_TOMBSTONE = ".uninstall-pending";

    private PluginDirs() {
    }

    /** 目录内是否存在卸载墓碑。 */
    public static boolean hasUninstallTombstone(Path pluginDir) {
        return Files.isRegularFile(pluginDir.resolve(UNINSTALL_TOMBSTONE));
    }

    /** 写卸载墓碑(内容 = 一行卸载时间戳);写入失败由调用方决定是否阻断(墓碑是尽力而为的兜底)。 */
    public static void writeUninstallTombstone(Path pluginDir) throws IOException {
        Files.writeString(pluginDir.resolve(UNINSTALL_TOMBSTONE),
                "uninstall-pending since " + Instant.now() + System.lineSeparator());
    }

    /** 递归删除(先子后父,目录与文件同构处理;文件不存在静默通过,句柄占用照常抛 IOException)。 */
    public static void deleteRecursive(Path dir) throws IOException {
        if (Files.isDirectory(dir)) {
            try (Stream<Path> stream = Files.list(dir)) {
                for (Path child : stream.toList()) {
                    deleteRecursive(child);
                }
            }
        }
        Files.deleteIfExists(dir);
    }
}
