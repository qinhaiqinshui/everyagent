package dev.everyagent.worker.plugin.registry;

import dev.everyagent.worker.config.WorkerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 插件禁用名单(唯一真相源)——只负责「名单 + 落盘」,零依赖其他插件组件。
 *
 * <p><b>禁用如何生效</b>:{@link dev.everyagent.worker.plugin.loader.PluginLoader}
 * 在加载每个插件时先查本名单,被禁用的插件<b>核心不调它的 activate 入口</b>——
 * 插件压根没被激活,自然也不会向任何 SPI 注册表(advisor/tool/interceptor/授权链/slash 等)
 * 注册自己的贡献,因此各注册表不需要知道「禁用」这个概念,也不做二次过滤。
 *
 * <p>本类可被 PluginLoader 依赖链上的任意组件安全注入(它只依赖 {@link WorkerProperties});
 * 反过来注册表去注入 {@code PluginRegistry} 会成构造环
 * ({@code SlashCommandRegistry → PluginRegistry → PluginLoader → SlashCommandRegistry})。
 *
 * <p>禁用状态持久化到 {@code ~/.everyagent/plugins/.disabled-plugins}(每行一个插件 id):
 * 构造时读盘恢复;{@code plugin.enable} / {@code plugin.disable} RPC 经
 * {@link PluginRegistry} 委托本类改内存 + 立即落盘。
 * 名单变更对<b>下一次 worker 启动</b>完全生效(插件系统没有 deactivate 钩子,
 * 已激活的插件在当前进程内贡献留在注册表里)。
 */
@Component
public class PluginStateStore {

    private static final Logger log = LoggerFactory.getLogger(PluginStateStore.class);

    /** 禁用名单文件名(每行一个插件 id)。 */
    private static final String DISABLED_FILE = ".disabled-plugins";

    private final WorkerProperties props;

    private final Set<String> disabledPluginIds = ConcurrentHashMap.newKeySet();

    public PluginStateStore(WorkerProperties props) {
        this.props = props;
        // 构造即读盘:注入方(PluginLoader)拿到的实例一定已带禁用名单,
        // 不依赖 @PostConstruct 的相对时序。
        load();
    }

    /** 禁用插件(改内存 + 落盘)。 */
    public void disable(String pluginId) {
        disabledPluginIds.add(pluginId);
        persist();
    }

    /** 启用插件(改内存 + 落盘)。 */
    public void enable(String pluginId) {
        disabledPluginIds.remove(pluginId);
        persist();
    }

    /** 插件是否被禁用。 */
    public boolean isDisabled(String pluginId) {
        return disabledPluginIds.contains(pluginId);
    }

    /** 获取全部已禁用的插件 id。 */
    public Set<String> disabledIds() {
        return Set.copyOf(disabledPluginIds);
    }

    /** 从磁盘加载禁用列表。 */
    private void load() {
        Path file = disabledFile();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file)) {
                String id = line.trim();
                if (!id.isEmpty() && !id.startsWith("#")) {
                    disabledPluginIds.add(id);
                }
            }
            log.info("[plugins] 恢复 {} 个禁用插件", disabledPluginIds.size());
        } catch (IOException e) {
            log.warn("[plugins] 读取禁用列表失败: {}", e.getMessage());
        }
    }

    /** 落盘禁用列表。 */
    private void persist() {
        Path file = disabledFile();
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, disabledPluginIds.stream().sorted().toList());
        } catch (IOException e) {
            log.warn("[plugins] 写入禁用列表失败: {}", e.getMessage());
        }
    }

    private Path disabledFile() {
        return props.resolvePluginsDir().resolve(DISABLED_FILE);
    }
}
