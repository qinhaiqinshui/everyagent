package dev.everyagent.worker.plugin.adapters;

import dev.everyagent.plugin.api.spi.AdvisorContext;
import dev.everyagent.plugin.api.spi.AdvisorProvider;
import dev.everyagent.worker.agent.AgentEntity;
import dev.everyagent.worker.attachment.FileAttachmentAdvisor;
import dev.everyagent.worker.plugin.AdvisorContextImpl;
import dev.everyagent.plugin.api.execution.ExecContext;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.core.Ordered;

/**
 * {@link FileAttachmentAdvisor} 适配器。
 *
 * <p>order = {@link Ordered#HIGHEST_PRECEDENCE} + 160（紧跟 SlashTokenResolveAdvisor 之后，
 * 只改写末位 user 消息、不动 system 区）。
 * 每 run 新建实例；子 agent 无 taskEntry 属性时 Advisor 恒放行。
 */
public class FileAttachmentAdvisorProvider implements AdvisorProvider {

    @Override
    public String pluginId() {
        return "builtin.file-attachment";
    }

    @Override
    public int order() {
        return Ordered.HIGHEST_PRECEDENCE + 160;
    }

    @Override
    public Advisor create(AdvisorContext ctx) {
        AgentEntity a = (AgentEntity) ((AdvisorContextImpl) ctx).agentEntity();
        ExecContext exec = a != null ? a.execution() : null;
        return new FileAttachmentAdvisor(exec);
    }
}
