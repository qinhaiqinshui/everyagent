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
 *   <li>TaskLifecycleNode → TaskLifecycleRegistry（spawned.await）</li>
 * </ul>
 *
 * <p>task.agents RPC 已收回 task 域（worker TaskManager.rpcTaskAgents），本插件不再注册该 RPC。
 */
public class SubAgentPlugin implements EveryAgentPlugin {

    @Override
    public String id() { return "subagent"; }

    @Override
    public void activate(WorkerPluginContext ctx) {
        // SubAgentManager 是插件内部类,不在 Spring 容器中(插件经 URLClassLoader 加载,
        // Spring 组件扫描不可见)。在此手动构造;零服务依赖——全部取数经 ExecContext
        // 槽位(§8.2),services().task() / agentFactory() / interaction() 均不再使用。
        SubAgentManager subAgentManager = new SubAgentManager();

        // 1. 注册工具提供者(工具入口绑 ToolContext.execution() 整个上下文句柄)
        ctx.registerToolProvider(new SubAgentToolsProvider(subAgentManager));

        // 2. 注册生命周期节点
        // 任务收口前等待全部子 agent（超时级联停）
        ctx.registerTaskLifecycleNode(new SubAgentSpawnedAwaitNode(subAgentManager));

        // 3. 注册 Skill 贡献者
        WorkerConfig props = ctx.services().config();
        SubAgentSkillContributor skillContributor = new SubAgentSkillContributor(props);
        ctx.registerSkillContributor(skillContributor);
        skillContributor.init();
    }
}