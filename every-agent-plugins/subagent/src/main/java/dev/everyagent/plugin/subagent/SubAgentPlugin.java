package dev.everyagent.plugin.subagent;

import dev.everyagent.plugin.api.EveryAgentPlugin;
import dev.everyagent.plugin.api.WorkerPluginContext;
import dev.everyagent.plugin.api.config.WorkerConfig;

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
        // SubAgentManager 是插件内部类,不在 Spring 容器中(插件经 URLClassLoader 加载,
        // Spring 组件扫描不可见)。在此手动构造,依赖从 worker 服务获取。
        SubAgentManager subAgentManager = new SubAgentManager(
                ctx.services().agentFactory(),
                ctx.services().interaction(),
                ctx.services().task());

        // 1. 注册工具提供者
        ctx.registerToolProvider(new SubAgentToolsProvider(subAgentManager));

        // 2. 台账实例（per-task 事件投影 + agents.json 读写）
        SubAgentLedger ledger = new SubAgentLedger(ctx.services().store());
        // 注入 ledger 到 SubAgentManager,使 list_agents 工具能读取台账
        // (含从磁盘 agents.json 恢复的历史已完成子 agent)
        subAgentManager.setLedger(ledger);

        // 3. 注册 task.agents RPC
        SubAgentRpcHandler rpcHandler = new SubAgentRpcHandler(ledger, ctx.services().store());
        ctx.registerRpcMethod("task.agents", rpcHandler::handleTaskAgents);

        // 4. 注册生命周期节点
        ctx.registerTaskLifecycleNode(new SubAgentLedgerTrackNode(ledger));
        ctx.registerTaskLifecycleNode(new SubAgentLedgerUntrackNode(ledger));
        ctx.registerTaskLifecycleNode(new SubAgentLedgerPersistNode(ledger));
        // 任务收口前等待全部子 agent（超时级联停）
        ctx.registerTaskLifecycleNode(new SubAgentSpawnedAwaitNode(subAgentManager));

        // 5. 注册 Skill 贡献者
        WorkerConfig props = ctx.services().config();
        SubAgentSkillContributor skillContributor = new SubAgentSkillContributor(props);
        ctx.registerSkillContributor(skillContributor);
        skillContributor.init();
    }
}
