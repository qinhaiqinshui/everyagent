package dev.everyagent.worker.plugin.scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.plugin.PluginDirs;
import dev.everyagent.worker.plugin.scanner.PluginScanner.ScannedPlugin;

/**
 * 卸载墓碑的启动自愈(§8.5,卸载墓碑回归):
 *
 * <ul>
 *   <li>墓碑目录 → 启动扫描时递归删除,不再出现在扫描结果(已决定卸载的插件不得复活);</li>
 *   <li>墓碑目录仍被句柄占用删不掉 → 整目录跳过,同样不加载;</li>
 *   <li>无墓碑的普通插件目录 → 扫描行为与既有完全一致(零回归)。</li>
 * </ul>
 */
class ExternalPluginScannerTombstoneTest {

    private static WorkerProperties props(Path pluginsDir) {
        WorkerProperties props = mock(WorkerProperties.class);
        when(props.resolvePluginsDir()).thenReturn(pluginsDir);
        return props;
    }

    /** 造一个最小可加载的外部插件目录(根含 plugin.json)。 */
    private static Path writePlugin(Path pluginsDir, String id) throws IOException {
        Path dir = pluginsDir.resolve(id);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("plugin.json"), "{\"id\":\"" + id + "\"}");
        return dir;
    }

    @Test
    void tombstonedDirIsCleanedUpAndNotScanned(@TempDir Path tmp) throws IOException {
        Path pluginsDir = tmp.resolve("plugins");
        Path dead = writePlugin(pluginsDir, "dead-plugin");
        PluginDirs.writeUninstallTombstone(dead);

        List<ScannedPlugin> scanned = new ExternalPluginScanner(props(pluginsDir)).scan();

        assertTrue(scanned.isEmpty(), "墓碑目录不得进扫描结果");
        assertFalse(Files.exists(dead), "墓碑目录应被启动自愈递归删除");
    }

    @Test
    void stillLockedTombstoneDirIsSkippedNotLoaded(@TempDir Path tmp) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                System.getProperty("os.name").toLowerCase().contains("win"),
                "CWD 锁目录是 Windows 行为,仅 Windows 上验证");

        Path pluginsDir = tmp.resolve("plugins");
        Path locked = writePlugin(pluginsDir, "locked-plugin");
        PluginDirs.writeUninstallTombstone(locked);

        // 复现真实场景:子进程把 CWD 停在插件目录 →
        // 文件可删、目录删不掉。Java NIO 句柄带 FILE_SHARE_DELETE 锁不住,须用真进程。
        Process holder = new ProcessBuilder("ping", "-n", "30", "127.0.0.1")
                .directory(locked.toFile())
                .start();
        // 就绪屏障:start() 返回时子进程的 CWD 句柄可能尚未建立(CreateProcess 与子进程
        // 初始化之间有窗口,抢先删除会假成功)。读到首行输出 = 进程已完成初始化、锁已生效。
        assertTrue(holder.getInputStream().read() >= 0, "占用进程应已就绪(输出首字节)");
        try {
            List<ScannedPlugin> scanned = new ExternalPluginScanner(props(pluginsDir)).scan();

            assertTrue(scanned.isEmpty(), "仍被占用的墓碑目录必须跳过,不得加载已卸载插件");
            assertTrue(Files.isDirectory(locked), "删不掉时目录保留(下次启动再试),不得误报成功");
            // 删除尝试会连带删掉旧墓碑 → 扫描器必须重写,自愈链不断(§8.5)
            assertTrue(PluginDirs.hasUninstallTombstone(locked), "删除失败后墓碑应被重写");
        } finally {
            holder.destroyForcibly();
            assertTrue(holder.waitFor(10, java.util.concurrent.TimeUnit.SECONDS), "占用进程应可终止");
        }

        // 占用解除后再扫描 → 自愈清理完成
        List<ScannedPlugin> healed = new ExternalPluginScanner(props(pluginsDir)).scan();
        assertTrue(healed.isEmpty());
        assertFalse(Files.exists(locked), "占用解除后启动扫描应完成自愈清理");
    }

    @Test
    void normalPluginStillScanned(@TempDir Path tmp) throws IOException {
        Path pluginsDir = tmp.resolve("plugins");
        writePlugin(pluginsDir, "alive-plugin");

        List<ScannedPlugin> scanned = new ExternalPluginScanner(props(pluginsDir)).scan();

        assertEquals(1, scanned.size(), "普通插件目录扫描行为不变(零回归)");
        assertEquals("alive-plugin", scanned.get(0).pluginDir().getFileName().toString());
    }
}
