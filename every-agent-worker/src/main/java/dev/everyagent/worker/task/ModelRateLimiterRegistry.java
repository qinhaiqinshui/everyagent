package dev.everyagent.worker.task;

import dev.everyagent.worker.config.WorkerProperties;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型限流器注册表(docs/design-model-rate-limit.md §6.1):按 configId 单例化
 * {@link ModelRateLimiter}(跨任务/跨 agent 共享,厂商 rpm/tpm 是账户级),
 * 并持有 {@link ModelRateStateStore} 做估算系数持久化。
 *
 * <p>限流参数从该 configId 的 {@code worker.models[].params} 解析;全缺省时
 * {@link #of} 返回 empty(不限流,现状兼容)。参数在首次创建时定格(配置变更后重启生效,
 * 与 worker 其它配置一致)。
 */
@Component
public class ModelRateLimiterRegistry {

    private final WorkerProperties props;
    private final ModelRateStateStore stateStore;
    private final Map<String, ModelRateLimiter> limiters = new ConcurrentHashMap<>();

    public ModelRateLimiterRegistry(WorkerProperties props) {
        this.props = props;
        this.stateStore = new ModelRateStateStore(props);
    }

    /** 获取 configId 对应限流器;最终限流值全为 0 → empty(装饰器直通)。 */
    public Optional<ModelRateLimiter> of(String configId, JsonNode params) {
        return Optional.ofNullable(limiters.computeIfAbsent(configId, id -> {
            ModelRateLimitConfig cfg = ModelRateLimitConfig.from(params,
                    props.getLimits().getModelRate());
            if (!cfg.enabled()) {
                return null;
            }
            double persisted = stateStore.factorOf(id);
            return new ModelRateLimiter(id, cfg, props.getLimits().getModelRate(), persisted,
                    (factor, samples) -> stateStore.record(id, factor, samples));
        }));
    }

    @PreDestroy
    void shutdown() {
        stateStore.close();
    }
}