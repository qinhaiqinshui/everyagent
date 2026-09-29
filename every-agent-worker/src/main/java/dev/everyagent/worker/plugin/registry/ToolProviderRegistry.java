package dev.everyagent.worker.plugin.registry;

import dev.everyagent.plugin.api.spi.ToolProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * ToolProvider SPI 注册表。
 *
 * <p>注册进来的都有效，查询直接返回全量。AgentBuilder 从 getProviders() 聚合。
 */
@Component
public class ToolProviderRegistry {

    private final List<ToolProvider> providers = new CopyOnWriteArrayList<>();

    public void register(ToolProvider provider) {
        providers.add(provider);
    }

    public void unregister(ToolProvider provider) {
        providers.remove(provider);
    }

    public List<ToolProvider> getProviders() {
        return List.copyOf(providers);
    }
}
