package dev.everyagent.worker.plugin.registry;

import dev.everyagent.worker.plugin.spi.SandboxBackend;
import dev.everyagent.worker.plugin.spi.SandboxProvider;
import dev.everyagent.worker.plugin.spi.SandboxProvider.SandboxConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * SandboxProvider SPI 注册表。
 *
 * <p>核心改造点 C3（§6.3）：{@code OsSandbox} 从此注册表选择沙箱后端，
 * 替代硬编码 {@code if (backend == WSL_BWRAP) ... else if (WSL_DIRECT) ... else WindowsSandbox.run(...)}。
 *
 * <p>选择策略：
 * <ul>
 *   <li>用户显式配置 type（如 "wsl-bwrap"）→ 找到 id 匹配且 isAvailable() 的 provider</li>
 *   <li>auto / 未配置 → 按 priority 降序选第一个 isAvailable() 的 provider</li>
 *   <li>全部不可用 → 返回 null（调用方退化为直接 spawn）</li>
 * </ul>
 */
@Component
public class SandboxProviderRegistry {

    private static final Logger log = LoggerFactory.getLogger(SandboxProviderRegistry.class);

    private final List<SandboxProvider> providers = new CopyOnWriteArrayList<>();

    /** 注册一个 SandboxProvider。 */
    public void register(SandboxProvider provider) {
        providers.add(provider);
    }

    /** 注销一个 SandboxProvider。 */
    public void unregister(SandboxProvider provider) {
        providers.remove(provider);
    }

    /** 获取全部已注册的 SandboxProvider。 */
    public List<SandboxProvider> getProviders() {
        return new ArrayList<>(providers);
    }

    /**
     * 按配置选择一个可用的沙箱后端。
     *
     * @param config 沙箱配置
     * @return 选中的沙箱后端，或 null（全部不可用）
     */
    public SandboxBackend select(SandboxConfig config) {
        if (!config.enabled() || "none".equalsIgnoreCase(config.type())) {
            return null;
        }

        // 显式 type：找 id 匹配且可用的 provider
        if (config.type() != null && !config.type().isBlank()
                && !"auto".equalsIgnoreCase(config.type())) {
            for (SandboxProvider p : providers) {
                if (p.id().equalsIgnoreCase(config.type()) && p.isAvailable()) {
                    return p.create(config);
                }
            }
            log.warn("[sandbox] 显式配置 type={} 无可用 provider，尝试 auto 选择", config.type());
        }

        // auto：按 priority 降序选第一个可用的
        return providers.stream()
                .filter(SandboxProvider::isAvailable)
                .max(Comparator.comparingInt(SandboxProvider::priority))
                .map(p -> p.create(config))
                .orElse(null);
    }
}
