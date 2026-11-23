package dev.everyagent.worker.plugin.scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.plugin.scanner.PluginScanner.ScannedPlugin;

/**
 * 内置扫描器 java / 纯 web 判型回归(实测事故:纯 web 插件目录残留历史构建产物
 * {@code target/classes/plugin.json}(无 jar)时,按产物判型会把它当成「Java 插件未构建」
 * 整目录跳过——plugin-manager 因此从前端消失,扩展管理侧栏入口不见、其余入口正常,
 * worker 控制台只有一行 WARN)。
 *
 * <ul>
 *   <li>判型只看根清单是否声明 Java 入口类(main / 旧格式 provides.spi.EveryAgentPlugin);</li>
 *   <li>纯 web 插件:无论 target/ 是否残留,一律纳入;加载期清单读根清单,残留副本不遮蔽;</li>
 *   <li>Java 插件:target/classes/plugin.json 与可加载 jar 缺一即「未构建」跳过
 *       (含从没构建过、连 target/ 都没有的情况——以前会被静默当成声明式插件加载)。</li>
 * </ul>
 */
class BuiltInPluginScannerWebPluginResidueTest {

    @TempDir
    Path pluginsRoot;

    private void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    /** 纯 web 插件骨架:根清单有 webMain、无 main;可选在 target/classes 放一份残留副本。 */
    private Path webPlugin(String name, boolean staleTargetCopy) throws IOException {
        Path dir = pluginsRoot.resolve(name);
        String manifest = "{\"id\":\"" + name + "\",\"name\":\"" + name + "\",\"webMain\":\"web/index.ts\"}";
        write(dir.resolve("plugin.json"), manifest);
        if (staleTargetCopy) {
            write(dir.resolve("target/classes/plugin.json"), manifest);
        }
        return dir;
    }

    /** Java 插件骨架:根清单声明 main;按参数决定是否带构建产物。 */
    private Path javaPlugin(String name, boolean targetManifest, boolean jar) throws IOException {
        Path dir = pluginsRoot.resolve(name);
        String manifest = "{\"id\":\"" + name + "\",\"name\":\"" + name + "\",\"main\":\"x.Entry\"}";
        write(dir.resolve("plugin.json"), manifest);
        if (targetManifest) {
            write(dir.resolve("target/classes/plugin.json"), manifest);
        }
        if (jar) {
            write(dir.resolve("target/" + name + "-0.1.0.jar"), "jar");
        }
        return dir;
    }

    private List<ScannedPlugin> scan() {
        WorkerProperties props = mock(WorkerProperties.class);
        when(props.resolveBuiltinPluginsDir()).thenReturn(pluginsRoot);
        return new BuiltInPluginScanner(props).scan();
    }

    private static boolean includes(List<ScannedPlugin> scanned, Path dir) {
        return scanned.stream().anyMatch(p -> p.pluginDir().equals(dir));
    }

    @Test
    void pureWebPluginWithStaleTargetCopyIsStillScanned() throws IOException {
        Path stale = webPlugin("plugin-manager", true);
        Path clean = webPlugin("pdf-viewer", false);

        List<ScannedPlugin> scanned = scan();

        assertTrue(includes(scanned, stale),
                "纯 web 插件目录残留 target/classes/plugin.json(无 jar)不得被判为「Java 插件未构建」跳过");
        assertTrue(includes(scanned, clean));
        assertEquals(2, scanned.size());
    }

    @Test
    void javaPluginNeedsBothTargetManifestAndJar() throws IOException {
        Path built = javaPlugin("built", true, true);
        Path noJar = javaPlugin("no-jar", true, false);
        Path noTarget = javaPlugin("no-target", false, false);
        Path jarOnly = javaPlugin("jar-only", false, true);

        List<ScannedPlugin> scanned = scan();

        assertTrue(includes(scanned, built));
        assertFalse(includes(scanned, noJar), "有 target/classes/plugin.json 无 jar → 未构建跳过(既有行为)");
        assertFalse(includes(scanned, noTarget), "声明了 main 却没构建过 → 未构建跳过,不再静默当成声明式插件");
        assertFalse(includes(scanned, jarOnly), "有 jar 无 target/classes/plugin.json → 未构建跳过(既有行为)");
    }

    @Test
    void legacyProvidesSpiCountsAsJavaEntry() throws IOException {
        Path dir = pluginsRoot.resolve("legacy");
        write(dir.resolve("plugin.json"),
                "{\"id\":\"legacy\",\"provides\":{\"spi\":{\"EveryAgentPlugin\":\"x.Entry\"}}}");
        write(dir.resolve("target/classes/plugin.json"), "{}");

        assertFalse(includes(scan(), dir), "旧格式入口声明同样按 Java 插件判型:无 jar → 未构建跳过");
    }

    @Test
    void resolveManifestPathIgnoresStaleTargetCopyForPureWebPlugin() throws IOException {
        Path dir = pluginsRoot.resolve("pure-web");
        write(dir.resolve("plugin.json"),
                "{\"id\":\"pure-web\",\"webMain\":\"web/index.ts\",\"name\":\"新名字\"}");
        // 残留副本是旧清单(无 webMain),不得遮蔽根清单
        write(dir.resolve("target/classes/plugin.json"), "{\"id\":\"pure-web\",\"name\":\"旧名字\"}");

        assertEquals(dir.resolve("plugin.json"), BuiltInPluginScanner.resolveManifestPath(dir));
    }

    @Test
    void resolveManifestPathStillPrefersTargetCopyForJavaPlugin() throws IOException {
        Path dir = javaPlugin("java", true, true);

        assertEquals(dir.resolve("target/classes/plugin.json"), BuiltInPluginScanner.resolveManifestPath(dir));
    }

    @Test
    void resolveManifestPathFallbacks() throws IOException {
        Path none = pluginsRoot.resolve("none");
        Files.createDirectories(none);
        assertNull(BuiltInPluginScanner.resolveManifestPath(none));

        Path targetOnly = pluginsRoot.resolve("target-only");
        write(targetOnly.resolve("target/classes/plugin.json"), "{}");
        assertEquals(targetOnly.resolve("target/classes/plugin.json"),
                BuiltInPluginScanner.resolveManifestPath(targetOnly), "根清单缺失时仍回落构建副本(既有行为)");
    }
}
