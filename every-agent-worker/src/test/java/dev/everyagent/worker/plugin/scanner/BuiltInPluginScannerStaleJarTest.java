package dev.everyagent.worker.plugin.scanner;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.plugin.scanner.PluginScanner.ScannedPlugin;

/**
 * builtin 插件 jar 陈旧检测(实测事故回归:沙箱 https 重构落地后依旧失败,
 * 根因是 IDEA 增量编译只更新 target/classes、加载器只认 target/*.jar,
 * 未重新 package 时 worker 把旧 jar 装进运行时且无任何提示)。
 *
 * <ul>
 *   <li>classes 比 jar 新 → isJarStale=true 且 WARN 不阻断(插件仍被纳入);</li>
 *   <li>jar 比 classes 新(正常 mvn package 后)→ 不告警;</li>
 *   <li>无 jar(未构建)→ 不按陈旧误报(走既有「未构建」分支)。</li>
 * </ul>
 */
class BuiltInPluginScannerStaleJarTest {

    @TempDir
    Path pluginsRoot;

    /** 造一个带 target/classes/plugin.json 的 Java 插件骨架,返回插件根目录。 */
    private Path javaPlugin(String name) throws IOException {
        Path dir = pluginsRoot.resolve(name);
        Files.createDirectories(dir.resolve("target/classes"));
        Files.writeString(dir.resolve("plugin.json"), "{\"id\":\"" + name + "\",\"name\":\"" + name + "\"}");
        Files.writeString(dir.resolve("target/classes/plugin.json"),
                "{\"id\":\"" + name + "\",\"name\":\"" + name + "\"}");
        return dir;
    }

    private static void setMtime(Path file, Instant t) throws IOException {
        Files.setLastModifiedTime(file, FileTime.from(t));
    }

    @Test
    void staleWhenClassesNewerThanJar() throws IOException {
        Path dir = javaPlugin("p-stale");
        Path jar = dir.resolve("target/p-stale-0.1.0.jar");
        Files.write(jar, new byte[0]);
        Instant base = Instant.now().minus(Duration.ofHours(1));
        setMtime(jar, base);
        setMtime(dir.resolve("target/classes/plugin.json"), base.plusSeconds(60));

        assertTrue(BuiltInPluginScanner.isJarStale(dir));
    }

    @Test
    void freshWhenJarNewest() throws IOException {
        Path dir = javaPlugin("p-fresh");
        Path jar = dir.resolve("target/p-fresh-0.1.0.jar");
        Files.write(jar, new byte[0]);
        Instant base = Instant.now().minus(Duration.ofHours(1));
        setMtime(dir.resolve("target/classes/plugin.json"), base);
        setMtime(jar, base.plusSeconds(60));

        assertFalse(BuiltInPluginScanner.isJarStale(dir));
    }

    @Test
    void notStaleWhenNoJar() throws IOException {
        Path dir = javaPlugin("p-unbuilt");

        assertFalse(BuiltInPluginScanner.isJarStale(dir));
    }

    @Test
    void scanStillIncludesPluginWhenJarStale() throws IOException {
        Path dir = javaPlugin("p-loaded");
        Path jar = dir.resolve("target/p-loaded-0.1.0.jar");
        Files.write(jar, new byte[0]);
        Instant base = Instant.now().minus(Duration.ofHours(1));
        setMtime(jar, base);
        setMtime(dir.resolve("target/classes/plugin.json"), base.plusSeconds(60));

        WorkerProperties props = mock(WorkerProperties.class);
        when(props.resolveBuiltinPluginsDir()).thenReturn(pluginsRoot);
        List<ScannedPlugin> scanned = new BuiltInPluginScanner(props).scan();

        assertTrue(scanned.stream().anyMatch(p -> p.pluginDir().getFileName().toString().equals("p-loaded")),
                "陈旧只 WARN 不阻断,插件仍须被纳入加载");
    }
}
