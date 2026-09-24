package dev.everyagent.worker.agent;

import dev.everyagent.plugin.api.agent.AgentContext;
import dev.everyagent.worker.task.AgentEntity;
import dev.everyagent.worker.task.AgentRunner;
import dev.everyagent.worker.task.SubAgentManager;
import dev.everyagent.worker.task.TaskEntry;
import org.springframework.stereotype.Component;

/**
 * AgentService 默认实现：委托 AgentRunner（run）和 SubAgentManager（spawn/waitFor/stop/stopAll/list）。
 * Phase 2 过渡期：SubAgentManager 仍直接依赖 TaskEntry，此处做 AgentContext → TaskEntry 的强转桥接。
 * Phase 4+ SubAgentManager 逻辑下沉到 agent 层后，强转消失。
 */
@Component
public class AgentServiceImpl implements AgentService {

    private final AgentRunner runner;
    private final SubAgentManager subAgentManager;

    public AgentServiceImpl(AgentRunner runner, SubAgentManager subAgentManager) {
        this.runner = runner;
        this.subAgentManager = subAgentManager;
    }

    @Override
    public void run(AgentEntity a) throws InterruptedException {
        runner.run(a);
    }

    @Override
    public String spawn(AgentContext ctx, String input, String title, String reuseAgentId) throws InterruptedException {
        TaskEntry taskEntry = (TaskEntry) ctx;
        return subAgentManager.run(taskEntry, input, title, reuseAgentId);
    }

    @Override
    public String waitFor(AgentContext ctx, String agentId, Long timeoutMs) throws InterruptedException {
        TaskEntry taskEntry = (TaskEntry) ctx;
        return subAgentManager.waitFor(taskEntry, agentId, timeoutMs);
    }

    @Override
    public String stop(AgentContext ctx, String agentId) {
        TaskEntry taskEntry = (TaskEntry) ctx;
        return subAgentManager.stop(taskEntry, agentId);
    }

    @Override
    public void stopAll(AgentContext ctx) {
        TaskEntry taskEntry = (TaskEntry) ctx;
        subAgentManager.stopAll(taskEntry);
    }

    @Override
    public String list(AgentContext ctx) throws InterruptedException {
        TaskEntry taskEntry = (TaskEntry) ctx;
        return subAgentManager.list(taskEntry);
    }
}
