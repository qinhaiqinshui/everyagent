package dev.everyagent.worker.plugin.adapters;

import dev.everyagent.worker.plugin.AdvisorContextImpl;
import dev.everyagent.worker.plugin.spi.AdvisorContext;
import dev.everyagent.worker.plugin.spi.AdvisorProvider;
import dev.everyagent.worker.slash.SlashTokenHandler;
import dev.everyagent.worker.task.AgentEntity;
import dev.everyagent.worker.task.DialogInsertAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;

/**
 * {@link DialogInsertAdvisor} 适配器。
 *
 * <p>order = {@link ToolCallingAdvisor#DEFAULT_ORDER} + 30（= HIGHEST+330），scope = BOTH
 * （主/子 agent 同挂；子 agent 按 kind 旁路不接收任务队列用户输入）。
 * 每 run 新建实例。
 */
public class DialogInsertAdvisorProvider implements AdvisorProvider {

    private final SlashTokenHandler slashTokenHandler;

    public DialogInsertAdvisorProvider(SlashTokenHandler slashTokenHandler) {
        this.slashTokenHandler = slashTokenHandler;
    }

    @Override
    public String pluginId() {
        return "builtin.dialog-insert";
    }

    @Override
    public Scope scope() {
        return Scope.BOTH;
    }

    @Override
    public int order() {
        return ToolCallingAdvisor.DEFAULT_ORDER + 30;
    }

    @Override
    public Advisor create(AdvisorContext ctx) {
        AgentEntity a = ((AdvisorContextImpl) ctx).agentEntity();
        return new DialogInsertAdvisor(a, slashTokenHandler);
    }
}
