package dev.everyagent.plugin.subagent;

import dev.everyagent.worker.agent.AgentService;
import dev.everyagent.worker.plugin.registry.SkillContributorRegistry;
import dev.everyagent.worker.plugin.registry.TaskLifecycleRegistry;
import dev.everyagent.worker.plugin.registry.ToolProviderRegistry;
import dev.everyagent.worker.rpc.RpcDispatcher;
import dev.everyagent.worker.task.TaskStore;
import org.springframework.stereotype.Component;

/**
 * subagent 插件注册入口（@Component，Spring 启动时构造注册）。
 *
 * <p>注册内容（Phase 4 迁移）：
 * <ul>
 *   <li>SubAgentToolsProvider → ToolProviderRegistry（4 个 @Tool）</li>
 *   <li>SubAgentSkillContributor → SkillContributorRegistry（agent-dispatch skill，由 @Component 自注册）</li>
 *   <li>task.agents RPC → RpcDispatcher</li>
 *   <li>3 个 TaskLifecycleNode → TaskLifecycleRegistry（ledger track/untrack/persist）</li>
 * </ul>
 *
 * <p>注意：ledger.persist 节点 order=600 在临界段 [420,850] 内，
 * 以 pluginId="worker" 注册以等价替代原内置 LedgerPersistNode。
 */
@Component
public class SubAgentRegistrar {

    public SubAgentRegistrar(
            ToolProviderRegistry toolRegistry,
            TaskLifecycleRegistry lifecycleRegistry,
            RpcDispatcher rpcDispatcher,
            AgentService agentService,
            TaskStore store) {

        // 1. 注册工具提供者
        toolRegistry.register(new SubAgentToolsProvider(agentService));

        // 2. 台账实例（per-task 事件投影 + agents.json 读写）
        SubAgentLedger ledger = new SubAgentLedger(store);

        // 3. 注册 task.agents RPC
        SubAgentRpcHandler rpcHandler = new SubAgentRpcHandler(ledger, store);
        rpcDispatcher.register("task.agents", rpcHandler::handleTaskAgents);

        // 4. 注册生命周期节点
        lifecycleRegistry.register(new SubAgentLedgerTrackNode(ledger), "subagent");
        lifecycleRegistry.register(new SubAgentLedgerUntrackNode(ledger), "subagent");
        // ledger.persist 以 "worker" 注册，绕过临界段限制（等价迁移）
        lifecycleRegistry.register(new SubAgentLedgerPersistNode(ledger), "worker");
    }
}
