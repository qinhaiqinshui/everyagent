package dev.everyagent.plugin.workflow;

import dev.everyagent.plugin.api.EveryAgentPlugin;
import dev.everyagent.plugin.api.WorkerPluginContext;
import dev.everyagent.plugin.api.event.Channels;
import dev.everyagent.plugin.api.event.StreamEmitter;
import dev.everyagent.plugin.workflow.engine.WorkflowEngine;
import dev.everyagent.plugin.workflow.rpc.WorkflowRpc;
import dev.everyagent.plugin.workflow.store.WorkflowStore;
import dev.everyagent.contract.json.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * workflow 插件入口。
 *
 * <p>职责（模型/校验/存储层 + hidden 五工具 + 激活扫描 + <b>执行引擎</b>
 * （WorkflowRuntime/调度器/节点执行器）+ <b>RPC 面</b>（workflow.* 14 个方法））。本类 activate 做四件事：
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

    /**
     * 变更通知事件名（worker 级插件通知频道 {@code u.<K>.worker.<id>.plugin}，架构 §5.2）。
     * 事件名沿用任务流 {@code plugin.*} 直推的前缀约定；payload
     * {@code {workerId, kind: definition|instance, action: created|updated|deleted, workspaceId, id}}。
     * 前端只把它当「失效→重拉」信号（数据经 workflow.def.list / instance.list 全量拉取）。
     */
    public static final String CHANGED_EVENT = "plugin.workflow.changed";

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

        // 变更 → stream 频道扇出（扫描之后接线：激活期的中断标记不算业务变更）。
        // store 的全部写操作收口在 5 个 mutator，通知点即「落盘+索引更新成功后」；
        // 一台 worker 的变更只进自己的频道（worker 段），不混进同 apiKey 下另一台的前端视图。
        StreamEmitter stream = ctx.services().stream();
        store.addChangeListener((kind, action, workspaceId, id) -> {
            String wid = stream.workerId();
            stream.fanout(k -> Channels.plugins(k, wid), CHANGED_EVENT, null, Json.obj()
                    .put("workerId", wid)
                    .put("kind", kind.name().toLowerCase(Locale.ROOT))
                    .put("action", action.name().toLowerCase(Locale.ROOT))
                    .put("workspaceId", workspaceId == null ? "" : workspaceId)
                    .put("id", id == null ? "" : id), null);
        });

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