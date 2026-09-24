package dev.everyagent.worker.agent;

import dev.everyagent.plugin.api.agent.AgentContext;
import dev.everyagent.worker.task.AgentEntity;
import dev.everyagent.worker.task.AgentRunner;
import dev.everyagent.worker.task.SubAgentManager;
import dev.everyagent.worker.task.TaskEntry;
import org.springframework.stereotype.Component;

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
        return subAgentManager.run((TaskEntry) ctx, input, title, reuseAgentId);
    }

    @Override
    public String waitFor(AgentContext ctx, String agentId, Long timeoutMs) throws InterruptedException {
        return subAgentManager.waitFor((TaskEntry) ctx, agentId, timeoutMs);
    }

    @Override
    public String stop(AgentContext ctx, String agentId) {
        return subAgentManager.stop((TaskEntry) ctx, agentId);
    }

    @Override
    public void stopAll(AgentContext ctx) {
        subAgentManager.stopAll((TaskEntry) ctx);
    }

    @Override
    public String list(AgentContext ctx) throws InterruptedException {
        return subAgentManager.list((TaskEntry) ctx);
    }
}
