package dev.everyagent.plugin.workflow.rpc;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.WorkerPluginContext;
import dev.everyagent.plugin.api.WorkerServices;
import dev.everyagent.plugin.api.exception.BadParamsException;
import dev.everyagent.plugin.api.exception.NotFoundException;
import dev.everyagent.plugin.api.model.ModelConfig;
import dev.everyagent.plugin.api.rpc.RpcContext;
import dev.everyagent.plugin.api.rpc.RpcMethod;
import dev.everyagent.plugin.workflow.engine.WorkflowEngine;
import dev.everyagent.plugin.workflow.model.AgentDef;
import dev.everyagent.plugin.workflow.model.Definition;
import dev.everyagent.plugin.workflow.model.EventLine;
import dev.everyagent.plugin.workflow.model.InstanceMeta;
import dev.everyagent.plugin.workflow.model.InstanceStatus;
import dev.everyagent.plugin.workflow.model.NodeDef;
import dev.everyagent.plugin.workflow.model.OnError;
import dev.everyagent.plugin.workflow.model.RunRecord;
import dev.everyagent.plugin.workflow.model.VariableDef;
import dev.everyagent.plugin.workflow.store.WorkflowStore;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * workflow.* RPC 面（15 个方法，契约=计划「RPC 参数契约」表，前后端对齐速查表）。
 *
 * <p><b>参数纪律</b>（task.run/fs.&#42;/git.&#42; 同款）：写操作（def.save/delete/export/import、run）
 * 必带 {@code workspace} 参数——缺省/空串由 {@code strParam} 抛 BAD_PARAMS；落位
 * {@code workspaces/<wsId>/workflows/} 由 store 层的路径函数保证。def.list/instance.list
 * 的 workspace 是可选过滤（缺省=全部工作区，同 tasks.list 口径）；instance.* 按全局唯一
 * instanceId 寻址（meta 自带 workspaceId 归户）。
 *
 * <p><b>错误码语义</b>（RpcDispatcher 统一映射）：参数缺失/非法/校验失败 →
 * {@link BadParamsException}=BAD_PARAMS；目标（定义/实例）不存在 →
 * {@link NotFoundException}=NOT_FOUND；未注册方法=UNKNOWN_METHOD、其余=INTERNAL
 * 均在 dispatcher 层，本类不直接应答 err。
 *
 * <p><b>seq 一律字符串</b>（雪花 ID 超 2^53，防 JS 精度丢失——task 域同款 wire 纪律）：
 * instance.events 的 seq/afterSeq/nextSeq 全走字符串（afterSeq 经 asLong 解析无损）。
 *
 * <p><b>长轮询</b>：instance.events 的 waitMs 上限 {@link #MAX_WAIT_MS}（sdk.rpc 默认
 * 30s 超时，留半程余量）；RPC handler 跑在 RpcDispatcher 的虚拟线程上（允许阻塞），
 * 等待/唤醒由 {@link WorkflowEngine#queryEvents} 内部实现（200ms 轮询磁盘事件流——
 * 追加写的完整事实源；事件到达/实例终态/超时即返回，终态实例立即返回当前尾）。
 */
public final class WorkflowRpc {

    // ══════════════════════════ 方法名（15 个） ══════════════════════════

    public static final String DEF_LIST = "workflow.def.list";
    public static final String DEF_GET = "workflow.def.get";
    public static final String DEF_SAVE = "workflow.def.save";
    public static final String DEF_DELETE = "workflow.def.delete";
    public static final String DEF_EXPORT = "workflow.def.export";
    public static final String DEF_IMPORT = "workflow.def.import";
    public static final String RUN = "workflow.run";
    public static final String INSTANCE_LIST = "workflow.instance.list";
    public static final String INSTANCE_GET = "workflow.instance.get";
    public static final String INSTANCE_RUN_LOG = "workflow.instance.runLog";
    public static final String INSTANCE_EVENTS = "workflow.instance.events";
    public static final String INSTANCE_CANCEL = "workflow.instance.cancel";
    public static final String INSTANCE_DELETE = "workflow.instance.delete";
    public static final String INSTANCE_DECIDE = "workflow.instance.decide";
    public static final String INSTANCE_DATA_DIR = "workflow.instance.dataDir";

    /** 长轮询等待上限（sdk.rpc 默认 30s 超时，勿超）。 */
    public static final long MAX_WAIT_MS = 15_000;

    private final WorkflowStore store;
    private final WorkflowEngine engine;
    private final WorkerServices services;

    public WorkflowRpc(WorkflowStore store, WorkflowEngine engine, WorkerServices services) {
        this.store = store;
        this.engine = engine;
        this.services = services;
    }

    /** 方法名 → 处理器（注册与测试共用同一张表，防两处漂移）。 */
    public Map<String, RpcMethod> methods() {
        Map<String, RpcMethod> m = new LinkedHashMap<>();
        m.put(DEF_LIST, this::defList);
        m.put(DEF_GET, this::defGet);
        m.put(DEF_SAVE, this::defSave);
        m.put(DEF_DELETE, this::defDelete);
        m.put(DEF_EXPORT, this::defExport);
        m.put(DEF_IMPORT, this::defImport);
        m.put(RUN, this::run);
        m.put(INSTANCE_LIST, this::instanceList);
        m.put(INSTANCE_GET, this::instanceGet);
        m.put(INSTANCE_RUN_LOG, this::instanceRunLog);
        m.put(INSTANCE_EVENTS, this::instanceEvents);
        m.put(INSTANCE_CANCEL, this::instanceCancel);
        m.put(INSTANCE_DELETE, this::instanceDelete);
        m.put(INSTANCE_DECIDE, this::instanceDecide);
        m.put(INSTANCE_DATA_DIR, this::instanceDataDir);
        return m;
    }

    /** 注册全部 15 个方法（activate 调用）。 */
    public void registerAll(WorkerPluginContext ctx) {
        methods().forEach(ctx::registerRpcMethod);
    }

    // ══════════════════════════ 定义 CRUD ══════════════════════════

    /** workflow.def.list {workspace?} → {defs:[摘要]}：updatedAt 倒序，摘要不含全图。 */
    public void defList(RpcContext ctx) {
        String ws = optWorkspace(ctx);
        ArrayNode defs = Json.arr();
        for (Definition d : store.listDefinitions(ws)) {
            defs.add(Json.obj()
                    .put("id", orEmpty(d.id()))
                    .put("name", orEmpty(d.name()))
                    .put("description", orEmpty(d.description()))
                    .put("workspaceId", orEmpty(d.workspaceId()))
                    .put("updatedAt", d.updatedAt() == null ? 0L : d.updatedAt())
                    .put("nodeCount", d.nodesOrEmpty().size())
                    .put("workspaceMissing", !store.isWorkspaceRegistered(d.workspaceId())));
        }
        ctx.ok(Json.obj().set("defs", defs));
    }

    /** workflow.def.get {workspace, id} → {definition}：完整定义 JSON。 */
    public void defGet(RpcContext ctx) {
        Definition d = requireDefinition(ctx);
        ctx.ok(Json.obj().set("definition", Json.toJson(d)));
    }

    /**
     * workflow.def.save {workspace, definition} → {id}：upsert（id 空=新建赋 wf id）；
     * 全量校验失败 BAD_PARAMS 带逐项错误列表（store 层 join）；定义的 workspaceId
     * 以参数 workspace 为准回写。
     */
    public void defSave(RpcContext ctx) {
        String ws = ctx.strParam("workspace");
        Definition draft = definitionParam(ctx);
        Definition saved = store.saveDefinition(ws, draft);
        ctx.ok(Json.obj().put("id", saved.id()));
    }

    /** workflow.def.delete {workspace, id} → {ok:true}：只删 definitions/ 目录，历史实例持快照不受影响。 */
    public void defDelete(RpcContext ctx) {
        String ws = ctx.strParam("workspace");
        String id = ctx.strParam("id");
        if (!store.deleteDefinition(ws, id)) {
            throw new NotFoundException("工作流定义不存在: " + id + " @工作区 " + ws);
        }
        ctx.ok(Json.obj().put("ok", true));
    }

    /** workflow.def.export {workspace, id} → {definition}：definition.json 原样（前端转文件下载）。 */
    public void defExport(RpcContext ctx) {
        String ws = ctx.strParam("workspace");
        String id = ctx.strParam("id");
        String raw = store.exportDefinition(ws, id)
                .orElseThrow(() -> new NotFoundException("工作流定义不存在: " + id + " @工作区 " + ws));
        ctx.ok(Json.obj().set("definition", Json.parse(raw)));
    }

    /** workflow.def.import {workspace, definition} → {id}：重赋顶层 wf id、落入指定工作区，校验同 save。 */
    public void defImport(RpcContext ctx) {
        String ws = ctx.strParam("workspace");
        Definition incoming = definitionParam(ctx);
        Definition saved = store.importDefinition(ws, incoming);
        ctx.ok(Json.obj().put("id", saved.id()));
    }

    // ══════════════════════════ 运行 ══════════════════════════

    /**
     * workflow.run {workspace, definitionId, variables?} → {instanceId}：
     * required 变量校验（缺→BAD_PARAMS）+ 模型配置存在性校验（不存在→BAD_PARAMS）；
     * 立即创建实例并异步开跑（engine.start 只做状态迁移与首次调度，调度器跑虚拟线程，
     * 不阻塞 RPC 线程）。
     */
    public void run(RpcContext ctx) {
        String ws = ctx.strParam("workspace");
        String definitionId = ctx.strParam("definitionId");
        Definition def = store.getDefinition(ws, definitionId)
                .orElseThrow(() -> new NotFoundException("工作流定义不存在: " + definitionId + " @工作区 " + ws));
        Map<String, String> variables = variablesParam(ctx);
        List<String> missing = missingRequiredVariables(def, variables);
        if (!missing.isEmpty()) {
            throw new BadParamsException("缺少必填运行变量: " + String.join(", ", missing));
        }
        List<String> unknownConfigs = unknownModelConfigs(def);
        if (!unknownConfigs.isEmpty()) {
            throw new BadParamsException("模型配置不存在: " + String.join("; ", unknownConfigs));
        }
        InstanceMeta meta = store.createInstance(def, variables);
        engine.start(meta);
        ctx.ok(Json.obj().put("instanceId", meta.id()));
    }

    // ══════════════════════════ 实例 ══════════════════════════

    /** workflow.instance.list {workspace?, definitionId?} → {instances:[摘要]}：createdAt 倒序，含终态 nodeStates 投影、运行变量与工作区缺失标记。 */
    public void instanceList(RpcContext ctx) {
        String ws = optWorkspace(ctx);
        String definitionId = blankToNull(ctx.optStrParam("definitionId", null));
        ArrayNode instances = Json.arr();
        for (InstanceMeta m : store.listInstances(ws, definitionId)) {
            ObjectNode n = Json.obj()
                    .put("id", orEmpty(m.id()))
                    .put("definitionId", orEmpty(m.definitionId()))
                    .put("definitionName", orEmpty(m.definitionName()))
                    .put("workspaceId", orEmpty(m.workspaceId()))
                    .put("status", statusWire(m.status()))
                    .put("createdAt", m.createdAt() == null ? 0L : m.createdAt())
                    .put("endedAt", m.endedAt() == null ? 0L : m.endedAt())
                    .put("durationMs", m.durationMs() == null ? 0L : m.durationMs())
                    .put("workspaceMissing", !store.isWorkspaceRegistered(m.workspaceId()));
            if (m.error() != null) {
                n.set("error", Json.toJson(m.error()));
            }
            if (m.nodeStates() != null) {
                n.set("nodeStates", Json.toJson(m.nodeStates()));
            }
            // 运行变量（侧栏列表行展示参数值；非空才带，避免空表噪声）。
            if (m.variables() != null && !m.variables().isEmpty()) {
                n.set("variables", Json.toJson(m.variables()));
            }
            instances.add(n);
        }
        ctx.ok(Json.obj().set("instances", instances));
    }

    /** workflow.instance.get {instanceId} → {instance}：meta.json 全量（含 definitionSnapshot/nodeStates/pendingDecision）。 */
    public void instanceGet(RpcContext ctx) {
        InstanceMeta m = requireInstance(ctx);
        ctx.ok(Json.obj().set("instance", Json.toJson(m)));
    }

    /**
     * workflow.instance.runLog {instanceId} → {runs:[按 runNo 升序], nextRunNo}：
     * runs/*.json 内容数组；nextRunNo = max(已分配计数, 已落盘最大 runNo)+1
     * （对前端是「下一个将出现的运行号」——整表快照式应答，无增量游标语义）。
     */
    public void instanceRunLog(RpcContext ctx) {
        InstanceMeta m = requireInstance(ctx);
        List<RunRecord> records = store.listRunRecords(m.workspaceId(), m.id());
        ArrayNode runs = Json.arr();
        int maxRunNo = 0;
        for (RunRecord r : records) {
            runs.add(Json.toJson(r));
            if (r.runNo() != null && r.runNo() > maxRunNo) {
                maxRunNo = r.runNo();
            }
        }
        int counter = m.runCounter() == null ? 0 : m.runCounter();
        ctx.ok(Json.obj().set("runs", runs).put("nextRunNo", Math.max(maxRunNo, counter) + 1));
    }

    /**
     * workflow.instance.events {instanceId, afterSeq?, waitMs?(≤15000), limit?} →
     * {events:[{seq(string),ts,event,payload}], nextSeq(string)}。
     *
     * <p>长轮询：afterSeq 游标；空批且实例仍在运行时等待至 waitMs（引擎内部轮询磁盘
     * 事件流，事件到达/终态/超时即返回）；终态实例立即返回当前尾。nextSeq=未过滤推进
     * 游标（task.poll 同款纪律）：本面无出网过滤，原始批次尾即游标；空批保持调用方
     * 游标不动（终态空批=已读到尾，活实例空批=本次等待无新事件）。
     */
    public void instanceEvents(RpcContext ctx) {
        String instanceId = ctx.strParam("instanceId");
        store.findInstance(instanceId)
                .orElseThrow(() -> new NotFoundException("工作流实例不存在: " + instanceId));
        long afterSeq = Math.max(0L, ctx.optLongParam("afterSeq", 0L));
        long waitMs = clampWaitMs(ctx.optLongParam("waitMs", 0L));
        int limit = (int) Math.max(0L, ctx.optLongParam("limit", 0L));
        List<EventLine> events = engine.queryEvents(instanceId, afterSeq, waitMs, limit);
        ArrayNode arr = Json.arr();
        for (EventLine e : events) {
            arr.add(Json.obj()
                    .put("seq", e.seq() == null ? "0" : e.seq())
                    .put("ts", e.ts() == null ? 0L : e.ts())
                    .put("event", orEmpty(e.event()))
                    .set("payload", e.payload() == null ? Json.obj() : e.payload()));
        }
        String nextSeq = events.isEmpty()
                ? Long.toString(afterSeq)
                : events.get(events.size() - 1).seq();
        ctx.ok(Json.obj().set("events", arr).put("nextSeq", nextSeq));
    }

    /** workflow.instance.cancel {instanceId} → {ok:true}：engine.cancel（已终态/未在运行 → BAD_PARAMS）。 */
    public void instanceCancel(RpcContext ctx) {
        String instanceId = ctx.strParam("instanceId");
        engine.cancel(instanceId, "user");
        ctx.ok(Json.obj().put("ok", true));
    }

    /** workflow.instance.delete {instanceId} → {ok:true}：运行中/挂起拒绝；删整个实例目录+内存索引。 */
    public void instanceDelete(RpcContext ctx) {
        InstanceMeta m = requireInstance(ctx);
        if (engine.isAlive(m.id())) {
            throw new BadParamsException("实例运行中/挂起等待用户,拒绝删除: " + m.id());
        }
        if (!store.deleteInstance(m.workspaceId(), m.id())) {
            throw new NotFoundException("工作流实例不存在: " + m.id());
        }
        ctx.ok(Json.obj().put("ok", true));
    }

    /** workflow.instance.decide {instanceId, runNo, decision, input?} → {ok:true}：仅 waiting-user+pendingDecision 匹配时生效，否则 BAD_PARAMS。 */
    public void instanceDecide(RpcContext ctx) {
        String instanceId = ctx.strParam("instanceId");
        long runNo = ctx.optLongParam("runNo", -1L);
        if (runNo < 0 || runNo > Integer.MAX_VALUE) {
            throw new BadParamsException("缺少/非法参数 runNo");
        }
        String decision = ctx.strParam("decision");
        String input = ctx.optStrParam("input", null);
        engine.decide(instanceId, (int) runNo, decision, input);
        ctx.ok(Json.obj().put("ok", true));
    }

    /**
     * workflow.instance.dataDir {instanceId} → {path}：实例目录
     * （workspaces/&lt;wsId&gt;/workflows/instances/&lt;wiId&gt;/）在 worker 宿主上的绝对路径。
     * 前端侧边栏「复制工作流路径」按需拉取——不进 instance.list 摘要 wire，路径随取随发，
     * worker 数据目录迁移后不会留下陈旧值（task.dataDir 同款纪律）。
     * 实例不存在 → NOT_FOUND（存在性口径=内存索引，activate 扫描后与磁盘一致）。
     */
    public void instanceDataDir(RpcContext ctx) {
        InstanceMeta m = requireInstance(ctx);
        ctx.ok(Json.obj().put("path", store.instanceDirOf(m.workspaceId(), m.id()).toString()));
    }

    // ══════════════════════════ 参数装配 ══════════════════════════

    /** definition 参数（JSON 对象 → Definition；缺失/形态错/不可解析 → BAD_PARAMS）。 */
    private static Definition definitionParam(RpcContext ctx) {
        JsonNode node = ctx.params().path("definition");
        if (node.isMissingNode() || node.isNull()) {
            throw new BadParamsException("缺少参数 definition");
        }
        if (!node.isObject()) {
            throw new BadParamsException("definition 必须是 JSON 对象");
        }
        try {
            return Json.convert(node, Definition.class);
        } catch (RuntimeException e) {
            throw new BadParamsException("definition 解析失败: " + e.getMessage());
        }
    }

    /** variables 参数（对象→字符串表；缺省空表；非标量值 → BAD_PARAMS）。 */
    private static Map<String, String> variablesParam(RpcContext ctx) {
        JsonNode node = ctx.params().path("variables");
        if (node.isMissingNode() || node.isNull()) {
            return Map.of();
        }
        if (!node.isObject()) {
            throw new BadParamsException("variables 必须是对象(变量名→标量值)");
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : node.properties()) {
            JsonNode v = e.getValue();
            if (v == null || v.isNull()) {
                continue;
            }
            if (v.isObject() || v.isArray()) {
                throw new BadParamsException("变量 " + e.getKey() + " 的值必须是标量(字符串/数字/布尔)");
            }
            out.put(e.getKey(), v.asString());
        }
        return out;
    }

    /** required 且运行变量既未提供也无默认值的名字（默认值可满足 required——渲染口径一致）。 */
    private static List<String> missingRequiredVariables(Definition def, Map<String, String> variables) {
        List<String> missing = new ArrayList<>();
        for (VariableDef v : def.variablesOrEmpty()) {
            if (v == null || v.name() == null || !Boolean.TRUE.equals(v.required())) {
                continue;
            }
            String provided = variables.get(v.name());
            if ((provided == null || provided.isEmpty())
                    && (v.defaultValue() == null || v.defaultValue().isEmpty())) {
                missing.add(v.name());
            }
        }
        return missing;
    }

    /**
     * 定义引用的模型配置存在性（经 services().modelConfigs()）：agent 声明与节点覆盖
     * 全查；supervisor 仅 onError=supervisor（缺省同）时查。configId=null=用系统默认模型，
     * 不在校验范围。引用处带定位（「agent 名/节点名 → configId」逐项列出）。
     */
    private List<String> unknownModelConfigs(Definition def) {
        Set<String> known = new HashSet<>();
        for (ModelConfig c : services.modelConfigs()) {
            if (c != null && c.configId() != null) {
                known.add(c.configId());
            }
        }
        List<String> unknown = new ArrayList<>();
        Set<String> reported = new LinkedHashSet<>();
        for (AgentDef a : def.agentsOrEmpty()) {
            if (a != null && a.modelConfigId() != null && !known.contains(a.modelConfigId())
                    && reported.add(a.modelConfigId())) {
                unknown.add("agent「" + orEmpty(a.name()) + "」→ " + a.modelConfigId());
            }
        }
        for (NodeDef n : def.nodesOrEmpty()) {
            if (n != null && n.modelConfigId() != null && !known.contains(n.modelConfigId())
                    && reported.add(n.modelConfigId())) {
                unknown.add("节点「" + (n.name() == null || n.name().isBlank() ? n.id() : n.name())
                        + "」→ " + n.modelConfigId());
            }
        }
        if (def.onError() == null || def.onError() == OnError.SUPERVISOR) {
            Definition.Supervisor sup = def.supervisor();
            if (sup != null && sup.modelConfigId() != null && !known.contains(sup.modelConfigId())
                    && reported.add(sup.modelConfigId())) {
                unknown.add("supervisor → " + sup.modelConfigId());
            }
        }
        return unknown;
    }

    /** 可选 workspace 过滤参数（缺省/空白 → null=不过滤）。 */
    private static String optWorkspace(RpcContext ctx) {
        return blankToNull(ctx.optStrParam("workspace", null));
    }

    private Definition requireDefinition(RpcContext ctx) {
        String ws = ctx.strParam("workspace");
        String id = ctx.strParam("id");
        return store.getDefinition(ws, id)
                .orElseThrow(() -> new NotFoundException("工作流定义不存在: " + id + " @工作区 " + ws));
    }

    private InstanceMeta requireInstance(RpcContext ctx) {
        String id = ctx.strParam("instanceId");
        return store.findInstance(id)
                .orElseThrow(() -> new NotFoundException("工作流实例不存在: " + id));
    }

    // ══════════════════════════ 工具 ══════════════════════════

    /** waitMs 归一并夹到 [0, {@link #MAX_WAIT_MS}]（超限钳制不报错——前端时钟漂移容错）。 */
    public static long clampWaitMs(long waitMs) {
        return Math.min(Math.max(0L, waitMs), MAX_WAIT_MS);
    }

    /** 状态 → wire 名（与 InstanceStatus @JsonProperty 序列化值一致）。 */
    private static String statusWire(InstanceStatus s) {
        if (s == null) {
            return "";
        }
        return switch (s) {
            case CREATED -> "created";
            case RUNNING -> "running";
            case WAITING_USER -> "waiting-user";
            case DONE -> "done";
            case FAILED -> "failed";
            case CANCELLED -> "cancelled";
        };
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
