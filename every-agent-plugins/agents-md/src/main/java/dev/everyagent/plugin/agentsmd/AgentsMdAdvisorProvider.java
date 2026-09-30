package dev.everyagent.plugin.agentsmd;

import dev.everyagent.plugin.api.spi.AdvisorContext;
import dev.everyagent.plugin.api.spi.AdvisorProvider;
import dev.everyagent.worker.plugin.AdvisorContextImpl;
import dev.everyagent.worker.agent.AgentEntity;
import dev.everyagent.worker.task.TaskEntry;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.core.Ordered;

/**
 * {@link AgentsMdAdvisor} 适配器。
 *
 * <p>order = {@link Ordered#HIGHEST_PRECEDENCE} + 60。每 run 新建实例。
 */
public class AgentsMdAdvisorProvider implements AdvisorProvider {

    @Override
    public String pluginId() {
        return "builtin.agents-md";
    }

    @Override
    public int order() {
        return Ordered.HIGHEST_PRECEDENCE + 60;
    }

    @Override
    public Advisor create(AdvisorContext ctx) {
        AgentEntity a = (AgentEntity) ((AdvisorContextImpl) ctx).agentEntity();
        TaskEntry t = (TaskEntry) a.properties.get("taskEntry");
        return new AgentsMdAdvisor(t.workspaceRoot);
    }
}
