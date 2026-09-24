package dev.everyagent.worker.agent;

import dev.everyagent.plugin.api.agent.AgentContext;
import dev.everyagent.worker.task.AgentEntity;

/**
 * Agent 层公共 API。主/子 agent 的调用者共用此接口。
 */
public interface AgentService {
    void run(AgentEntity a) throws InterruptedException;
    String spawn(AgentContext ctx, String input, String title, String reuseAgentId) throws InterruptedException;
    String waitFor(AgentContext ctx, String agentId, Long timeoutMs) throws InterruptedException;
    String stop(AgentContext ctx, String agentId);
    void stopAll(AgentContext ctx);
    String list(AgentContext ctx) throws InterruptedException;
}
