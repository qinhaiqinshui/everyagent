package dev.everyagent.plugin.git;

import dev.everyagent.plugin.api.agent.AgentContext;
import dev.everyagent.plugin.api.spi.AdvisorContext;
import dev.everyagent.plugin.api.spi.AdvisorProvider;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.core.Ordered;

/**
 * {@link GitAutoSyncAdvisor} 适配器。
 *
 * <p>order = {@link Ordered#HIGHEST_PRECEDENCE} + 140。每 run 新建实例（per-request 状态：本轮是否自动同步随实例物化隔离）。
 */
public class GitAutoSyncAdvisorProvider implements AdvisorProvider {

    private final GitService gitService;

    public GitAutoSyncAdvisorProvider(GitService gitService) {
        this.gitService = gitService;
    }

    @Override
    public String pluginId() {
        return "builtin.git-auto-sync";
    }

    @Override
    public int order() {
        return Ordered.HIGHEST_PRECEDENCE + 140;
    }

    /**
     * 仅任务主体挂载：{@code git.auto_sync} slash token 语义属任务域（SlashTaskScopeStore
     * 以 taskId 分区），挂到非任务主体（如工作流 runtime）会在无任务上下文处误触发自动同步。
     */
    @Override
    public boolean appliesTo(AdvisorContext ctx) {
        if (!ctx.isTaskSubject()) {
            return false;
        }
        return true;
    }

    @Override
    public Advisor create(AdvisorContext ctx) {
        AgentContext a = ctx.agentEntity();
        return new GitAutoSyncAdvisor(a, gitService);
    }
}
