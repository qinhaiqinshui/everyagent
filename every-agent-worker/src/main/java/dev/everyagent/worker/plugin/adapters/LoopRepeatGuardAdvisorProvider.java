package dev.everyagent.worker.plugin.adapters;

import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.plugin.AdvisorContextImpl;
import dev.everyagent.plugin.api.spi.AdvisorContext;
import dev.everyagent.plugin.api.spi.AdvisorProvider;
import dev.everyagent.worker.task.AgentEntity;
import dev.everyagent.worker.task.LoopRepeatGuardAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;

/**
 * {@link LoopRepeatGuardAdvisor} 适配器。
 *
 * <p>order = {@link ToolCallingAdvisor#DEFAULT_ORDER}（继承 WorkerToolEventAdvisor），
 * scope = BOTH（主/子 agent 同挂）。每 run 新建实例（守卫计数随实例物化隔离）。
 * 需要 {@link ToolCallingManager}（从 ctx 获取）和 maxRepeatedToolRounds（从 WorkerProperties 获取）。
 */
public class LoopRepeatGuardAdvisorProvider implements AdvisorProvider {

    private final WorkerProperties props;

    public LoopRepeatGuardAdvisorProvider(WorkerProperties props) {
        this.props = props;
    }

    @Override
    public String pluginId() {
        return "builtin.loop-repeat-guard";
    }

    @Override
    public Scope scope() {
        return Scope.BOTH;
    }

    @Override
    public int order() {
        return ToolCallingAdvisor.DEFAULT_ORDER;
    }

    @Override
    public Advisor create(AdvisorContext ctx) {
        AgentEntity a = ((AdvisorContextImpl) ctx).agentEntity();
        return new LoopRepeatGuardAdvisor(
                ctx.toolCallingManager(),
                a,
                props.getLimits().getMaxRepeatedToolRounds());
    }
}
