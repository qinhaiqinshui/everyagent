package dev.everyagent.worker.plugin.registry;

import dev.everyagent.plugin.api.spi.AdvisorProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * AdvisorProvider SPI 注册表。
 *
 * <p>注册进来的都有效，查询直接返回全量。AgentBuilder 从 getProviders() 聚合后按 order 排序。
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

    public List<AdvisorProvider> getProviders() {
        return List.copyOf(providers);
    }
}
