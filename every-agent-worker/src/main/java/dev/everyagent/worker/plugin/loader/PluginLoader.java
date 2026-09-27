package dev.everyagent.worker.plugin.loader;

import dev.everyagent.contract.json.Json;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.plugin.api.EveryAgentPlugin;
import dev.everyagent.worker.plugin.PluginConfigImpl;
import dev.everyagent.plugin.api.WorkerPluginContext;
import dev.everyagent.worker.plugin.WorkerPluginContextImpl;
import dev.everyagent.plugin.api.WorkerServices;
import dev.everyagent.worker.plugin.registry.AdvisorProviderRegistry;
import dev.everyagent.worker.plugin.registry.AuthorizationHandlerRegistry;
import dev.everyagent.worker.plugin.registry.ChatModelEnhancerRegistry;
import dev.everyagent.worker.plugin.registry.SandboxProviderRegistry;
import dev.everyagent.worker.plugin.registry.SearchProviderRegistry;
import dev.everyagent.worker.plugin.registry.SkillContributorRegistry;
import dev.everyagent.worker.plugin.registry.ToolExecutionInterceptorRegistry;
import dev.everyagent.worker.plugin.registry.ToolProviderRegistry;
import dev.everyagent.worker.plugin.registry.TaskLifecycleRegistry;
import dev.everyagent.worker.plugin.registry.TaskAdmissionPolicyRegistry;
import dev.everyagent.worker.plugin.scanner.BuiltInPluginScanner;
import dev.everyagent.worker.plugin.scanner.PluginScanner;
import dev.everyagent.worker.plugin.scanner.PluginScanner.ScannedPlugin;
import dev.everyagent.worker.rpc.RpcDispatcher;
import dev.everyagent.worker.slash.SlashCommandRegistry;
import dev.everyagent.worker.slash.SlashTokenHandler;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 插件加载器 —— 多扫描器统一加载架构(插件扫描机制统一重构第 2 步)。
 *
 * <p>加载流程(架构 §7.2):
 * <ol>
 *   <li>遍历所有注入的 {@link PluginScanner} 实现(Spring 自动聚合 Bean),
 *       调用 {@code scan()} 收集全部 {@link ScannedPlugin};
 *       单个扫描器失败不影响其他扫描器(WARN 记录)</li>
 *   <li>收集结果按 source 排序:{@code "builtin"} 在前、外部在后,
 *       保证同名冲突时内置插件优先</li>
 *   <li>对每个 {@link ScannedPlugin} 走统一的 {@link #loadPlugin(ScannedPlugin)}:
 *       按 source 分派 manifest 路径与 jar 列表的解析方式,
 *       URLClassLoader 隔离加载 jar,反射实例化 EveryAgentPlugin,
 *       构造 WorkerPluginContext,调用 activate()</li>
 *   <li>已加载 id 去重(后扫描到的同名插件跳过 + WARN);单个插件失败不影响其他</li>
 * </ol>
 *
 * <p>source 分派策略:
 * <ul>
 *   <li>{@code "builtin"}:manifest 用 {@link BuiltInPluginScanner#resolveManifestPath(Path)}
 *       (target/classes/plugin.json 优先,否则根 plugin.json);
 *       jar 用 {@link BuiltInPluginScanner#findTargetJars(Path)}(target/ 下非 sources/javadoc 的 jar)</li>
 *   <li>{@code "external"} 及其它:pluginDir/plugin.json + pluginDir/lib/*.jar</li>
 * </ul>
 *
 * <p>入口类解析:优先顶层 {@code "main"} 字段,兼容旧格式
 * {@code provides.spi.EveryAgentPlugin};无入口类 = 纯声明式插件(只有 plugin.json 贡献)。
 *
 * <p>ClassLoader 隔离策略(架构 §7.3):
 * <ul>
 *   <li>每个插件一个 URLClassLoader(jar, parent=workerClassLoader)</li>
 *   <li>插件可见 worker 公共 API(plugin.spi.*、contract.*)</li>
 *   <li>插件之间互不可见</li>
 * </ul>
 */
@Component
public class PluginLoader {

    private static final Logger log = LoggerFactory.getLogger(PluginLoader.class);

    private final WorkerProperties props;
    private final List<PluginScanner> pluginScanners;
    private final AdvisorProviderRegistry advisorRegistry;
    private final ToolProviderRegistry toolRegistry;
    private final SandboxProviderRegistry sandboxRegistry;
    private final SearchProviderRegistry searchRegistry;
    private final AuthorizationHandlerRegistry authHandlerRegistry;
    private final ToolExecutionInterceptorRegistry toolInterceptorRegistry;
    private final TaskLifecycleRegistry lifecycleRegistry;
    private final ChatModelEnhancerRegistry chatModelEnhancerRegistry;
    private final TaskAdmissionPolicyRegistry admissionPolicyRegistry;
    private final SkillContributorRegistry skillContributorRegistry;
    private final RpcDispatcher rpcDispatcher;
    private final SlashCommandRegistry slashRegistry;
    private final SlashTokenHandler slashTokenHandler;
    private final WorkerServices services;
    private final ApplicationContext applicationContext;

    /** 已加载的插件清单（供 plugin.list RPC 查询）。 */
    private final List<LoadedPlugin> loadedPlugins = new ArrayList<>();

    public PluginLoader(WorkerProperties props,
            List<PluginScanner> pluginScanners,
            AdvisorProviderRegistry advisorRegistry,
            ToolProviderRegistry toolRegistry,
            SandboxProviderRegistry sandboxRegistry,
            SearchProviderRegistry searchRegistry,
            AuthorizationHandlerRegistry authHandlerRegistry,
            ToolExecutionInterceptorRegistry toolInterceptorRegistry,
            TaskLifecycleRegistry lifecycleRegistry,
            ChatModelEnhancerRegistry chatModelEnhancerRegistry,
            TaskAdmissionPolicyRegistry admissionPolicyRegistry,
            SkillContributorRegistry skillContributorRegistry,
            RpcDispatcher rpcDispatcher,
            SlashCommandRegistry slashRegistry,
            SlashTokenHandler slashTokenHandler,
            WorkerServices services,
            ApplicationContext applicationContext) {
        this.props = props;
        this.pluginScanners = pluginScanners;
        this.advisorRegistry = advisorRegistry;
        this.toolRegistry = toolRegistry;
        this.sandboxRegistry = sandboxRegistry;
        this.searchRegistry = searchRegistry;
        this.authHandlerRegistry = authHandlerRegistry;
        this.toolInterceptorRegistry = toolInterceptorRegistry;
        this.lifecycleRegistry = lifecycleRegistry;
        this.chatModelEnhancerRegistry = chatModelEnhancerRegistry;
        this.admissionPolicyRegistry = admissionPolicyRegistry;
        this.skillContributorRegistry = skillContributorRegistry;
        this.rpcDispatcher = rpcDispatcher;
        this.slashRegistry = slashRegistry;
        this.slashTokenHandler = slashTokenHandler;
        this.services = services;
        this.applicationContext = applicationContext;
    }

    @PostConstruct
    void init() {
        scanAndLoad();
    }

    /**
     * 遍历所有 {@link PluginScanner} 收集插件并统一加载。
     * 在 worker 启动时（@PostConstruct）调用一次。
     */
    public void scanAndLoad() {
        List<ScannedPlugin> scanned = new ArrayList<>();
        for (PluginScanner scanner : pluginScanners) {
            try {
                scanned.addAll(scanner.scan());
            } catch (Exception e) {
                log.warn("[plugins] 插件扫描器 {} 扫描失败,跳过: {}",
                        scanner.getClass().getSimpleName(), e.getMessage(), e);
            }
        }

        if (scanned.isEmpty()) {
            log.info("[plugins] 未扫描到任何插件");
            return;
        }

        // builtin 优先:同名冲突时内置插件先加载,外部同名插件随后被去重跳过。
        // List.sort 稳定,同 source 内保持各扫描器返回顺序。
        scanned.sort(Comparator.comparingInt(s -> "builtin".equals(s.source()) ? 0 : 1));

        log.info("[plugins] 扫描到 {} 个插件(来自 {} 个扫描器)", scanned.size(), pluginScanners.size());

        for (ScannedPlugin sp : scanned) {
            loadPlugin(sp);
        }

        log.info("[plugins] 加载完成: {}/{} 个插件成功加载", loadedPlugins.size(), scanned.size());
    }

    /**
     * 加载单个插件(builtin 与 external 统一入口,按 source 分派 manifest/jar 解析)。
     */
    private void loadPlugin(ScannedPlugin sp) {
        Path pluginDir = sp.pluginDir();
        String source = sp.source();
        boolean builtin = "builtin".equals(source);
        String dirName = pluginDir.getFileName().toString();

        // 解析 plugin.json(manifest 路径按 source 分派)
        Path manifest = builtin
                ? BuiltInPluginScanner.resolveManifestPath(pluginDir)
                : pluginDir.resolve("plugin.json");
        if (manifest == null || !Files.isRegularFile(manifest)) {
            log.warn("[plugins] 插件清单不存在,跳过: {} (source={})", dirName, source);
            return;
        }
        JsonNode json;
        try {
            json = Json.parse(Files.readString(manifest));
        } catch (IOException e) {
            log.warn("[plugins] 插件清单解析失败,跳过: {} ({})(source={})", dirName, e.getMessage(), source);
            return;
        }

        String id = json.path("id").asString("");
        if (id.isEmpty()) {
            id = dirName;
        }
        final String pluginId = id;

        // 检查是否已加载(重复注册;builtin 排前,故同名冲突时内置赢,外部被跳过)
        if (loadedPlugins.stream().anyMatch(p -> p.id().equals(pluginId))) {
            log.warn("[plugins] 插件 {} 已加载,跳过重复: {} (source={})", id, dirName, source);
            return;
        }

        String name = json.path("name").asString(id);
        String version = json.path("version").asString("0.0.0");
        String description = json.path("description").asString("");
        String author = json.path("author").asString("");
        // 入口类:优先顶层 "main" 字段,兼容旧格式 provides.spi.EveryAgentPlugin
        String entryClass = json.path("main").asString("");
        if (entryClass.isEmpty()) {
            entryClass = json.path("provides").path("spi").path("EveryAgentPlugin").asString("");
        }
        String webMain = json.path("webMain").asString("");

        // 解析 contributes.config 默认值 → PluginConfig
        Map<String, Object> configDefaults = new HashMap<>();
        JsonNode contributes = json.path("contributes").path("config");
        if (contributes.isObject()) {
            for (var e : contributes.properties()) {
                JsonNode def = e.getValue().path("default");
                if (!def.isMissingNode() && !def.isNull()) {
                    configDefaults.put(e.getKey(), unwrapJsonNode(def));
                }
            }
        }
        PluginConfigImpl config = new PluginConfigImpl(configDefaults);

        // 无入口类 = 纯声明式插件（只有 plugin.json 贡献，无 Java 代码）
        if (entryClass.isEmpty()) {
            log.info("[plugins] 声明式插件已注册: id={} name={} v{} (无 Java 入口,source={})",
                    id, name, version, source);
            loadedPlugins.add(new LoadedPlugin(id, name, version, description, author,
                    pluginDir, source, true, "声明式插件", "", webMain));
            return;
        }

        // 查找 jar 文件(jar 列表按 source 分派)
        List<Path> jars;
        if (builtin) {
            jars = BuiltInPluginScanner.findTargetJars(pluginDir);
        } else {
            Path libDir = pluginDir.resolve("lib");
            try (Stream<Path> jarStream = Files.list(libDir)) {
                jars = jarStream.filter(p -> p.toString().endsWith(".jar")).sorted().toList();
            } catch (IOException e) {
                log.warn("[plugins] 插件 {} 的 lib 目录不可读,跳过: {}", id, e.getMessage());
                loadedPlugins.add(new LoadedPlugin(id, name, version, description, author,
                        pluginDir, source, false, "lib 目录不可读: " + e.getMessage(), entryClass, webMain));
                return;
            }
        }

        if (jars.isEmpty()) {
            log.warn("[plugins] 插件 {} 声明了入口类但无可用 jar (source={})", id, source);
            loadedPlugins.add(new LoadedPlugin(id, name, version, description, author,
                    pluginDir, source, false, "无 jar 文件", entryClass, webMain));
            return;
        }

        // ClassLoader 隔离加载
        URL[] urls = jars.stream()
                .map(p -> {
                    try {
                        return p.toUri().toURL();
                    } catch (Exception e) {
                        log.warn("[plugins] jar URL 转换失败: {}", p, e);
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull)
                .toArray(URL[]::new);

        URLClassLoader classLoader = new URLClassLoader(urls, getClass().getClassLoader());

        try {
            // 反射实例化 EveryAgentPlugin
            Class<?> clazz = classLoader.loadClass(entryClass);
            if (!EveryAgentPlugin.class.isAssignableFrom(clazz)) {
                log.warn("[plugins] 插件 {} 的入口类 {} 未实现 EveryAgentPlugin 接口", id, entryClass);
                loadedPlugins.add(new LoadedPlugin(id, name, version, description, author,
                        pluginDir, source, false, "入口类未实现 EveryAgentPlugin", entryClass, webMain));
                return;
            }

            EveryAgentPlugin plugin = (EveryAgentPlugin) clazz.getDeclaredConstructor().newInstance();

            // 构造 WorkerPluginContext
            WorkerPluginContext ctx = new WorkerPluginContextImpl(id,
                    advisorRegistry, toolRegistry, sandboxRegistry,
                    searchRegistry,
                    authHandlerRegistry, toolInterceptorRegistry,
                    lifecycleRegistry, chatModelEnhancerRegistry,
                    admissionPolicyRegistry,
                    skillContributorRegistry,
                    rpcDispatcher, slashRegistry, slashTokenHandler, services, config, applicationContext);

            // 调用 activate()
            plugin.activate(ctx);

            loadedPlugins.add(new LoadedPlugin(id, name, version, description, author,
                    pluginDir, source, true, builtin ? "已激活(内置)" : "已激活", entryClass, webMain));
            log.info("[plugins] 插件已激活: id={} name={} v{} entry={} source={}",
                    id, name, version, entryClass, source);

        } catch (Exception e) {
            log.warn("[plugins] 插件 {} 激活失败: {}", id, e.getMessage(), e);
            loadedPlugins.add(new LoadedPlugin(id, name, version, description, author,
                    pluginDir, source, false, "激活失败: " + e.getMessage(), entryClass, webMain));
        }
    }

    /** 获取已加载插件清单（供 plugin.list RPC）。 */
    public List<LoadedPlugin> getLoadedPlugins() {
        return List.copyOf(loadedPlugins);
    }

    /**
     * 已加载的插件信息 record。
     *
     * @param source 插件来源({@code "builtin"} / {@code "external"} 等,与扫描器约定一致)
     */
    public record LoadedPlugin(String id, String name, String version, String description,
            String author, Path pluginDir, String source, boolean active, String status,
            String main, String webMain) {
    }

    /** 将 JsonNode 解包为原生 Java 对象（String/Long/Double/Boolean/null）。 */
    private static Object unwrapJsonNode(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asString();
        }
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        return node.toString();
    }
}
