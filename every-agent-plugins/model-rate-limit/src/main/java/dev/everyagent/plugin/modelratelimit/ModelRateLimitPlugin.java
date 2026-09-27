package dev.everyagent.plugin.modelratelimit;

import dev.everyagent.plugin.api.EveryAgentPlugin;
import dev.everyagent.plugin.api.WorkerPluginContext;
import dev.everyagent.worker.config.WorkerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 模型限流插件入口。
 *
 * <p>步骤 4：搬迁限流逻辑到此插件模块。activate() 中注册：
 * <ul>
 *   <li>{@link BuiltinTokenEstimator} — Token 估算器 SPI 实现（经 registerTokenEstimator）；</li>
 *   <li>{@link TokenCalibrationAdvisor.Provider} — Token 校准 Advisor 提供者；</li>
 *   <li>{@link RateLimitNode} — 模型请求洋葱链限流节点。</li>
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
        WorkerProperties props = ctx.getService(WorkerProperties.class);

        // 1. 创建 BuiltinTokenEstimator（Token 估算器 SPI 实现）
        BuiltinTokenEstimator estimator = new BuiltinTokenEstimator(props);
        ctx.registerTokenEstimator(estimator);
        log.info("[model-rate-limit] 已注册 BuiltinTokenEstimator");

        // 2. 注册 TokenCalibrationAdvisor.Provider（经 AdvisorContext.configId() 获取 configId）
        ctx.registerAdvisorProvider(new TokenCalibrationAdvisor.Provider(estimator));
        log.info("[model-rate-limit] 已注册 TokenCalibrationAdvisor.Provider");

        // 3. 创建 ModelRateLimiterRegistry + RateLimitNode（洋葱链限流节点）
        ModelRateLimiterRegistry registry = new ModelRateLimiterRegistry(props, estimator);
        ctx.registerModelRequestNode(new RateLimitNode(registry));
        log.info("[model-rate-limit] 已注册 RateLimitNode");
    }
}
