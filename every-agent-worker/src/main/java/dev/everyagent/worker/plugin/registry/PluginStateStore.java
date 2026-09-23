package dev.everyagent.worker.plugin.registry;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 插件启用/禁用管理器。
 *
 * <p>内置插件随主包打包，不能卸载，但可以禁用。
 * 禁用的插件 ID 从所有 SPI 注册表的查询结果中过滤掉。
 *
 * <p>禁用状态持久化到 {@code ~/.everyagent/plugins/.disabled-plugins}（每行一个插件 id）。
 * 启动时加载；plugin.enable / plugin.disable RPC 运行时修改 + 落盘。
 */
@Component
public class PluginStateStore {

    private final Set<String> disabledPluginIds = ConcurrentHashMap.newKeySet();

    /** 禁用插件。 */
    public void disable(String pluginId) {
        disabledPluginIds.add(pluginId);
    }

    /** 启用插件。 */
    public void enable(String pluginId) {
        disabledPluginIds.remove(pluginId);
    }

    /** 插件是否被禁用。 */
    public boolean isDisabled(String pluginId) {
        return disabledPluginIds.contains(pluginId);
    }

    /** 获取全部已禁用的插件 id。 */
    public Set<String> disabledIds() {
        return Set.copyOf(disabledPluginIds);
    }

    /** 从集合加载禁用列表（启动时调用）。 */
    public void loadDisabled(Set<String> ids) {
        disabledPluginIds.clear();
        disabledPluginIds.addAll(ids);
    }
}
