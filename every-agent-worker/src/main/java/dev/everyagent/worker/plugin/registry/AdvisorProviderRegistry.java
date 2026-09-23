package dev.everyagent.worker.plugin.registry;

import dev.everyagent.worker.plugin.spi.AdvisorProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * AdvisorProvider SPI 注册表。
 *
 * <p>核心改造点 C2（§6.2）：{@code AgentClientFactory.forMain()/forSub()} 从此注册表聚合 Advisor，
 * 替代硬编码 12 个 Advisor 的创建与顺序。
 *
 * <p>Advisor 顺序按 {@link AdvisorProvider#order()} 排序（数字从小到大 = 从外层到内层）。
 */
@Component
public class AdvisorProviderRegistry {

    private final List<AdvisorProvider> providers = new CopyOnWriteArrayList<>();

    /** 注册一个 AdvisorProvider。 */
    public void register(AdvisorProvider provider) {
        providers.add(provider);
    }

    /** 注销一个 AdvisorProvider。 */
    public void unregister(AdvisorProvider provider) {
        providers.remove(provider);
    }

    /**
     * 获取适用于主 agent 的 AdvisorProvider，按 order 排序（scope != SUB）。
     */
    public List<AdvisorProvider> getForMain() {
        return providers.stream()
                .filter(p -> p.scope() != AdvisorProvider.Scope.SUB)
                .sorted(Comparator.comparingInt(AdvisorProvider::order))
                .toList();
    }

    /**
     * 获取适用于子 agent 的 AdvisorProvider，按 order 排序（scope != MAIN）。
     */
    public List<AdvisorProvider> getForSub() {
        return providers.stream()
                .filter(p -> p.scope() != AdvisorProvider.Scope.MAIN)
                .sorted(Comparator.comparingInt(AdvisorProvider::order))
                .toList();
    }

    /** 获取全部已注册的 AdvisorProvider。 */
    public List<AdvisorProvider> getProviders() {
        return new ArrayList<>(providers);
    }
}
