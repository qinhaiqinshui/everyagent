package dev.everyagent.plugin.workflow;

import dev.everyagent.plugin.api.EveryAgentPlugin;
import dev.everyagent.plugin.api.WorkerPluginContext;
import dev.everyagent.plugin.workflow.engine.WorkflowEngine;
import dev.everyagent.plugin.workflow.rpc.WorkflowRpc;
import dev.everyagent.plugin.workflow.store.WorkflowStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * workflow 插件入口。
 *
 * <p>当前阶段（步骤 4+5+6）：模型/校验/存储层 + hidden 五工具 + 激活扫描 + <b>执行引擎</b>
 * （WorkflowRuntime/调度器/节点执行器）+ <b>RPC 面</b>（workflow.* 14 个方法）。本类 activate 做四件事：
 * <ol>
 *   <li>构造 {@link WorkflowStore} 并执行 activate 扫描（建内存索引 + 非终态实例标
 *       failed("worker 重启中断")）；</li>
 *   <li>构造 {@link WorkflowEngine}（workflow.run/cancel/decide/instance.events RPC 后端，
 *       经 {@link #engine()} 取用）；</li>
 *   <li>注册 {@link WorkflowRpc}（14 个 workflow.* 方法，
 *       契约=计划「RPC 参数契约」表）；</li>
 *   <li>注册 {@link WorkflowToolProvider}（workflow_create/get/update/delete/list 五个
 *       hidden 工具；技能包由 plugin.json 声明 {@code skills} 目录物化，不走代码注册）。</li>
 * </ol>
 */
public class WorkflowPlugin implements EveryAgentPlugin {

    private static final Logger log = LoggerFactory.getLogger(WorkflowPlugin.class);

    private volatile WorkflowEngine engine;

    @Override
    public String id() {
        return "workflow";
    }

    @Override
    public void activate(WorkerPluginContext ctx) {
        // 存储层：systemDir/workspaces/<wsId>/workflows/（与 tasks/ 平级）；
        // workspaceId→root 经注册表解析（redirect 安全）
        WorkflowStore store = new WorkflowStore(ctx.services().workspaces());

        // 激活扫描：全扫建索引 + worker 重启中断的非终态实例标 failed
        WorkflowStore.ScanReport report = store.scanOnActivate();
        log.info("[workflow] 已激活：扫描 {} 个工作区，索引 {} 定义 / {} 实例，重启中断标 failed {} 个",
                report.workspaces(), report.definitions(), report.instances(), report.interrupted());

        // 执行引擎：调度器跑虚拟线程（start 异步开跑，不阻塞 RPC 线程）
        engine = new WorkflowEngine(store, ctx.services());

        // RPC 面：workflow.* 14 个方法（契约=计划「RPC 参数契约」表）
        new WorkflowRpc(store, engine, ctx.services()).registerAll(ctx);

        // hidden 五工具（模型经 skill/workflow-tools/skill.md 认知后按名调用）
        ctx.registerToolProvider(new WorkflowToolProvider(store));
    }

    /** 执行引擎（RPC 层与本插件内部共用）。 */
    public WorkflowEngine engine() {
        WorkflowEngine e = engine;
        if (e == null) {
            throw new IllegalStateException("插件未激活(引擎未构造)");
        }
        return e;
    }
}