package dev.everyagent.plugin.modelratelimit;

import dev.everyagent.plugin.api.EveryAgentPlugin;
import dev.everyagent.plugin.api.WorkerPluginContext;
import dev.everyagent.plugin.api.config.WorkerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 模型限流插件入口。
 *
 * <p>activate() 中注册：
 * <ul>
 *   <li>{@link BuiltinTokenEstimator} — Token 估算器 SPI 实现（经 registerTokenEstimator）；</li>
 *   <li>{@link TokenCalibrationAdvisor.Provider} — Token 校准 Advisor 提供者；</li>
 *   <li>{@link RateLimitAdvisorProvider} — 限流 Advisor 提供者（替代原 RateLimitNode 洋葱链节点）。</li>
 * </ul>
 * 三者共享同一 {@link BuiltinTokenEstimator} 实例（校准系数共享）。
 */
public class ModelRateLimitPlugin implements EveryAgentPlugin {

    private static final Logger log = LoggerFactory.getLogger(ModelRateLimitPlugin.class);

    @Override
    public String id() {
        return "model-rate-limit";
    }

    @Override
    public void activate(WorkerPluginContext ctx) throws Exception {
        WorkerConfig config = ctx.services().config();

        // 1. 创建 BuiltinTokenEstimator
        BuiltinTokenEstimator estimator = new BuiltinTokenEstimator(config);
        ctx.registerTokenEstimator(estimator);
        log.info("[model-rate-limit] 已注册 BuiltinTokenEstimator");

        // 2. 注册 TokenCalibrationAdvisor.Provider
        ctx.registerAdvisorProvider(new TokenCalibrationAdvisor.Provider(estimator));
        log.info("[model-rate-limit] 已注册 TokenCalibrationAdvisor.Provider");

        // 3. 创建 ModelRateLimiterRegistry + RateLimitAdvisorProvider（替代原 RateLimitNode）
        ModelRateLimiterRegistry registry = new ModelRateLimiterRegistry(config, estimator);
        ctx.registerAdvisorProvider(new RateLimitAdvisorProvider(registry));
        log.info("[model-rate-limit] 已注册 RateLimitAdvisorProvider");
    }
}
