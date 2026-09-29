package dev.everyagent.plugin.transientretry;

import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.plugin.AdvisorContextImpl;
import dev.everyagent.plugin.api.spi.AdvisorContext;
import dev.everyagent.plugin.api.spi.AdvisorProvider;
import dev.everyagent.worker.task.AgentEntity;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;

/**
 * {@link TransientErrorRetryAdvisor} 适配器。
 *
 * <p>order = {@link ToolCallingAdvisor#DEFAULT_ORDER} + 200（工具循环最内层，紧贴模型 HTTP 调用）。
 * 每 run 新建实例。
 */
public class TransientErrorRetryAdvisorProvider implements AdvisorProvider {

    private final WorkerProperties props;

    public TransientErrorRetryAdvisorProvider(WorkerProperties props) {
        this.props = props;
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
        AgentEntity a = ((AdvisorContextImpl) ctx).agentEntity();
        return new TransientErrorRetryAdvisor(a, props.getRetry());
    }
}
