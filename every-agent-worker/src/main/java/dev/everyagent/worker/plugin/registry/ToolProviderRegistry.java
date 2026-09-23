package dev.everyagent.worker.plugin.registry;

import dev.everyagent.plugin.api.spi.ToolProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * ToolProvider SPI 注册表。
 *
 * <p>核心改造点 C1：TaskManager.buildMainAgent() 从此注册表聚合工具。
 * 注册时机：内置适配器在 Spring 启动时注册；外部插件在 activate() 时注册。
 * 禁用过滤：查询时经 PluginStateStore 过滤掉被禁用的 provider。
 */
@Component
public class ToolProviderRegistry {

    private final List<ToolProvider> providers = new CopyOnWriteArrayList<>();
    private final PluginStateStore stateStore;

    public ToolProviderRegistry(PluginStateStore stateStore) {
        this.stateStore = stateStore;
    }

    public void register(ToolProvider provider) {
        providers.add(provider);
    }

    public void unregister(ToolProvider provider) {
        providers.remove(provider);
    }

    public List<ToolProvider> getProviders() {
        return providers.stream()
                .filter(p -> !stateStore.isDisabled(p.pluginId()))
                .toList();
    }

    public List<ToolProvider> getForMain() {
        return providers.stream()
                .filter(p -> p.scope() != ToolProvider.Scope.SUB)
                .filter(p -> !stateStore.isDisabled(p.pluginId()))
                .toList();
    }

    public List<ToolProvider> getForSub() {
        return providers.stream()
                .filter(p -> p.scope() != ToolProvider.Scope.MAIN)
                .filter(p -> !stateStore.isDisabled(p.pluginId()))
                .toList();
    }
}
