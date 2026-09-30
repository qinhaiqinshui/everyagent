package dev.everyagent.plugin.transientretry;

import dev.everyagent.plugin.api.config.WorkerConfig;
import dev.everyagent.plugin.api.agent.AgentContext;
import dev.everyagent.plugin.api.spi.AdvisorContext;
import dev.everyagent.plugin.api.spi.AdvisorProvider;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;

/**
 * {@link TransientErrorRetryAdvisor} 适配器。
 *
 * <p>order = {@link ToolCallingAdvisor#DEFAULT_ORDER} + 200（工具循环最内层，紧贴模型 HTTP 调用）。
 * 每 run 新建实例。
 */
public class TransientErrorRetryAdvisorProvider implements AdvisorProvider {

    private final WorkerConfig config;

    public TransientErrorRetryAdvisorProvider(WorkerConfig config) {
        this.config = config;
    }

    @Override
    public String pluginId() {
        return "builtin.transient-error-retry";
    }

    @Override
    public int order() {
        return ToolCallingAdvisor.DEFAULT_ORDER + 200;
    }

    @Override
    public Advisor create(AdvisorContext ctx) {
        AgentContext a = ctx.agentEntity();
        return new TransientErrorRetryAdvisor(a, config.retry());
    }
}
