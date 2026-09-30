package dev.everyagent.plugin.filechange;

import dev.everyagent.plugin.api.agent.AgentContext;
import dev.everyagent.plugin.api.spi.AdvisorContext;
import dev.everyagent.plugin.api.spi.AdvisorProvider;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.core.Ordered;

/**
 * {@link FileChangeAdvisor} 适配器。
 *
 * <p>order = {@link Ordered#HIGHEST_PRECEDENCE} + 301。每 run 新建实例。
 */
public class FileChangeAdvisorProvider implements AdvisorProvider {

    @Override
    public String pluginId() {
        return "file-change";
    }

    @Override
    public int order() {
        return Ordered.HIGHEST_PRECEDENCE + 301;
    }

    @Override
    public Advisor create(AdvisorContext ctx) {
        AgentContext a = ctx.agentEntity();
        return new FileChangeAdvisor(a);
    }
}
