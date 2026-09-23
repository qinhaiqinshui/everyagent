package dev.everyagent.worker.plugin.adapters;

import dev.everyagent.worker.modules.GitService;
import dev.everyagent.worker.plugin.AdvisorContextImpl;
import dev.everyagent.worker.plugin.spi.AdvisorContext;
import dev.everyagent.worker.plugin.spi.AdvisorProvider;
import dev.everyagent.worker.task.AgentEntity;
import dev.everyagent.worker.task.GitAutoSyncAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.core.Ordered;

/**
 * {@link GitAutoSyncAdvisor} 适配器。
 *
 * <p>order = {@link Ordered#HIGHEST_PRECEDENCE} + 140，scope = MAIN（仅主 agent）。
 * 每 run 新建实例（per-request 状态：本轮是否自动同步随实例物化隔离）。
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
    public Scope scope() {
        return Scope.MAIN;
    }

    @Override
    public int order() {
        return Ordered.HIGHEST_PRECEDENCE + 140;
    }

    @Override
    public Advisor create(AdvisorContext ctx) {
        AgentEntity a = ((AdvisorContextImpl) ctx).agentEntity();
        return new GitAutoSyncAdvisor(a, gitService);
    }
}
