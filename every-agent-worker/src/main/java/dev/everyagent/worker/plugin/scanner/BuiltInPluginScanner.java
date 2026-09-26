package dev.everyagent.worker.plugin.scanner;

import dev.everyagent.worker.config.WorkerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 内置插件扫描器 —— 扫描内置插件源码根目录下的所有一级子目录。
 *
 * <p>内置插件源码根目录默认为工作目录下 {@code every-agent-plugins/},
 * 可通过 {@code worker.builtin-plugins-dir} 配置覆盖(支持 ~ 开头)。
 *
 * <p>扫描规则(每个一级子目录,按名称排序保证确定性):
 * <ol>
 *   <li>若存在 {@code target/classes/plugin.json}(Java 插件,maven 已构建):
 *       还要求 {@code target/} 下至少有一个非 sources/javadoc 的 jar,
 *       两者都满足才纳入;否则 WARN 提示「内置插件未构建,请先 mvn package」并跳过。</li>
 *   <li>否则若子目录根存在 {@code plugin.json}(纯 web 插件):纳入。</li>
 *   <li>否则跳过。</li>
 * </ol>
 *
 * <p>根目录不存在时 INFO 日志并返回空列表,不报错。
 *
 * <p>本类还提供若干 {@code public static} 工具方法,供后续步骤(PluginLoader 统一加载)复用:
 * {@link #findTargetJars(Path)}、{@link #resolveManifestPath(Path)} 等。
 */
@Component
public class BuiltInPluginScanner implements PluginScanner {

    private static final Logger log = LoggerFactory.getLogger(BuiltInPluginScanner.class);

    private final WorkerProperties props;

    public BuiltInPluginScanner(WorkerProperties props) {
        this.props = props;
    }

    @Override
    public List<ScannedPlugin> scan() {
        Path root = props.resolveBuiltinPluginsDir();
        if (!Files.isDirectory(root)) {
            log.info("[plugins-builtin] 内置插件源码目录不存在,跳过扫描: {}", root);
            return List.of();
        }

        List<Path> subDirs;
        try (Stream<Path> dirs = Files.list(root)) {
            subDirs = dirs.filter(Files::isDirectory)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            log.warn("[plugins-builtin] 扫描内置插件目录失败: {}", root, e);
            return List.of();
        }

        List<ScannedPlugin> result = new ArrayList<>();
        for (Path dir : subDirs) {
            if (hasTargetClassesPluginJson(dir)) {
                // Java 插件:target/classes/plugin.json 存在
                if (hasTargetJars(dir)) {
                    result.add(new ScannedPlugin(dir, "builtin"));
                } else {
                    log.warn("[plugins-builtin] 内置插件未构建,请先 mvn package: {}", dir.getFileName());
                }
            } else if (hasRootPluginJson(dir)) {
                // 纯 web 插件:根目录 plugin.json
                result.add(new ScannedPlugin(dir, "builtin"));
            }
            // else: 无 plugin.json,跳过
        }

        log.info("[plugins-builtin] 扫描到 {} 个内置插件 (共 {} 个子目录)", result.size(), subDirs.size());
        return result;
    }

    // ---- 工具方法(供后续 PluginLoader 复用) ----

    /**
     * 查找 {@code target/} 下的 jar 文件列表(排除 *-sources.jar 和 *-javadoc.jar)。
     *
     * @param pluginDir 插件根目录
     * @return jar 路径列表(已排序);无 jar 或 target/ 不存在返回空列表
     */
    public static List<Path> findTargetJars(Path pluginDir) {
        Path targetDir = pluginDir.resolve("target");
        if (!Files.isDirectory(targetDir)) {
            return List.of();
        }
        try (Stream<Path> jars = Files.list(targetDir)) {
            return jars
                    .filter(p -> p.toString().endsWith(".jar"))
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return !name.endsWith("-sources.jar") && !name.endsWith("-javadoc.jar");
                    })
                    .sorted()
                    .toList();
        } catch (IOException e) {
            log.warn("[plugins-builtin] 读取 target 目录失败: {}", targetDir, e);
            return List.of();
        }
    }

    /**
     * {@code target/} 下是否有至少一个非 sources/javadoc 的 jar。
     *
     * @param pluginDir 插件根目录
     * @return true = 有可用 jar
     */
    public static boolean hasTargetJars(Path pluginDir) {
        return !findTargetJars(pluginDir).isEmpty();
    }

    /**
     * {@code target/classes/plugin.json} 是否存在(Java 插件构建产物)。
     *
     * @param pluginDir 插件根目录
     * @return true = 存在
     */
    public static boolean hasTargetClassesPluginJson(Path pluginDir) {
        return Files.isRegularFile(pluginDir.resolve("target/classes/plugin.json"));
    }

    /**
     * 子目录根 {@code plugin.json} 是否存在(纯 web 插件清单)。
     *
     * @param pluginDir 插件根目录
     * @return true = 存在
     */
    public static boolean hasRootPluginJson(Path pluginDir) {
        return Files.isRegularFile(pluginDir.resolve("plugin.json"));
    }

    /**
     * 解析插件的 manifest(plugin.json)路径:
     * 优先返回 {@code target/classes/plugin.json}(Java 插件构建产物),
     * 否则返回子目录根的 {@code plugin.json}(纯 web 插件),
     * 两者都不存在返回 {@code null}。
     *
     * @param pluginDir 插件根目录
     * @return manifest 路径,或 null
     */
    public static Path resolveManifestPath(Path pluginDir) {
        Path targetManifest = pluginDir.resolve("target/classes/plugin.json");
        if (Files.isRegularFile(targetManifest)) {
            return targetManifest;
        }
        Path rootManifest = pluginDir.resolve("plugin.json");
        if (Files.isRegularFile(rootManifest)) {
            return rootManifest;
        }
        return null;
    }
}
