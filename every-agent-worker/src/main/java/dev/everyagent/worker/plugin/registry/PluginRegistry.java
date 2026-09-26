package dev.everyagent.worker.plugin.registry;

import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.plugin.loader.PluginLoader;
import dev.everyagent.worker.plugin.loader.PluginLoader.LoadedPlugin;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 插件完整目录注册表 —— 替代原 {@code PluginStateStore} 的禁用管理 + 目录聚合。
 *
 * <p>职责：
 * <ul>
 *   <li>从 {@link PluginLoader#getLoadedPlugins()} 统一获取所有已加载插件
 *       （内置 + 外部均来自此处，PluginLoader 已在 @PostConstruct 完成扫描加载），
 *       构建 {@link PluginManifest} 条目放入完整目录</li>
 *   <li>持久化禁用列表到 {@code ~/.everyagent/plugins/.disabled-plugins}（每行一个 id）</li>
 *   <li>{@link #enable(String)} / {@link #disable(String)} 修改内存 + 落盘</li>
 *   <li>启动时 load 恢复禁用集合</li>
 * </ul>
 *
 * <p>本类不再扫描 classpath 也不再激活插件——插件扫描与激活已统一收编到
 * PluginLoader 的多扫描器加载流程（插件扫描机制统一重构第 2 步）。
 * 本类只负责目录聚合 + 禁用管理。
 *
 * <p>使用 {@code @DependsOn("pluginLoader")} 确保 PluginLoader 的 @PostConstruct
 * （scanAndLoad）先于本类 {@link #init()} 执行，使 getLoadedPlugins() 在聚合时已就绪。
 */
@Component
@DependsOn("pluginLoader")
public class PluginRegistry {

    private static final Logger log = LoggerFactory.getLogger(PluginRegistry.class);

    private final WorkerProperties props;
    private final PluginLoader pluginLoader;

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
    }

    /** 从 PluginLoader 的已加载插件列表构建完整目录（内置 + 外部统一来源）。 */
    private void scanCatalog() {
        List<LoadedPlugin> loaded = pluginLoader.getLoadedPlugins();
        if (loaded.isEmpty()) {
            log.warn("[plugins] PluginLoader 未加载任何插件,目录为空");
        }
        for (LoadedPlugin p : loaded) {
            catalog.put(p.id(), new PluginManifest(
                    p.id(), p.name(), p.version(), p.description(), p.author(),
                    p.main(), p.webMain(), p.source()));
        }
        log.info("[plugins] 目录扫描完成: 共 {} 个插件", catalog.size());
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

    /** 插件目录根路径（供 install/uninstall/webSource RPC 使用）。 */
    public Path getPluginsRoot() {
        return props.resolvePluginsDir();
    }
}
