package dev.everyagent.worker.plugin.adapters;

import dev.everyagent.worker.plugin.AdvisorContextImpl;
import dev.everyagent.plugin.api.spi.AdvisorContext;
import dev.everyagent.plugin.api.spi.AdvisorProvider;
import dev.everyagent.worker.agent.AgentEntity;
import dev.everyagent.worker.task.WorkerToolEventAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;

/**
 * {@link WorkerToolEventAdvisor} 适配器（worker 事件发射）。
 *
 * <p>order = {@link ToolCallingAdvisor#DEFAULT_ORDER}（主/子 agent 同挂）。
 * 每 run 新建实例（持有 per-run {@link AgentEntity}）。
 * 从 {@link AdvisorContext#toolCallingManager()} 获取（可能已被工厂装饰了
 * {@code LoopRepeatGuardToolManager} 的）ToolCallingManager。
 *
 * <p>解耦前 {@code WorkerToolEventAdvisor} 没有 independent Provider，仅通过被
 * {@code LoopRepeatGuardAdvisor} 继承间接进入链。解耦后此 Provider 独立注册，
 * 守卫改由 {@code AgentClientFactory} 在组装入口装饰 TCM 承担。
 */
public class WorkerToolEventAdvisorProvider implements AdvisorProvider {

    @Override
    public String pluginId() {
        return "builtin.worker-tool-event";
    }

    @Override
    public int order() {
        return ToolCallingAdvisor.DEFAULT_ORDER;
    }

    @Override
    public Advisor create(AdvisorContext ctx) {
        AgentEntity a = (AgentEntity) ((AdvisorContextImpl) ctx).agentEntity();
        return new WorkerToolEventAdvisor(ctx.toolCallingManager(), a);
    }
}
