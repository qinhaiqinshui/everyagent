package dev.everyagent.plugin.modelratelimit;

import dev.everyagent.plugin.api.agent.AgentContext;
import dev.everyagent.plugin.api.spi.AdvisorContext;
import dev.everyagent.plugin.api.spi.AdvisorProvider;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;

/**
 * {@link RateLimitAdvisor} 的提供者。
 *
 * <p>order = {@link ToolCallingAdvisor#DEFAULT_ORDER} + 500（最内层，
 * 在 ContextCompression +400 之后）。每 run 新建实例，经 {@link AdvisorContext#agentEntity()} 获取
 * {@link AgentContext}（含 task/events/snapshot），与
 * {@link ModelRateLimiterRegistry} 一起构造 {@link RateLimitAdvisor}。
 */
public class RateLimitAdvisorProvider implements AdvisorProvider {

    private final ModelRateLimiterRegistry registry;

    public RateLimitAdvisorProvider(ModelRateLimiterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String pluginId() {
        return "builtin.rate-limit";
    }

    @Override
    public int order() {
        return ToolCallingAdvisor.DEFAULT_ORDER + 500;
    }

    @Override
    public Advisor create(AdvisorContext ctx) {
        AgentContext a = ctx.agentEntity();
        return new RateLimitAdvisor(a, registry);
    }
}
