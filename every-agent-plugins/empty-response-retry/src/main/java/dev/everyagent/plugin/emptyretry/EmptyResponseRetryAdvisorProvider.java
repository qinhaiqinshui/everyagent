package dev.everyagent.plugin.emptyretry;

import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.plugin.AdvisorContextImpl;
import dev.everyagent.plugin.api.spi.AdvisorContext;
import dev.everyagent.plugin.api.spi.AdvisorProvider;
import dev.everyagent.worker.agent.AgentEntity;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;

/**
 * {@link EmptyResponseRetryAdvisor} 适配器。
 *
 * <p>order = {@link ToolCallingAdvisor#DEFAULT_ORDER} + 100（工具循环内侧第一圈）。
 * 每 run 新建实例。
 */
public class EmptyResponseRetryAdvisorProvider implements AdvisorProvider {

    private final WorkerProperties props;

    public EmptyResponseRetryAdvisorProvider(WorkerProperties props) {
        this.props = props;
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
        AgentEntity a = ((AdvisorContextImpl) ctx).agentEntity();
        return new EmptyResponseRetryAdvisor(a, props.getRetry());
    }
}
