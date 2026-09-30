package dev.everyagent.worker.plugin.adapters;

import dev.everyagent.worker.plugin.AdvisorContextImpl;
import dev.everyagent.plugin.api.spi.AdvisorContext;
import dev.everyagent.plugin.api.spi.AdvisorProvider;
import dev.everyagent.worker.skill.SlashTokenResolveAdvisor;
import dev.everyagent.worker.slash.SlashTokenHandler;
import dev.everyagent.worker.agent.AgentEntity;
import dev.everyagent.worker.task.TaskEntry;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.core.Ordered;

/**
 * {@link SlashTokenResolveAdvisor} 适配器。
 *
 * <p>order = {@link Ordered#HIGHEST_PRECEDENCE} + 150。
 * 每 run 新建实例。
 */
public class SlashTokenResolveAdvisorProvider implements AdvisorProvider {

    private final SlashTokenHandler slashTokenHandler;

    public SlashTokenResolveAdvisorProvider(SlashTokenHandler slashTokenHandler) {
        this.slashTokenHandler = slashTokenHandler;
    }

    @Override
    public String pluginId() {
        return "builtin.slash-token-resolve";
    }

    @Override
    public int order() {
        return Ordered.HIGHEST_PRECEDENCE + 150;
    }

    @Override
    public Advisor create(AdvisorContext ctx) {
        AgentEntity a = (AgentEntity) ((AdvisorContextImpl) ctx).agentEntity();
        TaskEntry t = (TaskEntry) a.properties.get("taskEntry");
        return new SlashTokenResolveAdvisor(slashTokenHandler, t);
    }
}
