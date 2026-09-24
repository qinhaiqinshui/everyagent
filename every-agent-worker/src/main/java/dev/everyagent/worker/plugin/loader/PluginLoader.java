package dev.everyagent.worker.plugin.loader;

import dev.everyagent.contract.json.Json;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.plugin.api.EveryAgentPlugin;
import dev.everyagent.plugin.api.PluginConfig;
import dev.everyagent.worker.plugin.PluginConfigImpl;
import dev.everyagent.plugin.api.WorkerPluginContext;
import dev.everyagent.worker.plugin.WorkerPluginContextImpl;
import dev.everyagent.plugin.api.WorkerServices;
import dev.everyagent.worker.plugin.registry.AdvisorProviderRegistry;
import dev.everyagent.worker.plugin.registry.AuthorizationHandlerRegistry;
import dev.everyagent.worker.plugin.registry.SandboxProviderRegistry;
import dev.everyagent.worker.plugin.registry.SearchProviderRegistry;
import dev.everyagent.worker.plugin.registry.ToolExecutionInterceptorRegistry;
import dev.everyagent.worker.plugin.registry.ToolProviderRegistry;
import dev.everyagent.worker.plugin.registry.TaskLifecycleRegistry;
import dev.everyagent.worker.plugin.registry.TaskAdmissionPolicyRegistry;
import dev.everyagent.worker.rpc.RpcDispatcher;
import dev.everyagent.worker.slash.SlashCommandRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 插件加载器 —— 扫描 {@code ~/.everyagent/plugins/}，用 ClassLoader 隔离加载外部 jar 插件。
 *
 * <p>加载流程（架构 §7.2）：
 * <ol>
 *   <li>扫描 plugins 目录下所有一级子目录</li>
 *   <li>读 plugin.json → 解析元数据</li>
 *   <li>检查 id 与内置插件冲突（内置优先）</li>
 *   <li>对每个待加载插件：URLClassLoader 隔离加载 jar，反射实例化 EveryAgentPlugin，
 *       构造 WorkerPluginContext，调用 activate()</li>
 *   <li>单个插件失败不影响其他，WARN 记录</li>
 * </ol>
 *
 * <p>ClassLoader 隔离策略（架构 §7.3）：
 * <ul>
 *   <li>每个插件一个 URLClassLoader(jar, parent=workerClassLoader)</li>
 *   <li>插件可见 worker 公共 API（plugin.spi.*、contract.*）</li>
 *   <li>插件之间互不可见</li>
 * </ul>
 */
@Component
public class PluginLoader {

    private static final Logger log = LoggerFactory.getLogger(PluginLoader.class);

    private final WorkerProperties props;
    private final BuiltInPlugins builtInPlugins;
    private final AdvisorProviderRegistry advisorRegistry;
    private final ToolProviderRegistry toolRegistry;
    private final SandboxProviderRegistry sandboxRegistry;
    private final SearchProviderRegistry searchRegistry;
    private final AuthorizationHandlerRegistry authHandlerRegistry;
    private final ToolExecutionInterceptorRegistry toolInterceptorRegistry;
    private final TaskLifecycleRegistry lifecycleRegistry;
    private final TaskAdmissionPolicyRegistry admissionPolicyRegistry;
    private final RpcDispatcher rpcDispatcher;
    private final SlashCommandRegistry slashRegistry;
    private final WorkerServices services;

    /** 已加载的插件清单（供 plugin.list RPC 查询）。 */
    private final List<LoadedPlugin> loadedPlugins = new ArrayList<>();

    public PluginLoader(WorkerProperties props, BuiltInPlugins builtInPlugins,
            AdvisorProviderRegistry advisorRegistry,
            ToolProviderRegistry toolRegistry,
            SandboxProviderRegistry sandboxRegistry,
            SearchProviderRegistry searchRegistry,
            AuthorizationHandlerRegistry authHandlerRegistry,
            ToolExecutionInterceptorRegistry toolInterceptorRegistry,
            TaskLifecycleRegistry lifecycleRegistry,
            TaskAdmissionPolicyRegistry admissionPolicyRegistry,
            RpcDispatcher rpcDispatcher,
            SlashCommandRegistry slashRegistry,
            WorkerServices services) {
        this.props = props;
        this.builtInPlugins = builtInPlugins;
        this.advisorRegistry = advisorRegistry;
        this.toolRegistry = toolRegistry;
        this.sandboxRegistry = sandboxRegistry;
        this.searchRegistry = searchRegistry;
        this.authHandlerRegistry = authHandlerRegistry;
        this.toolInterceptorRegistry = toolInterceptorRegistry;
        this.lifecycleRegistry = lifecycleRegistry;
        this.admissionPolicyRegistry = admissionPolicyRegistry;
        this.rpcDispatcher = rpcDispatcher;
        this.slashRegistry = slashRegistry;
        this.services = services;
    }

    @PostConstruct
    void init() {
        scanAndLoad();
    }

    /**
     * 扫描 plugins 目录并加载所有外部插件。
     * 在 worker 启动时（@PostConstruct）调用一次。
     */
    public void scanAndLoad() {
        Path pluginsDir = props.resolvePluginsDir();
        if (!Files.isDirectory(pluginsDir)) {
            log.info("[plugins] 插件目录不存在,跳过扫描: {}", pluginsDir);
            return;
        }

        List<Path> pluginDirs;
        try (Stream<Path> dirs = Files.list(pluginsDir)) {
            pluginDirs = dirs.filter(Files::isDirectory)
                    .filter(this::hasPluginJson)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            log.warn("[plugins] 扫描插件目录失败: {}", pluginsDir, e);
            return;
        }

        if (pluginDirs.isEmpty()) {
            log.info("[plugins] 插件目录 {} 无可用插件", pluginsDir);
            return;
        }

        log.info("[plugins] 扫描到 {} 个插件目录", pluginDirs.size());

        for (Path dir : pluginDirs) {
            loadPlugin(dir);
        }

        log.info("[plugins] 加载完成: {}/{} 个插件成功加载", loadedPlugins.size(), pluginDirs.size());
    }

    /** 检查目录中是否有 plugin.json。 */
    private boolean hasPluginJson(Path dir) {
        return Files.isRegularFile(dir.resolve("plugin.json"));
    }

    /**
     * 加载单个插件。
     */
    private void loadPlugin(Path pluginDir) {
        String dirName = pluginDir.getFileName().toString();

        // 解析 plugin.json
        Path manifest = pluginDir.resolve("plugin.json");
        JsonNode json;
        try {
            json = Json.parse(Files.readString(manifest));
        } catch (IOException e) {
            log.warn("[plugins] 插件清单解析失败,跳过: {} ({})", dirName, e.getMessage());
            return;
        }

        String id = json.path("id").asString("");
        if (id.isEmpty()) {
            id = dirName;
        }
        final String pluginId = id;

        // 检查与内置插件冲突（内置优先）
        if (builtInPlugins.builtInIds().contains(id)) {
            log.debug("[plugins] 内置插件 {},跳过外部同名: {}", id, dirName);
            return;
        }

        // 检查是否已加载（重复注册）
        if (loadedPlugins.stream().anyMatch(p -> p.id().equals(pluginId))) {
            log.warn("[plugins] 插件 {} 已加载,跳过重复: {}", id, dirName);
            return;
        }

        String name = json.path("name").asString(id);
        String version = json.path("version").asString("0.0.0");
        String description = json.path("description").asString("");
        String author = json.path("author").asString("");
        String entryClass = json.path("provides").path("spi").path("EveryAgentPlugin").asString("");

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
        PluginConfig config = new PluginConfigImpl(configDefaults);

        // 无入口类 = 纯声明式插件（只有 plugin.json 贡献，无 Java 代码）
        if (entryClass.isEmpty()) {
            log.info("[plugins] 声明式插件已注册: id={} name={} v{} (无 Java 入口)", id, name, version);
            loadedPlugins.add(new LoadedPlugin(id, name, version, description, author,
                    pluginDir, true, "声明式插件"));
            return;
        }

        // 查找 jar 文件
        Path libDir = pluginDir.resolve("lib");
        List<Path> jars;
        try (Stream<Path> jarStream = Files.list(libDir)) {
            jars = jarStream.filter(p -> p.toString().endsWith(".jar")).sorted().toList();
        } catch (IOException e) {
            log.warn("[plugins] 插件 {} 的 lib 目录不可读,跳过: {}", id, e.getMessage());
            loadedPlugins.add(new LoadedPlugin(id, name, version, description, author,
                    pluginDir, false, "lib 目录不可读: " + e.getMessage()));
            return;
        }

        if (jars.isEmpty()) {
            log.warn("[plugins] 插件 {} 声明了入口类但 lib/ 下无 jar", id);
            loadedPlugins.add(new LoadedPlugin(id, name, version, description, author,
                    pluginDir, false, "无 jar 文件"));
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
                        pluginDir, false, "入口类未实现 EveryAgentPlugin"));
                return;
            }

            EveryAgentPlugin plugin = (EveryAgentPlugin) clazz.getDeclaredConstructor().newInstance();

            // 构造 WorkerPluginContext
            WorkerPluginContext ctx = new WorkerPluginContextImpl(id,
                    advisorRegistry, toolRegistry, sandboxRegistry,
                    searchRegistry,
                    authHandlerRegistry, toolInterceptorRegistry,
                    lifecycleRegistry, admissionPolicyRegistry,
                    rpcDispatcher, slashRegistry, services, config);

            // 调用 activate()
            plugin.activate(ctx);

            loadedPlugins.add(new LoadedPlugin(id, name, version, description, author,
                    pluginDir, true, "已激活"));
            log.info("[plugins] 插件已激活: id={} name={} v{} entry={}", id, name, version, entryClass);

        } catch (Exception e) {
            log.warn("[plugins] 插件 {} 激活失败: {}", id, e.getMessage(), e);
            loadedPlugins.add(new LoadedPlugin(id, name, version, description, author,
                    pluginDir, false, "激活失败: " + e.getMessage()));
        }
    }

    /** 获取已加载插件清单（供 plugin.list RPC）。 */
    public List<LoadedPlugin> getLoadedPlugins() {
        return List.copyOf(loadedPlugins);
    }

    /**
     * 已加载的插件信息 record。
     */
    public record LoadedPlugin(String id, String name, String version, String description,
            String author, Path pluginDir, boolean active, String status) {
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
