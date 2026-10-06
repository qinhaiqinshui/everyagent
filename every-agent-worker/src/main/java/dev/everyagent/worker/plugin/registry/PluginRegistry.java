package dev.everyagent.worker.plugin.registry;

import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.plugin.loader.PluginLoader;
import dev.everyagent.worker.plugin.loader.PluginLoader.LoadedPlugin;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 插件完整目录注册表 —— 目录聚合 + 启用/禁用门面(名单真相源在 {@link PluginStateStore})。
 *
 * <p>职责：
 * <ul>
 *   <li>从 {@link PluginLoader#getLoadedPlugins()} 统一获取所有已加载插件
 *       （内置 + 外部均来自此处，PluginLoader 已在 @PostConstruct 完成扫描加载），
 *       构建 {@link PluginManifest} 条目放入完整目录</li>
 *   <li>{@link #enable(String)} / {@link #disable(String)} 委托 {@link PluginStateStore}
 *       改名单 + 落盘；禁用如何生效见该类 javadoc（核心不调被禁用插件的 activate）</li>
 * </ul>
 *
 * <p>本类不再扫描 classpath 也不再激活插件——插件扫描与激活已统一收编到
 * PluginLoader 的多扫描器加载流程（插件扫描机制统一重构第 2 步）。
 * 本类只负责目录聚合 + 开关门面。
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
    /** 禁用名单真相源（读盘/落盘都在它那儿）。 */
    private final PluginStateStore pluginStates;

    /** 完整插件目录（id → manifest）。 */
    private final Map<String, PluginManifest> catalog = new ConcurrentHashMap<>();

    public PluginRegistry(WorkerProperties props, PluginLoader pluginLoader,
            PluginStateStore pluginStates) {
        this.props = props;
        this.pluginLoader = pluginLoader;
        this.pluginStates = pluginStates;
    }

    @PostConstruct
    void init() {
        scanCatalog();
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
                    p.main(), p.webMain(), p.source(), p.status(), p.pluginDir(),
                    p.icon(), p.repository(), p.license(), p.homepage(), p.categories()));
        }
        log.info("[plugins] 目录扫描完成: 共 {} 个插件", catalog.size());
    }

    // ---- 禁用管理(委托 PluginStateStore)----

    /** 启用插件（运行时改名单 + 落盘；下次 worker 启动不再激活它）。 */
    public void enable(String pluginId) {
        pluginStates.enable(pluginId);
    }

    /** 禁用插件（运行时改名单 + 落盘；下次 worker 启动核心不调它的 activate）。 */
    public void disable(String pluginId) {
        pluginStates.disable(pluginId);
    }

    /** 插件是否被禁用。 */
    public boolean isDisabled(String pluginId) {
        return pluginStates.isDisabled(pluginId);
    }

    /** 获取全部已禁用的插件 id。 */
    public Set<String> disabledIds() {
        return pluginStates.disabledIds();
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
