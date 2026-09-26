package dev.everyagent.plugin.subagent;

import dev.everyagent.plugin.api.EveryAgentPlugin;
import dev.everyagent.plugin.api.WorkerPluginContext;
import dev.everyagent.worker.agent.AgentService;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.task.TaskStore;

/**
 * subagent 插件入口。
 *
 * <p>activate() 中注册：
 * <ul>
 *   <li>SubAgentToolsProvider → ToolProviderRegistry</li>
 *   <li>SubAgentSkillContributor → SkillContributorRegistry</li>
 *   <li>task.agents RPC → RpcDispatcher</li>
 *   <li>3 个 TaskLifecycleNode → TaskLifecycleRegistry（ledger track/untrack/persist）</li>
 * </ul>
 */
public class SubAgentPlugin implements EveryAgentPlugin {

    @Override
    public String id() { return "subagent"; }

    @Override
    public void activate(WorkerPluginContext ctx) {
        AgentService agentService = ctx.getService(AgentService.class);
        TaskStore store = ctx.getService(TaskStore.class);
        WorkerProperties props = ctx.getService(WorkerProperties.class);

        // 1. 注册工具提供者
        ctx.registerToolProvider(new SubAgentToolsProvider(agentService));

        // 2. 台账实例（per-task 事件投影 + agents.json 读写）
        SubAgentLedger ledger = new SubAgentLedger(store);

        // 3. 注册 task.agents RPC
        SubAgentRpcHandler rpcHandler = new SubAgentRpcHandler(ledger, store);
        ctx.registerRpcMethod("task.agents", rpcCtx -> rpcHandler.handleTaskAgents(
                (dev.everyagent.worker.rpc.RpcContext) rpcCtx));

        // 4. 注册生命周期节点
        ctx.registerTaskLifecycleNode(new SubAgentLedgerTrackNode(ledger));
        ctx.registerTaskLifecycleNode(new SubAgentLedgerUntrackNode(ledger));
        // ledger.persist 以 "worker" 注册，绕过临界段限制（等价迁移）
        // 注意：registerTaskLifecycleNode 用 pluginId 注册，这里需要保持 "worker" 标签
        ctx.getService(dev.everyagent.worker.plugin.registry.TaskLifecycleRegistry.class)
                .register(new SubAgentLedgerPersistNode(ledger), "worker");

        // 5. 注册 Skill 贡献者
        SubAgentSkillContributor skillContributor = new SubAgentSkillContributor(props);
        ctx.registerSkillContributor(skillContributor);
        skillContributor.init();
    }
}
