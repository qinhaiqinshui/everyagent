package dev.everyagent.worker.plugin.adapters;

import dev.everyagent.worker.plugin.AdvisorContextImpl;
import dev.everyagent.worker.plugin.spi.AdvisorContext;
import dev.everyagent.worker.plugin.spi.AdvisorProvider;
import dev.everyagent.worker.skill.SlashTokenResolveAdvisor;
import dev.everyagent.worker.slash.SlashTokenHandler;
import dev.everyagent.worker.task.AgentEntity;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.core.Ordered;

/**
 * {@link SlashTokenResolveAdvisor} 适配器。
 *
 * <p>order = {@link Ordered#HIGHEST_PRECEDENCE} + 150，scope = MAIN（仅主 agent）。
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
    public Scope scope() {
        return Scope.MAIN;
    }

    @Override
    public int order() {
        return Ordered.HIGHEST_PRECEDENCE + 150;
    }

    @Override
    public Advisor create(AdvisorContext ctx) {
        AgentEntity a = ((AdvisorContextImpl) ctx).agentEntity();
        return new SlashTokenResolveAdvisor(slashTokenHandler, a.task);
    }
}
