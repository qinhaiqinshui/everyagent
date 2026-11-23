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
import java.time.Instant;
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
 *   <li>{@code enabled} 为 true 或缺省(视为 true),<b>按根清单是否声明 Java 入口类判型</b>
 *       ({@code main} / 旧格式 {@code provides.spi.EveryAgentPlugin},见 {@link #declaresJavaEntry(Path)}),
 *       <b>不按 target/ 产物是否存在判型</b>:
 *     <ul>
 *       <li>声明了入口类 = Java 插件:须有 {@code target/classes/plugin.json}(maven 已构建)
 *           且 {@code target/} 下至少一个非 sources/javadoc 的 jar,两者都满足才纳入;
 *           否则 WARN「内置插件未构建,请先 mvn package」并跳过。</li>
 *       <li>未声明入口类 = 纯 web / 声明式插件(根 plugin.json 即清单),纳入;
 *           目录里残留的 {@code target/}(历史构建产物)不参与判型——若按产物判型,
 *           残留的 target/classes/plugin.json 会把它误判成「Java 插件未构建」整目录跳过,
 *           前端入口(如扩展管理侧栏)静默消失。</li>
 *     </ul>
 *   </li>
 *   <li>子目录根无 {@code plugin.json}:跳过。</li>
 * </ol>
 *
 * <p>根目录不存在时 WARN 日志并返回空列表,不报错(桌面安装包若见此行,
 * 多为打包时未含 every-agent-plugins,见 §9 copy-plugins.mjs)。
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
            log.warn("[plugins-builtin] 内置插件源码目录不存在,跳过扫描: {}(桌面安装包出现此行 = 打包未含 every-agent-plugins)", root);
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

            // 判型只看根清单是否声明 Java 入口类,不看 target/ 产物是否残留:
            // 纯 web 插件目录若残留历史构建产物 target/classes/plugin.json(无 jar),
            // 按产物判型会把它误判成「Java 插件未构建」整目录跳过,前端入口(如扩展
            // 管理侧栏)静默消失、worker 控制台只有一行 WARN——与 PluginLoader 的
            // 「无 main = 声明式插件,不需要 jar」口径对齐后不再出现这种半新半旧。
            if (declaresJavaEntry(rootManifest)) {
                // Java 插件:须有 mvn package 产物(target/classes/plugin.json + 可加载 jar)
                if (hasTargetClassesPluginJson(dir) && hasTargetJars(dir)) {
                    warnIfJarStale(dir);
                    result.add(new ScannedPlugin(dir, "builtin"));
                } else {
                    log.warn("[plugins-builtin] 内置插件未构建,请先 mvn package: {}", dir.getFileName());
                }
            } else {
                // 纯 web / 声明式插件:根目录 plugin.json(已确认存在且 enabled)即清单
                result.add(new ScannedPlugin(dir, "builtin"));
            }
        }

        log.info("[plugins-builtin] 扫描到 {} 个内置插件 (共 {} 个子目录)", result.size(), subDirs.size());
        return result;
    }

    // ---- 工具方法(供后续 PluginLoader 复用) ----

    /**
     * 检查 plugin.json 是否声明 Java 入口类:顶层 {@code main},兼容旧格式
     * {@code provides.spi.EveryAgentPlugin}。
     *
     * <p>与 {@code PluginLoader.loadPlugin} 判定「声明式插件」的口径互为反面:声明了入口类
     * 的插件才需要 jar 等构建产物,未声明的(纯 web / 声明式)一个 jar 都不加载,
     * 故 {@code target/} 的有无对后者毫无意义。解析失败视为未声明({@link #isEnabled}
     * 已 WARN;加载器用同一份清单同样解析不出 main)。
     *
     * @param manifestPath plugin.json 路径
     * @return true = 声明了 Java 入口类(java 插件);false = 纯 web / 声明式插件
     */
    public static boolean declaresJavaEntry(Path manifestPath) {
        try {
            JsonNode json = Json.parse(Files.readString(manifestPath));
            if (!json.path("main").asString("").isEmpty()) {
                return true;
            }
            return !json.path("provides").path("spi").path("EveryAgentPlugin").asString("").isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

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
     * jar 陈旧检测:target/classes 内最新文件比 target/ 下最新 jar 还新,说明
     * 增量编译(如 IDEA 构建)后未重新 mvn package——本扫描器加载的是 jar 而非
     * classes,worker 会把旧代码装进运行时,「改了源码不生效」且无任何征兆
     * (实测事故:沙箱 https 重构全部落地后依旧失败,根因即加载了旧 jar)。
     * 仅提示不阻断——时间戳口径可能误报(如 package 后又跑了一次 IDEA 全量
     * 构建),由 WARN 文案引导;无法判定(无 jar / IO 失败)时返回 false。
     *
     * @param pluginDir 插件根目录
     * @return true = classes 比 jar 新,疑似陈旧
     */
    static boolean isJarStale(Path pluginDir) {
        List<Path> jars = findTargetJars(pluginDir);
        if (jars.isEmpty()) {
            return false;
        }
        Instant newestJar = jars.stream()
                .map(BuiltInPluginScanner::mtimeOf)
                .filter(java.util.Objects::nonNull)
                .max(java.util.Comparator.naturalOrder())
                .orElse(null);
        Instant newestClass = newestMtimeUnder(pluginDir.resolve("target/classes"));
        return newestJar != null && newestClass != null && newestClass.isAfter(newestJar);
    }

    /** {@link #isJarStale} 为真时打 WARN(不阻断加载)。 */
    private static void warnIfJarStale(Path pluginDir) {
        if (isJarStale(pluginDir)) {
            log.warn("[plugins-builtin] 插件 jar 比 target/classes 陈旧(增量编译后未重新打包?),"
                    + "worker 将加载旧 jar、源码改动不会生效,请重新 mvn package: {}",
                    pluginDir.getFileName());
        }
    }

    /** 文件 mtime;IO 失败返回 null(无法判定按不陈旧处理)。 */
    private static Instant mtimeOf(Path file) {
        try {
            return Files.getLastModifiedTime(file).toInstant();
        } catch (IOException e) {
            return null;
        }
    }

    /** 目录树内最新文件的 mtime;目录不存在/为空/IO 失败返回 null。 */
    private static Instant newestMtimeUnder(Path dir) {
        if (!Files.isDirectory(dir)) {
            return null;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(Files::isRegularFile)
                    .map(BuiltInPluginScanner::mtimeOf)
                    .filter(java.util.Objects::nonNull)
                    .max(java.util.Comparator.naturalOrder())
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
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
     * 解析插件的 manifest(plugin.json)路径。
     *
     * <p>仅 Java 插件(根清单声明入口类,或根清单缺失只有构建副本)优先返回
     * {@code target/classes/plugin.json}(mvn package 的复制件),否则返回根 {@code plugin.json}
     * (纯 web / 声明式插件以根清单为唯一真相——目录里残留的 {@code target/classes/plugin.json}
     * 是历史构建产物,不得遮蔽根清单的 webMain 等字段);两者都不存在返回 {@code null}。
     *
     * @param pluginDir 插件根目录
     * @return manifest 路径,或 null
     */
    public static Path resolveManifestPath(Path pluginDir) {
        Path rootManifest = pluginDir.resolve("plugin.json");
        Path targetManifest = pluginDir.resolve("target/classes/plugin.json");
        if (Files.isRegularFile(targetManifest)
                && (!Files.isRegularFile(rootManifest) || declaresJavaEntry(rootManifest))) {
            return targetManifest;
        }
        if (Files.isRegularFile(rootManifest)) {
            return rootManifest;
        }
        return Files.isRegularFile(targetManifest) ? targetManifest : null;
    }
}
