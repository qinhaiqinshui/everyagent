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
 *   <li>{@link RateLimitAdvisorProvider} — 限流 Advisor 提供者（替代原 RateLimitNode 洋葱链节点）。</li>
 * </ul>
 * 本插件<b>只做限流</b>，不再注册 token 估算器（已拆到独立插件 {@code token-estimator}）；
 * 限流所需的估算器经 SPI 消费——即 {@code ctx.services().tokenEstimator()}，经
 * {@link ModelRateLimiterRegistry} 延迟解析（见该类 javadoc）。
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

        // 创建 ModelRateLimiterRegistry + RateLimitAdvisorProvider（替代原 RateLimitNode）。
        // 估算器经 SPI 延迟解析:内置插件按目录名激活,本插件排在 token-estimator 之前,
        // 直接用 Supplier 取「当前生效实例」,避免固化到 activate 期间的旧值。
        ModelRateLimiterRegistry registry = new ModelRateLimiterRegistry(config, ctx.services()::tokenEstimator);
        ctx.registerAdvisorProvider(new RateLimitAdvisorProvider(registry));
        log.info("[model-rate-limit] 已注册 RateLimitAdvisorProvider");
    }
}
