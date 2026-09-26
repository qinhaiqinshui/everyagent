package dev.everyagent.worker.plugin.registry;

import dev.everyagent.plugin.api.spi.SandboxBackend;
import dev.everyagent.plugin.api.spi.SandboxProvider;
import dev.everyagent.plugin.api.spi.SandboxProvider.SandboxConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * SandboxProvider SPI 注册表。
 *
 * <p>核心改造点 C3：OsSandbox 从此注册表选择沙箱后端。
 * 注册进来的都有效，查询直接返回全量。
 */
@Component
public class SandboxProviderRegistry {

    private static final Logger log = LoggerFactory.getLogger(SandboxProviderRegistry.class);

    private final List<SandboxProvider> providers = new CopyOnWriteArrayList<>();

    public void register(SandboxProvider provider) {
        providers.add(provider);
    }

    public void unregister(SandboxProvider provider) {
        providers.remove(provider);
    }

    public List<SandboxProvider> getProviders() {
        return List.copyOf(providers);
    }

    public SandboxBackend select(SandboxConfig config) {
        if (!config.enabled() || "none".equalsIgnoreCase(config.type())) {
            return null;
        }

        if (config.type() != null && !config.type().isBlank()
                && !"auto".equalsIgnoreCase(config.type())) {
            for (SandboxProvider p : providers) {
                if (p.id().equalsIgnoreCase(config.type()) && p.isAvailable()) {
                    return p.create(config);
                }
            }
            log.warn("[sandbox] 显式配置 type={} 无可用 provider，尝试 auto 选择", config.type());
        }

        return providers.stream()
                .filter(SandboxProvider::isAvailable)
                .max(Comparator.comparingInt(SandboxProvider::priority))
                .map(p -> p.create(config))
                .orElse(null);
    }
}
