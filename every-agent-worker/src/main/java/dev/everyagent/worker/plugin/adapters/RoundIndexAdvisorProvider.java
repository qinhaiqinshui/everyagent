package dev.everyagent.worker.plugin.adapters;

import dev.everyagent.worker.plugin.AdvisorContextImpl;
import dev.everyagent.plugin.api.spi.AdvisorContext;
import dev.everyagent.plugin.api.spi.AdvisorProvider;
import dev.everyagent.worker.task.AgentEntity;
import dev.everyagent.worker.task.RoundIndexAdvisor;
import dev.everyagent.worker.task.RoundIndexStore;
import dev.everyagent.worker.task.TaskStore;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.core.Ordered;

/**
 * {@link RoundIndexAdvisor} 适配器。
 *
 * <p>order = {@link Ordered#HIGHEST_PRECEDENCE} + 10（主链最外层），scope = MAIN。
 * 每 run 新建实例（per-run 状态：轮次索引随实例物化隔离）。
 */
public class RoundIndexAdvisorProvider implements AdvisorProvider {

    private final TaskStore taskStore;
    private final RoundIndexStore roundIndexStore;

    public RoundIndexAdvisorProvider(TaskStore taskStore, RoundIndexStore roundIndexStore) {
        this.taskStore = taskStore;
        this.roundIndexStore = roundIndexStore;
    }

    @Override
    public String pluginId() {
        return "builtin.round-index";
    }

    @Override
    public Scope scope() {
        return Scope.MAIN;
    }

    @Override
    public int order() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    @Override
    public Advisor create(AdvisorContext ctx) {
        AgentEntity a = ((AdvisorContextImpl) ctx).agentEntity();
        return new RoundIndexAdvisor(a, taskStore, roundIndexStore);
    }
}
