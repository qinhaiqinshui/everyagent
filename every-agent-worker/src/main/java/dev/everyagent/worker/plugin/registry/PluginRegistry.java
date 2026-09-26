package dev.everyagent.worker.plugin.registry;

import dev.everyagent.contract.json.Json;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.plugin.api.PluginConfig;
import dev.everyagent.worker.plugin.loader.PluginLoader;
import dev.everyagent.worker.plugin.PluginConfigImpl;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 插件完整目录注册表 —— 替代原 {@link PluginStateStore} 的禁用管理 + 目录聚合。
 *
 * <p>职责：
 * <ul>
 *   <li>扫描 classpath 下所有 {@code plugin.json}（{@code classpath*:plugin.json}）
 *       + 外部插件目录（从 {@link PluginLoader} 获取已加载信息），建立
 *       {@code Map<pluginId, PluginManifest>} 完整目录</li>
 *   <li>持久化禁用列表到 {@code ~/.everyagent/plugins/.disabled-plugins}（每行一个 id）</li>
 *   <li>{@link #enable(String)} / {@link #disable(String)} 修改内存 + 落盘</li>
 *   <li>启动时 load 恢复禁用集合</li>
 * </ul>
 *
 * <p>SPI 注册表不再持有禁用过滤逻辑——注册进来的都有效，查询直接返回全量。
 * 禁用仅影响 {@code plugin.list} 的展示（前端据此隐藏/灰显），不影响已注册的 SPI。
 */
@Component
public class PluginRegistry {

    private static final Logger log = LoggerFactory.getLogger(PluginRegistry.class);

    private final WorkerProperties props;
    private final PluginLoader pluginLoader;
    private final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    /** 完整插件目录（id → manifest）。 */
    private final Map<String, PluginManifest> catalog = new ConcurrentHashMap<>();

    /** 禁用的插件 id 集合。 */
    private final Set<String> disabledIds = ConcurrentHashMap.newKeySet();

    public PluginRegistry(WorkerProperties props, PluginLoader pluginLoader) {
        this.props = props;
        this.pluginLoader = pluginLoader;
    }

    @PostConstruct
    void init() {
        scanCatalog();
        loadDisabled();
        activateBuiltInPlugins();
    }

    /** 激活未禁用的内置插件（有 main 入口类的）。 */
    private void activateBuiltInPlugins() {
        for (PluginManifest m : catalog.values()) {
            if (!"builtin".equals(m.source())) {
                continue;
            }
            if (m.main() == null || m.main().isBlank()) {
                continue;
            }
            if (disabledIds.contains(m.id())) {
                log.info("[plugins] 内置插件 {} 已禁用,跳过激活", m.id());
                continue;
            }
            PluginConfig config = new PluginConfigImpl(new HashMap<>());
            pluginLoader.activateBuiltInPlugin(m.id(), m.main(), config);
        }
    }

    /** 扫描 classpath + 外部插件目录，建立完整目录。 */
    private void scanCatalog() {
        // 1. 扫描 classpath*:plugin.json（内置插件）
        try {
            Resource[] resources = resolver.getResources("classpath*:plugin.json");
            for (Resource res : resources) {
                try {
                    JsonNode json = Json.parse(res.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
                    PluginManifest m = parseManifest(json, "builtin");
                    catalog.put(m.id(), m);
                } catch (Exception e) {
                    log.warn("[plugins] 解析 classpath plugin.json 失败: {} ({})", res.getURL(), e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("[plugins] 扫描 classpath plugin.json 失败: {}", e.getMessage());
        }

        // 2. 从 PluginLoader 获取外部插件信息
        for (PluginLoader.LoadedPlugin p : pluginLoader.getLoadedPlugins()) {
            String main = p.active() && !"声明式插件".equals(p.status()) ? "" : "";
            // 外部插件没有 main 字段信息（PluginLoader 未暴露），hasMain 由 catalog 条目判断
            catalog.put(p.id(), new PluginManifest(
                    p.id(), p.name(), p.version(), p.description(), p.author(),
                    "", "", "external"));
        }

        log.info("[plugins] 目录扫描完成: 共 {} 个插件", catalog.size());
    }

    /** 从 plugin.json 节点解析 manifest。 */
    private PluginManifest parseManifest(JsonNode json, String source) {
        String id = json.path("id").asString("");
        String name = json.path("name").asString(id);
        String version = json.path("version").asString("0.0.0");
        String description = json.path("description").asString("");
        String author = json.path("author").asString("");
        // 优先用顶层 "main" 字段，兼容旧格式 provides.spi.EveryAgentPlugin
        String main = json.path("main").asString("");
        if (main.isEmpty()) {
            main = json.path("provides").path("spi").path("EveryAgentPlugin").asString("");
        }
        String webMain = json.path("webMain").asString("");
        return new PluginManifest(id, name, version, description, author, main, webMain, source);
    }

    // ---- 禁用管理 ----

    /** 从磁盘加载禁用列表。 */
    private void loadDisabled() {
        Path file = disabledFile();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            List<String> lines = Files.readAllLines(file);
            for (String line : lines) {
                String id = line.trim();
                if (!id.isEmpty() && !id.startsWith("#")) {
                    disabledIds.add(id);
                }
            }
            log.info("[plugins] 恢复 {} 个禁用插件", disabledIds.size());
        } catch (IOException e) {
            log.warn("[plugins] 读取禁用列表失败: {}", e.getMessage());
        }
    }

    /** 落盘禁用列表。 */
    private void persistDisabled() {
        Path file = disabledFile();
        try {
            Files.createDirectories(file.getParent());
            List<String> lines = disabledIds.stream().sorted().toList();
            Files.write(file, lines);
        } catch (IOException e) {
            log.warn("[plugins] 写入禁用列表失败: {}", e.getMessage());
        }
    }

    private Path disabledFile() {
        return props.resolvePluginsDir().resolve(".disabled-plugins");
    }

    /** 启用插件（移除禁用标记 + 落盘）。 */
    public void enable(String pluginId) {
        disabledIds.remove(pluginId);
        persistDisabled();
    }

    /** 禁用插件（加入禁用集合 + 落盘）。 */
    public void disable(String pluginId) {
        disabledIds.add(pluginId);
        persistDisabled();
    }

    /** 插件是否被禁用。 */
    public boolean isDisabled(String pluginId) {
        return disabledIds.contains(pluginId);
    }

    /** 获取全部已禁用的插件 id。 */
    public Set<String> disabledIds() {
        return Set.copyOf(disabledIds);
    }

    // ---- 目录查询 ----

    /** 返回全部插件目录（不可变快照）。 */
    public List<PluginManifest> catalog() {
        return List.copyOf(catalog.values());
    }

    /** 按 id 获取单个插件 manifest。 */
    public PluginManifest get(String pluginId) {
        return catalog.get(pluginId);
    }

    /** 外部插件目录路径（供 webSource 读取）。 */
    public Path getPluginsRoot() {
        return props.resolvePluginsDir();
    }
}
