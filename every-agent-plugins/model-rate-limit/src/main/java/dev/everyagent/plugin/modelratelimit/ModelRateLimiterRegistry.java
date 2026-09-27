package dev.everyagent.plugin.modelratelimit;

import dev.everyagent.plugin.api.spi.TokenEstimator;
import dev.everyagent.worker.config.WorkerProperties;
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
 * <p>token 估算系数的持久化由 {@link TokenEstimator} 实现自行管理,
 * 本注册表不再持有 {@code ModelRateStateStore}。
 */
public class ModelRateLimiterRegistry {

    private final WorkerProperties props;
    private final TokenEstimator estimator;
    private final Map<String, ModelRateLimiter> limiters = new ConcurrentHashMap<>();

    public ModelRateLimiterRegistry(WorkerProperties props, TokenEstimator estimator) {
        this.props = props;
        this.estimator = estimator;
    }

    /** 获取 configId 对应限流器;最终限流值全为 0 → empty(装饰器直通)。 */
    public Optional<ModelRateLimiter> of(String configId, JsonNode params) {
        return Optional.ofNullable(limiters.computeIfAbsent(configId, id -> {
            ModelRateLimitConfig cfg = ModelRateLimitConfig.from(params,
                    props.getLimits().getModelRate());
            if (!cfg.enabled()) {
                return null;
            }
            return new ModelRateLimiter(id, cfg, props.getLimits().getModelRate(), estimator);
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
