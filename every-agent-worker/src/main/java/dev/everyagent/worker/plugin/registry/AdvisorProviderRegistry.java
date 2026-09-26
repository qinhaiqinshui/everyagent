package dev.everyagent.worker.plugin.registry;

import dev.everyagent.plugin.api.spi.AdvisorProvider;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * AdvisorProvider SPI 注册表。
 *
 * <p>核心改造点 C2：AgentClientFactory.forMain()/forSub() 从此注册表聚合 Advisor。
 * 注册进来的都有效，查询直接返回全量。
 */
@Component
public class AdvisorProviderRegistry {

    private final List<AdvisorProvider> providers = new CopyOnWriteArrayList<>();

    public void register(AdvisorProvider provider) {
        providers.add(provider);
    }

    public void unregister(AdvisorProvider provider) {
        providers.remove(provider);
    }

    public List<AdvisorProvider> getForMain() {
        return providers.stream()
                .filter(p -> p.scope() != AdvisorProvider.Scope.SUB)
                .sorted(Comparator.comparingInt(AdvisorProvider::order))
                .toList();
    }

    public List<AdvisorProvider> getForSub() {
        return providers.stream()
                .filter(p -> p.scope() != AdvisorProvider.Scope.MAIN)
                .sorted(Comparator.comparingInt(AdvisorProvider::order))
                .toList();
    }

    public List<AdvisorProvider> getProviders() {
        return List.copyOf(providers);
    }
}
