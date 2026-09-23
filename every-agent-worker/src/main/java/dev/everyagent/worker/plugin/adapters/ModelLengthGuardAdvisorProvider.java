package dev.everyagent.worker.plugin.adapters;

import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.plugin.AdvisorContextImpl;
import dev.everyagent.plugin.api.spi.AdvisorContext;
import dev.everyagent.plugin.api.spi.AdvisorProvider;
import dev.everyagent.worker.task.AgentEntity;
import dev.everyagent.worker.task.ModelLengthGuardAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;

/**
 * {@link ModelLengthGuardAdvisor} 适配器。
 *
 * <p>order = {@link ToolCallingAdvisor#DEFAULT_ORDER} + 300（瞬时错误重试内侧、上下文压缩外侧），
 * scope = BOTH（主/子 agent 同挂）。每 run 新建实例。
 */
public class ModelLengthGuardAdvisorProvider implements AdvisorProvider {

    private final WorkerProperties props;

    public ModelLengthGuardAdvisorProvider(WorkerProperties props) {
        this.props = props;
    }

    @Override
    public String pluginId() {
        return "builtin.model-length-guard";
    }

    @Override
    public Scope scope() {
        return Scope.BOTH;
    }

    @Override
    public int order() {
        return ToolCallingAdvisor.DEFAULT_ORDER + 300;
    }

    @Override
    public Advisor create(AdvisorContext ctx) {
        AgentEntity a = ((AdvisorContextImpl) ctx).agentEntity();
        return new ModelLengthGuardAdvisor(a, props);
    }
}
