package dev.everyagent.worker.plugin.scanner;

import dev.everyagent.contract.json.Json;
import dev.everyagent.worker.config.WorkerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

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
 *   <li>若子目录根存在 {@code plugin.json}:解析并检查 {@code enabled} 字段,
 *       {@code enabled=false} 的插件直接跳过(不依赖 target/ 是否存在,
 *       避免已构建但未清理的 target/ 残留导致禁用插件被误加载)。</li>
 *   <li>{@code enabled} 为 true 或缺省(视为 true):
 *     <ul>
 *       <li>若存在 {@code target/classes/plugin.json}(Java 插件,maven 已构建):
 *           还要求 {@code target/} 下至少有一个非 sources/javadoc 的 jar,
 *           两者都满足才纳入;否则 WARN 提示「内置插件未构建,请先 mvn package」并跳过。</li>
 *       <li>否则(无 target/classes/plugin.json):视为纯 web 插件(根 plugin.json 即清单),纳入。</li>
 *     </ul>
 *   </li>
 *   <li>子目录根无 {@code plugin.json}:跳过。</li>
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
            // 优先检查根目录 plugin.json 的 enabled 字段:
            // enabled=false 的插件直接跳过——不依赖 target/ 是否存在,
            // 避免已构建但未清理的 target/ 残留导致禁用插件被误加载。
            Path rootManifest = dir.resolve("plugin.json");
            if (Files.isRegularFile(rootManifest)) {
                if (!isEnabled(rootManifest)) {
                    log.info("[plugins-builtin] 插件已禁用(enabled=false),跳过: {}", dir.getFileName());
                    continue;
                }
            } else {
                // 无 plugin.json 的目录不是插件,跳过
                continue;
            }

            if (hasTargetClassesPluginJson(dir)) {
                // Java 插件:target/classes/plugin.json 存在
                if (hasTargetJars(dir)) {
                    result.add(new ScannedPlugin(dir, "builtin"));
                } else {
                    log.warn("[plugins-builtin] 内置插件未构建,请先 mvn package: {}", dir.getFileName());
                }
            } else {
                // 纯 web 插件:根目录 plugin.json(已确认存在且 enabled)
                result.add(new ScannedPlugin(dir, "builtin"));
            }
        }

        log.info("[plugins-builtin] 扫描到 {} 个内置插件 (共 {} 个子目录)", result.size(), subDirs.size());
        return result;
    }

    // ---- 工具方法(供后续 PluginLoader 复用) ----

    /**
     * 检查 plugin.json 中的 {@code enabled} 字段。
     *
     * <p>缺省(无该字段)视为 enabled=true。解析失败也视为 true(宽容,不因格式错误阻止加载)。
     *
     * @param manifestPath plugin.json 路径
     * @return true = 已启用或缺省;false = 显式 enabled=false
     */
    public static boolean isEnabled(Path manifestPath) {
        try {
            JsonNode json = Json.parse(Files.readString(manifestPath));
            // asBoolean(true): 字段缺省时返回 true
            return json.path("enabled").asBoolean(true);
        } catch (Exception e) {
            log.warn("[plugins-builtin] 解析 plugin.json 失败,视为已启用: {}", manifestPath, e);
            return true;
        }
    }

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
