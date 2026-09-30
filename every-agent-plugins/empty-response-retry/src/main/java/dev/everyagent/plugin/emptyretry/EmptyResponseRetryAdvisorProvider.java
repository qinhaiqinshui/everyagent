package dev.everyagent.plugin.emptyretry;

import dev.everyagent.plugin.api.config.WorkerConfig;
import dev.everyagent.plugin.api.agent.AgentContext;
import dev.everyagent.plugin.api.spi.AdvisorContext;
import dev.everyagent.plugin.api.spi.AdvisorProvider;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;

/**
 * {@link EmptyResponseRetryAdvisor} 适配器。
 *
 * <p>order = {@link ToolCallingAdvisor#DEFAULT_ORDER} + 100（工具循环内侧第一圈）。
 * 每 run 新建实例。
 */
public class EmptyResponseRetryAdvisorProvider implements AdvisorProvider {

    private final WorkerConfig config;

    public EmptyResponseRetryAdvisorProvider(WorkerConfig config) {
        this.config = config;
    }

    @Override
    public String pluginId() {
        return "builtin.empty-response-retry";
    }

    @Override
    public int order() {
        return ToolCallingAdvisor.DEFAULT_ORDER + 100;
    }

    @Override
    public Advisor create(AdvisorContext ctx) {
        AgentContext a = ctx.agentEntity();
        return new EmptyResponseRetryAdvisor(a, config.retry());
    }
}
