package dev.everyagent.plugin.workflow;

import dev.everyagent.plugin.api.EveryAgentPlugin;
import dev.everyagent.plugin.api.WorkerPluginContext;
import dev.everyagent.plugin.api.event.Channels;
import dev.everyagent.plugin.api.event.StreamEmitter;
import dev.everyagent.plugin.workflow.engine.WorkflowEngine;
import dev.everyagent.plugin.workflow.rpc.WorkflowRpc;
import dev.everyagent.plugin.workflow.search.WorkflowDefinitionSuggestProvider;
import dev.everyagent.plugin.workflow.search.WorkflowSearchProvider;
import dev.everyagent.plugin.workflow.slash.WorkflowReferenceSlashResolver;
import dev.everyagent.plugin.workflow.slash.WorkflowReferenceToken;
import dev.everyagent.plugin.workflow.store.WorkflowStore;
import dev.everyagent.contract.json.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * workflow 插件入口。
 *
 * <p>职责（模型/校验/存储层 + hidden 五工具 + 激活扫描 + <b>执行引擎</b>
 * （WorkflowRuntime/调度器/节点执行器）+ <b>RPC 面</b>（workflow.* 15 个方法））。本类 activate 做六件事：
 * <ol>
 *   <li>构造 {@link WorkflowStore} 并执行 activate 扫描（建内存索引 + 非终态实例标
 *       failed("worker 重启中断")）；</li>
 *   <li>接线存储变更通知：ChangeListener → worker 级插件频道扇出
 *       {@code plugin.workflow.changed}（失效→重拉信号）；InstanceEventListener →
 *       <b>实例流频道</b>扇出实例事件（实时增量推送，历史/补齐走 instance.events RPC）；</li>
 *   <li>构造 {@link WorkflowEngine}（workflow.run/cancel/decide/instance.events RPC 后端，
 *       经 {@link #engine()} 取用）；</li>
 *   <li>注册 {@link WorkflowRpc}（14 个 workflow.* 方法，
 *       契约=计划「RPC 参数契约」表）；</li>
 *   <li>注册 {@link WorkflowToolProvider}（定义 CRUD 五件 + workflow_run/实例查询四件 +
 *       cancel + workflow_rerun 共十一个 hidden 工具；技能包由 plugin.json 声明 {@code skills} 目录物化，不走代码注册）。</li>
 *   <li>注册 {@link WorkflowReferenceSlashResolver}（kind={@code workflow.reference}
 *       + 兼容 {@code workflow.run} 各一份：任务页运行按钮 / @ 引用的工作流胶囊 token
 *       原串 → 「[引用工作流] 名 (definitionId=…)」中性引用文本——操作意图写在
 *       token 外的普通文字，主 agent 依文字自选 hidden 工具）。</li>
 *   <li>注册 {@link dev.everyagent.plugin.workflow.search.WorkflowSearchProvider}（统一
 *       search 后端：kind=workflow-definition（定义结构化字段内容）与 workflow-instance
 *       （events 过程内容），架构 §8.5 插件 provider 与内置同权）。</li>
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

        // 实例事件 → 实例流频道扇出（u.<K>.worker.<wid>.wf.<wi>.stream，Channels.workflowStream）：
        // 帧事件名 = 实例事件名，payload 与 instance.events RPC 应答条目同形（seq 字符串防 2^53）；
        // hub 只投已订阅连接（前端打开实例页即订阅）。低频元数据走广播扇出，无背压机——
        // fanout 的 pub 非阻塞（出站满即丢帧），磁盘 events.jsonl 是事实源、补齐走 RPC 拉取。
        store.addInstanceEventListener((workspaceId, instanceId, line) -> {
            String wid = stream.workerId();
            Long seq = parseSeq(line.seq());
            stream.fanout(k -> Channels.workflowStream(k, wid, instanceId), line.event(), seq,
                    Json.obj()
                            .put("seq", line.seq() == null ? "0" : line.seq())
                            .put("ts", line.ts() == null ? 0L : line.ts())
                            .put("event", line.event() == null ? "" : line.event())
                            .set("payload", line.payload() == null ? Json.obj() : line.payload()),
                    null);
        });

        // 执行引擎：调度器跑虚拟线程（start 异步开跑，不阻塞 RPC 线程）
        engine = new WorkflowEngine(store, ctx.services());

        // RPC 面：workflow.* 14 个方法（契约=计划「RPC 参数契约」表）
        new WorkflowRpc(store, engine).registerAll(ctx);

        // hidden 十一工具（定义 CRUD 五件 + 运行/查询/重开六件；frontmatter tools 绑定，
        // 读取 skill/workflow-tools/skill.md 后自动暴露进请求工具列表）
        ctx.registerToolProvider(new WorkflowToolProvider(store, engine));

        // 工作流引用 token 提交解析（中性引用：解析只留工作流名+id；kind=workflow.reference
        // 新构造 + 兼容 LEGACY workflow.run 各注册一份，旧草稿/重发仍可解析）
        ctx.registerSlashTokenResolver(new WorkflowReferenceSlashResolver(store, WorkflowReferenceToken.KIND));
        ctx.registerSlashTokenResolver(new WorkflowReferenceSlashResolver(store, WorkflowReferenceToken.LEGACY_KIND));

        // 统一搜索后端（kind=workflow-definition / workflow-instance，架构 §8.5 插件 provider 同权）
        ctx.registerSearchProvider(new WorkflowSearchProvider(store));

        // 「工作流定义名」@ 建议源（SuggestionProvider 能力接口，mention.query 增补聚合）
        ctx.registerSearchProvider(new WorkflowDefinitionSuggestProvider(store));
    }

    /** 执行引擎（RPC 层与本插件内部共用）。 */
    public WorkflowEngine engine() {
        WorkflowEngine e = engine;
        if (e == null) {
            throw new IllegalStateException("插件未激活(引擎未构造)");
        }
        return e;
    }

    /** 字符串雪花 seq → Long（帧级 seq 携带；解析失败返回 null=不携带）。 */
    private static Long parseSeq(String seq) {
        if (seq == null || seq.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(seq);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}