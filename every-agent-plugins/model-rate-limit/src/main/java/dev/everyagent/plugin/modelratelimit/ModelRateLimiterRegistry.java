package dev.everyagent.plugin.modelratelimit;

import dev.everyagent.plugin.api.spi.TokenEstimator;
import dev.everyagent.plugin.api.config.WorkerConfig;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型限流器注册表(docs/design-model-rate-limit.md §6.1):按 configId 单例化
 * {@link ModelRateLimiter}(跨任务/跨 agent 共享,厂商 rpm/tpm 是账户级)。
 *
 * <p>限流参数从该 configId 的 {@code worker.models[].params} 解析;全缺省时
 * {@link #of} 返回 empty(不限流,现状兼容)。参数在首次创建时定格(配置变更后重启生效,
 * 与 worker 其它配置一致)。
 *
 * <p><b>估算器延迟解析</b>:内置插件按目录名字典序激活,{@code model-rate-limit} 排在
 * {@code token-estimator} <b>之前</b>——本插件 activate 时估算器插件可能尚未激活。
 * 故本注册表持有 {@code Supplier<TokenEstimator>} 而非估算器实例,在 {@link #of} 的
 * {@code computeIfAbsent} 缓存 lambda 内<b>取当前生效实例</b>再构造 {@link ModelRateLimiter};
 * 首次创建发生在真实 run 期间(此时所有插件均已激活),故拿到的一定是最终生效的估算器。
 *
 * <p>token 估算系数的持久化由 {@link TokenEstimator} 实现自行管理,
 * 本注册表不再持有 {@code ModelRateStateStore}。
 */
public class ModelRateLimiterRegistry {

    private final WorkerConfig config;
    private final java.util.function.Supplier<TokenEstimator> estimatorSupplier;
    private final Map<String, ModelRateLimiter> limiters = new ConcurrentHashMap<>();

    public ModelRateLimiterRegistry(WorkerConfig config,
            java.util.function.Supplier<TokenEstimator> estimatorSupplier) {
        this.config = config;
        this.estimatorSupplier = estimatorSupplier;
    }

    /** 获取 configId 对应限流器;最终限流值全为 0 → empty(装饰器直通)。 */
    public Optional<ModelRateLimiter> of(String configId, JsonNode params) {
        return Optional.ofNullable(limiters.computeIfAbsent(configId, id -> {
            ModelRateLimitConfig cfg = ModelRateLimitConfig.from(params,
                    config.limits().modelRate());
            if (!cfg.enabled()) {
                return null;
            }
            // 延迟解析:此处才取当前生效的估算器(估算器插件后于本插件激活也不影响)。
            return new ModelRateLimiter(id, cfg, config.limits().modelRate(), estimatorSupplier.get());
        }));
    }

    /** 所有已建限流器的运行态快照(config.get 透出 P2)。 */
    public List<ModelRateLimiter.Snapshot> snapshots() {
        List<ModelRateLimiter.Snapshot> out = new ArrayList<>();
        limiters.forEach((id, l) -> {
            if (l != null) {
                out.add(l.snapshot());
            }
        });
        out.sort(java.util.Comparator.comparing(ModelRateLimiter.Snapshot::configId));
        return out;
    }
}
