package dev.everyagent.worker.plugin.registry;

import dev.everyagent.worker.plugin.spi.AdvisorProvider;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * AdvisorProvider SPI 注册表。
 *
 * <p>核心改造点 C2：AgentClientFactory.forMain()/forSub() 从此注册表聚合 Advisor。
 * 禁用过滤：查询时经 PluginStateStore 过滤掉被禁用的 provider。
 */
@Component
public class AdvisorProviderRegistry {

    private final List<AdvisorProvider> providers = new CopyOnWriteArrayList<>();
    private final PluginStateStore stateStore;

    public AdvisorProviderRegistry(PluginStateStore stateStore) {
        this.stateStore = stateStore;
    }

    public void register(AdvisorProvider provider) {
        providers.add(provider);
    }

    public void unregister(AdvisorProvider provider) {
        providers.remove(provider);
    }

    public List<AdvisorProvider> getForMain() {
        return providers.stream()
                .filter(p -> p.scope() != AdvisorProvider.Scope.SUB)
                .filter(p -> !stateStore.isDisabled(p.pluginId()))
                .sorted(Comparator.comparingInt(AdvisorProvider::order))
                .toList();
    }

    public List<AdvisorProvider> getForSub() {
        return providers.stream()
                .filter(p -> p.scope() != AdvisorProvider.Scope.MAIN)
                .filter(p -> !stateStore.isDisabled(p.pluginId()))
                .sorted(Comparator.comparingInt(AdvisorProvider::order))
                .toList();
    }

    public List<AdvisorProvider> getProviders() {
        return providers.stream()
                .filter(p -> !stateStore.isDisabled(p.pluginId()))
                .toList();
    }
}
