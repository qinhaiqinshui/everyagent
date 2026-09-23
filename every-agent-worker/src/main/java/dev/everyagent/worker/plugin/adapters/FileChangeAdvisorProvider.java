package dev.everyagent.worker.plugin.adapters;

import dev.everyagent.worker.plugin.AdvisorContextImpl;
import dev.everyagent.worker.plugin.spi.AdvisorContext;
import dev.everyagent.worker.plugin.spi.AdvisorProvider;
import dev.everyagent.worker.task.AgentEntity;
import dev.everyagent.worker.task.FileChangeAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.core.Ordered;

/**
 * {@link FileChangeAdvisor} 适配器。
 *
 * <p>order = {@link Ordered#HIGHEST_PRECEDENCE} + 301，scope = BOTH（主/子 agent 同挂）。
 * 每 run 新建实例。
 */
public class FileChangeAdvisorProvider implements AdvisorProvider {

    @Override
    public String pluginId() {
        return "builtin.file-change";
    }

    @Override
    public Scope scope() {
        return Scope.BOTH;
    }

    @Override
    public int order() {
        return Ordered.HIGHEST_PRECEDENCE + 301;
    }

    @Override
    public Advisor create(AdvisorContext ctx) {
        AgentEntity a = ((AdvisorContextImpl) ctx).agentEntity();
        return new FileChangeAdvisor(a);
    }
}
