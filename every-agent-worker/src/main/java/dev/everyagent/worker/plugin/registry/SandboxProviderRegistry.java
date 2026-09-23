package dev.everyagent.worker.plugin.registry;

import dev.everyagent.worker.plugin.spi.SandboxBackend;
import dev.everyagent.worker.plugin.spi.SandboxProvider;
import dev.everyagent.worker.plugin.spi.SandboxProvider.SandboxConfig;
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
 * 禁用过滤：select 时跳过被禁用的 provider。
 */
@Component
public class SandboxProviderRegistry {

    private static final Logger log = LoggerFactory.getLogger(SandboxProviderRegistry.class);

    private final List<SandboxProvider> providers = new CopyOnWriteArrayList<>();
    private final PluginStateStore stateStore;

    public SandboxProviderRegistry(PluginStateStore stateStore) {
        this.stateStore = stateStore;
    }

    public void register(SandboxProvider provider) {
        providers.add(provider);
    }

    public void unregister(SandboxProvider provider) {
        providers.remove(provider);
    }

    public List<SandboxProvider> getProviders() {
        return providers.stream()
                .filter(p -> !stateStore.isDisabled(p.id()))
                .toList();
    }

    public SandboxBackend select(SandboxConfig config) {
        if (!config.enabled() || "none".equalsIgnoreCase(config.type())) {
            return null;
        }

        if (config.type() != null && !config.type().isBlank()
                && !"auto".equalsIgnoreCase(config.type())) {
            for (SandboxProvider p : providers) {
                if (!stateStore.isDisabled(p.id())
                        && p.id().equalsIgnoreCase(config.type()) && p.isAvailable()) {
                    return p.create(config);
                }
            }
            log.warn("[sandbox] 显式配置 type={} 无可用 provider，尝试 auto 选择", config.type());
        }

        return providers.stream()
                .filter(p -> !stateStore.isDisabled(p.id()))
                .filter(SandboxProvider::isAvailable)
                .max(Comparator.comparingInt(SandboxProvider::priority))
                .map(p -> p.create(config))
                .orElse(null);
    }
}
