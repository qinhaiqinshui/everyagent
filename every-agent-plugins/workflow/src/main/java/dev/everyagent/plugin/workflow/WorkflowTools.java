package dev.everyagent.plugin.workflow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.exception.AgentCancelledException;
import dev.everyagent.plugin.api.exception.BadParamsException;
import dev.everyagent.plugin.api.spi.ToolContext;
import dev.everyagent.plugin.api.tool.PlainTextToolCallResultConverter;
import dev.everyagent.plugin.workflow.engine.WorkflowEngine;
import dev.everyagent.plugin.workflow.model.Definition;
import dev.everyagent.plugin.workflow.model.DefinitionJson;
import dev.everyagent.plugin.workflow.model.EventLine;
import dev.everyagent.plugin.workflow.model.InstanceMeta;
import dev.everyagent.plugin.workflow.model.InstanceStatus;
import dev.everyagent.plugin.workflow.model.RunRecord;
import dev.everyagent.plugin.workflow.store.WorkflowStore;
import dev.everyagent.plugin.workflow.validate.DefinitionValidator;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * workflow 工具集（hidden：模型经「工作流编排」skill 文档按名调用）——定义 CRUD 五件 +
 * 运行/查询/重开六件（workflow_run + instance_status/log/events/cancel + workflow_rerun，§7.20）。
 *
 * <p>绑定 createHiddenTools 传入的 {@link ToolContext}（随工具回调同生命周期）——
 * <b>零 per-run 捕获</b>，且 ToolContext 本身即 origin 任务的 ExecContext（subjectId=
 * taskId、emitter、agentFactory），workflow_run 直接以它作 {@code engine.start} 的任务
 * 绑定。需要工作区时：工具参数显式传 workspace（工作区根绝对路径，经注册表解析 id）
 * 优先，缺省回落当前任务工作区 {@code ctx.workspaceId()}。
 *
 * <p>全部返回<b>纯文本 JSON</b>（PlainTextToolCallResultConverter，防框架把 String 再
 * JSON 编码一层）。错误约定：{@code {"error": "...", "details": [...]}}（details 仅
 * 校验失败带，逐项错误供模型自纠）。
 *
 * <p><b>workflow_run 的阻塞语义（§7.20，workflow_rerun 同款复用）</b>：校验（与
 * workflow.run RPC 共用 {@link WorkflowEngine#validateForRun}）→ createInstance(
 * originTaskId=任务 ID) → engine.start(绑定任务) → 阻塞至终态/失败——可中断等待
 * （虚拟线程上阻塞合法）+ 任务 terminal() 轮询兜底；任务停止（中断到达或任务收口）→
 * 级联 engine.cancel 后抛 {@link AgentCancelledException}（穿透工具执行回栈，不被
 * 转成模型可读错误）。
 */
public final class WorkflowTools {

    /** 任务停止兜底轮询周期（任务 terminal() 检查；直接中断走 latch await 异常路径）。 */
    private static final long TASK_STOP_POLL_MS = 200;

    /** workflow_instance_log 的 stdout/stderr/text 截断上限（字符）。 */
    private static final int LOG_TEXT_TRUNCATE = 4000;

    /** workflow_instance_log 缺省尾巴条数。 */
    private static final int LOG_DEFAULT_TAIL = 10;

    /** workflow_instance_events 缺省/上限条数。 */
    private static final int EVENTS_DEFAULT_LIMIT = 50;
    private static final int EVENTS_MAX_LIMIT = 200;

    private final WorkflowStore store;
    private final WorkflowEngine engine;
    private final ToolContext ctx;

    public WorkflowTools(WorkflowStore store, WorkflowEngine engine, ToolContext ctx) {
        this.store = store;
        this.engine = engine;
        this.ctx = ctx;
    }

    @Tool(description = "创建一个可复用的工作流定义(多节点执行 DAG:开始/命令/AI 节点 + 条件边 + 运行变量 + 命名 agent,"
            + "由用户在工作流画布查看与运行)。AI 节点自带 agent(命名会话线,可空)/modelConfigId/systemPrompt/input;"
            + "入参是完整定义 JSON 文本(结构见「工作流编排」技能文档);"
            + "id/createdAt/updatedAt/workspaceId 等系统字段可省略,自动分配。返回 {id};"
            + "校验失败返回 {error, details[逐项原因]}。",
            resultConverter = PlainTextToolCallResultConverter.class)
    public String workflow_create(
            @ToolParam(description = "完整工作流定义 JSON 文本(结构见「工作流编排」技能)") String definition) {
        return saveDefinitionJson(null, definition);
    }

    @Tool(description = "按 id 读取完整工作流定义 JSON(含节点/边/变量全图)。",
            resultConverter = PlainTextToolCallResultConverter.class)
    public String workflow_get(
            @ToolParam(description = "定义 id(wf_ 前缀,来自 workflow_create 返回或 workflow_list)") String id) {
        String defId = requireId(id);
        Optional<Definition> found = store.findDefinition(defId);
        if (found.isEmpty()) {
            return err("NOT_FOUND: " + defId);
        }
        return Json.write(found.get());
    }

    @Tool(description = "全量更新已存在的工作流定义(整体替换,非增量合并;未提及的字段会被覆盖删除,"
            + "须传完整定义)。返回 {id};校验失败返回 {error, details[逐项原因]}。",
            resultConverter = PlainTextToolCallResultConverter.class)
    public String workflow_update(
            @ToolParam(description = "要更新的定义 id") String id,
            @ToolParam(description = "更新后的完整定义 JSON 文本(其 id 以参数 id 为准)") String definition) {
        return saveDefinitionJson(requireId(id), definition);
    }

    @Tool(description = "删除一个工作流定义。历史运行实例保留(实例持定义快照,不受影响)。",
            resultConverter = PlainTextToolCallResultConverter.class)
    public String workflow_delete(
            @ToolParam(description = "要删除的定义 id") String id) {
        String defId = requireId(id);
        Optional<Definition> found = store.findDefinition(defId);
        if (found.isEmpty()) {
            return err("NOT_FOUND: " + defId);
        }
        store.deleteDefinition(found.get().workspaceId(), defId);
        return Json.write(Map.of("ok", true));
    }

    @Tool(description = "列出工作流定义摘要 {defs:[{id,name,description,workspaceId,updatedAt,nodeCount}]},"
            + "按更新时间倒序。可选 workspace 传工作区根绝对路径只列该工作区;缺省列当前任务的工作区。",
            resultConverter = PlainTextToolCallResultConverter.class)
    public String workflow_list(
            @ToolParam(description = "工作区根绝对路径(可选,缺省=当前任务工作区)", required = false) String workspace) {
        String wsId;
        try {
            wsId = workspaceId(workspace);
        } catch (BadParamsException e) {
            return err(e.getMessage());
        }
        ObjectNode root = Json.obj();
        ArrayNode defs = Json.arr();
        for (Definition d : store.listDefinitions(wsId)) {
            defs.add(Json.obj()
                    .put("id", d.id())
                    .put("name", d.name())
                    .put("description", d.description() == null ? "" : d.description())
                    .put("workspaceId", d.workspaceId())
                    .put("updatedAt", d.updatedAt() == null ? 0L : d.updatedAt())
                    .put("nodeCount", d.nodes() == null ? 0 : d.nodes().size()));
        }
        root.set("defs", defs);
        return Json.write(root);
    }

    // ══════════════════════════ 运行与实例查询（§7.20） ══════════════════════════

    @Tool(description = "在当前任务内运行一个工作流并<b>阻塞至终态</b>(实例运行期间本工具不返回,节点过程以子 agent 会话"
            + "与 wf.node 轨迹形式实时呈现在任务页)。参数:definitionId 必填;variables 可选(JSON 文本 {k:v});"
            + "workspace 可选(缺省=当前任务工作区)。返回:done → {instanceId,status:'done',durationMs,nodes:[节点摘要]};"
            + "failed → {error,nodeId,runNo,message,context(失败节点历史输出摘要),hint:可用 workflow_rerun 从中断节点续跑};"
            + "cancelled/waiting-user → 结构化说明。校验失败返回 {error,details[逐项原因]}。",
            resultConverter = PlainTextToolCallResultConverter.class)
    public String workflow_run(
            @ToolParam(description = "定义 id(wf_ 前缀,来自 workflow_create 返回或 workflow_list)") String definitionId,
            @ToolParam(description = "运行变量 JSON 文本 {k:v}(可选,必填变量缺失会报错)", required = false) String variables,
            @ToolParam(description = "工作区根绝对路径(可选,缺省=当前任务工作区)", required = false) String workspace) {
        if (definitionId == null || definitionId.isBlank()) {
            return err("definitionId 不能为空");
        }
        Map<String, String> vars;
        try {
            vars = variablesOf(variables);
        } catch (BadParamsException e) {
            return err(e.getMessage());
        }
        String wsId;
        try {
            wsId = workspaceId(workspace);
        } catch (BadParamsException e) {
            return err(e.getMessage());
        }
        Optional<Definition> found = store.getDefinition(wsId, definitionId.trim());
        if (found.isEmpty()) {
            return err("NOT_FOUND: " + definitionId.trim() + " @工作区 " + wsId);
        }
        try {
            engine.validateForRun(found.get(), vars);
        } catch (BadParamsException e) {
            return err(e.getMessage());
        }
        // 任务内运行:ToolContext 本身即 origin 任务 ExecContext(subjectId=taskId)
        InstanceMeta meta = store.createInstance(found.get(), vars, ctx.subjectId());
        InstanceMeta terminal;
        try {
            engine.start(meta, ctx);
            terminal = awaitTerminalInterruptible(meta.id());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cascadeCancel(meta.id());
            throw new AgentCancelledException("task cancelled");
        }
        return runResult(terminal);
    }

    @Tool(description = "从<b>失败</b>的工作流实例中断节点续跑(介入修复:failed→running,runNo 延续,events/runs 继续追加;"
            + "已完成节点不重跑,只重跑指定节点及其下游;命名 agent 复用同一会话,上下文从任务档案原样恢复)。"
            + "参数:instanceId 必填(失败实例);nodeId 可选(缺省=失败节点,可指定任意节点重开该分支);"
            + "input 可选(替换该节点输入,retryWithInput 语义,AI 节点输入支持 {{var}} 模板)。"
            + "仅 failed 实例可重跑;再失败可再次调用(幂等链);实例关联的原任务已结束/删除时报错"
            + "(会话档案在原任务目录,只能重新 workflow_run 全新运行)。阻塞语义同 workflow_run"
            + "(运行至终态才返回,任务停止级联取消),返回形态同 workflow_run。",
            resultConverter = PlainTextToolCallResultConverter.class)
    public String workflow_rerun(
            @ToolParam(description = "失败实例 id(wi_ 前缀,来自 workflow_run 失败返回)") String instanceId,
            @ToolParam(description = "重开节点 id(可选,缺省=失败节点;可指定任意节点从头重开该分支)", required = false) String nodeId,
            @ToolParam(description = "替换该节点输入的文本(可选,retryWithInput 语义;AI 节点支持 {{var}} 变量模板)", required = false) String input) {
        String id = requireInstanceId(instanceId);
        Optional<InstanceMeta> found = store.findInstance(id);
        if (found.isEmpty()) {
            return err("NOT_FOUND: " + id);
        }
        InstanceMeta m = found.get();
        if (m.status() != InstanceStatus.FAILED) {
            return err("实例不可重跑(当前状态 " + statusWire(m.status()) + "): 仅 failed 实例可从中断节点续跑");
        }
        // origin 任务存活校验:实例关联任务须即当前任务且未收口(会话档案在任务目录,
        // 任务结束/换任务即不可复用——AI 节点上下文无法重建,只能全新运行);
        // 无 originTaskId(RPC 直启的纯命令流)允许无绑定重跑(无 AI 节点无需任务域)
        if (m.originTaskId() != null
                && (!m.originTaskId().equals(ctx.subjectId()) || ctx.terminal())) {
            return err("原任务已结束/删除,无法复用会话上下文,只能全新运行(重新 workflow_run):"
                    + " 实例关联任务 " + m.originTaskId() + ",当前任务 " + ctx.subjectId());
        }
        InstanceMeta terminal;
        try {
            engine.rerun(id, nodeId, input, ctx);
            terminal = awaitTerminalInterruptible(id);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cascadeCancel(id);
            throw new AgentCancelledException("task cancelled");
        } catch (BadParamsException | dev.everyagent.plugin.api.exception.NotFoundException e) {
            return err(e.getMessage());
        }
        return runResult(terminal);
    }

    @Tool(description = "查询工作流实例状态摘要 {instanceId,definitionName,status,variables,nodeStates(节点进度),"
            + "error?,pendingDecision?,durationMs,originTaskId?}。不含定义快照全图(需要定义用 workflow_get)。",
            resultConverter = PlainTextToolCallResultConverter.class)
    public String workflow_instance_status(
            @ToolParam(description = "实例 id(wi_ 前缀,来自 workflow_run 返回)") String instanceId) {
        String id = requireInstanceId(instanceId);
        Optional<InstanceMeta> found = store.findInstance(id);
        if (found.isEmpty()) {
            return err("NOT_FOUND: " + id);
        }
        InstanceMeta m = found.get();
        ObjectNode n = Json.obj()
                .put("instanceId", m.id())
                .put("definitionId", orEmpty(m.definitionId()))
                .put("definitionName", orEmpty(m.definitionName()))
                .put("workspaceId", orEmpty(m.workspaceId()))
                .put("status", statusWire(m.status()))
                .put("createdAt", m.createdAt() == null ? 0L : m.createdAt())
                .put("durationMs", m.durationMs() == null ? 0L : m.durationMs());
        if (m.originTaskId() != null) {
            n.put("originTaskId", m.originTaskId());
        }
        if (m.error() != null) {
            n.set("error", Json.toJson(m.error()));
        }
        if (m.pendingDecision() != null) {
            n.set("pendingDecision", Json.toJson(m.pendingDecision()));
        }
        if (!m.variablesOrEmpty().isEmpty()) {
            n.set("variables", Json.toJson(m.variablesOrEmpty()));
        }
        if (m.nodeStates() != null) {
            ArrayNode nodes = Json.arr();
            Definition snap = m.definitionSnapshot();
            m.nodeStates().forEach((nodeId, st) -> {
                nodes.add(Json.obj()
                        .put("nodeId", nodeId)
                        .put("nodeName", snap == null ? nodeId : displayNameOf(snap, nodeId))
                        .put("status", st.status() == null ? "" : st.status())
                        .put("runCount", st.runCount() == null ? 0 : st.runCount()));
            });
            n.set("nodeStates", nodes);
        }
        return Json.write(n);
    }

    @Tool(description = "查询工作流实例的节点运行日志(runs 记录,按 runNo 升序取末尾 tail 条,缺省 10)。"
            + "可选 nodeId 只看该节点。每条 {runNo,nodeId,nodeName,nodeType,startedAt,durationMs,"
            + "input?,output(stdout/stderr/text 各截断 4000 字符),exitCode?,error?}。",
            resultConverter = PlainTextToolCallResultConverter.class)
    public String workflow_instance_log(
            @ToolParam(description = "实例 id(wi_ 前缀)") String instanceId,
            @ToolParam(description = "节点 id(可选,只看该节点)", required = false) String nodeId,
            @ToolParam(description = "返回末尾条数(可选,缺省 10)", required = false) Integer tail) {
        String id = requireInstanceId(instanceId);
        Optional<InstanceMeta> found = store.findInstance(id);
        if (found.isEmpty()) {
            return err("NOT_FOUND: " + id);
        }
        InstanceMeta m = found.get();
        int t = tail != null && tail > 0 ? tail : LOG_DEFAULT_TAIL;
        List<RunRecord> records = store.listRunRecords(m.workspaceId(), m.id());
        List<RunRecord> filtered = new ArrayList<>();
        for (RunRecord r : records) {
            if (nodeId == null || nodeId.isBlank() || nodeId.trim().equals(r.nodeId())) {
                filtered.add(r);
            }
        }
        int from = Math.max(0, filtered.size() - t);
        ArrayNode runs = Json.arr();
        for (RunRecord r : filtered.subList(from, filtered.size())) {
            ObjectNode n = Json.obj()
                    .put("runNo", r.runNo() == null ? 0 : r.runNo())
                    .put("nodeId", orEmpty(r.nodeId()))
                    .put("nodeName", orEmpty(r.nodeName()))
                    .put("nodeType", orEmpty(r.nodeType()))
                    .put("startedAt", r.startedAt() == null ? 0L : r.startedAt())
                    .put("durationMs", r.durationMs() == null ? 0L : r.durationMs());
            RunRecord.RunInput in = r.input();
            if (in != null && in.rendered() != null) {
                n.put("input", truncate(in.rendered(), LOG_TEXT_TRUNCATE));
            }
            RunRecord.RunOutput out = r.output();
            if (out != null) {
                ObjectNode o = Json.obj();
                if (out.exitCode() != null) {
                    o.put("exitCode", out.exitCode());
                }
                if (out.stdout() != null) {
                    o.put("stdout", truncate(out.stdout(), LOG_TEXT_TRUNCATE));
                }
                if (out.stderr() != null) {
                    o.put("stderr", truncate(out.stderr(), LOG_TEXT_TRUNCATE));
                }
                if (out.text() != null) {
                    o.put("text", truncate(out.text(), LOG_TEXT_TRUNCATE));
                }
                n.set("output", o);
            }
            if (r.error() != null) {
                ObjectNode e = Json.obj().put("message", orEmpty(r.error().message()));
                if (r.error().exitCode() != null) {
                    e.put("exitCode", r.error().exitCode());
                }
                n.set("error", e);
            }
            runs.add(n);
        }
        return Json.write(Json.obj()
                .put("instanceId", m.id())
                .put("total", filtered.size())
                .set("runs", runs));
    }

    @Tool(description = "查询工作流实例事件流(events 直通,按 seq 升序)。参数 afterSeq 游标(可选,默认 0 从头)、"
            + "limit 条数(可选,缺省 50,上限 200)。返回 {events:[{seq(字符串),ts,event,payload}],nextSeq}——"
            + "seq 是雪花 ID 超精度数,一律字符串,续调用 afterSeq=<上次 nextSeq>。",
            resultConverter = PlainTextToolCallResultConverter.class)
    public String workflow_instance_events(
            @ToolParam(description = "实例 id(wi_ 前缀)") String instanceId,
            @ToolParam(description = "起始 seq 游标(可选,默认 0 从头;续调用传上次 nextSeq)", required = false) Long afterSeq,
            @ToolParam(description = "返回条数上限(可选,缺省 50,上限 200)", required = false) Integer limit) {
        String id = requireInstanceId(instanceId);
        Optional<InstanceMeta> found = store.findInstance(id);
        if (found.isEmpty()) {
            return err("NOT_FOUND: " + id);
        }
        InstanceMeta m = found.get();
        long after = afterSeq != null && afterSeq > 0 ? afterSeq : 0L;
        int lim = limit != null && limit > 0 ? Math.min(limit, EVENTS_MAX_LIMIT) : EVENTS_DEFAULT_LIMIT;
        List<EventLine> events = engine.queryEvents(m.id(), after, 0L, lim);
        ArrayNode arr = Json.arr();
        for (EventLine e : events) {
            arr.add(Json.obj()
                    .put("seq", e.seq() == null ? "0" : e.seq())
                    .put("ts", e.ts() == null ? 0L : e.ts())
                    .put("event", orEmpty(e.event()))
                    .set("payload", e.payload() == null ? Json.obj() : e.payload()));
        }
        String nextSeq = events.isEmpty() ? Long.toString(after)
                : events.get(events.size() - 1).seq();
        return Json.write(Json.obj().set("events", arr).put("nextSeq", nextSeq));
    }

    @Tool(description = "取消一个运行中的工作流实例(中断节点线程,挂起决策作废,实例转 cancelled)。"
            + "已终态返回 {error}。",
            resultConverter = PlainTextToolCallResultConverter.class)
    public String workflow_instance_cancel(
            @ToolParam(description = "实例 id(wi_ 前缀)") String instanceId) {
        String id = requireInstanceId(instanceId);
        if (store.findInstance(id).isEmpty()) {
            return err("NOT_FOUND: " + id);
        }
        try {
            engine.cancel(id, "agent");
        } catch (BadParamsException | dev.everyagent.plugin.api.exception.NotFoundException e) {
            return err(e.getMessage());
        }
        return Json.write(Map.of("instanceId", id, "status", "cancelled", "ok", true));
    }

    // ── 内部 ──────────────────────────────────────────────

    /** 解析 + 校验 + 保存；idOverride 非空时覆盖定义 id（update 语义）。 */
    private String saveDefinitionJson(String idOverride, String definitionText) {
        if (definitionText == null || definitionText.isBlank()) {
            return err("definition 参数不能为空(JSON 文本)");
        }
        Definition draft;
        try {
            draft = DefinitionJson.parseText(definitionText);
        } catch (RuntimeException e) {
            return err("定义 JSON 解析失败: " + e.getMessage());
        }
        if (idOverride != null) {
            draft = new Definition(draft.schemaVersion(), idOverride, draft.name(), draft.description(),
                    draft.workspaceId(), draft.createdAt(), draft.updatedAt(), draft.variables(),
                    draft.onError(), draft.supervisor(), draft.nodes(), draft.edges());
        }
        // 先跑一遍校验拿逐项错误(供模型自纠),再落盘(store 内会再校验一次,双保险)
        List<String> errors = DefinitionValidator.validate(draft);
        if (!errors.isEmpty()) {
            return Json.write(Map.of("error", "定义校验失败", "details", errors));
        }
        try {
            String wsId = workspaceId(null);
            Definition saved = store.saveDefinition(wsId, draft);
            return Json.write(Map.of("id", saved.id()));
        } catch (BadParamsException e) {
            return err(e.getMessage());
        }
    }

    /** 工作区解析：显式 workspace 参数(根路径→注册表 id)优先,缺省=当前任务工作区。 */
    private String workspaceId(String workspace) {
        if (workspace != null && !workspace.isBlank()) {
            String id = ctx.workspaces() == null ? null : ctx.workspaces().idOfRoot(workspace.trim());
            if (id == null) {
                throw new BadParamsException("workspace 未注册或不存在: " + workspace);
            }
            return id;
        }
        String id = ctx.workspaceId();
        if (id == null || id.isBlank()) {
            throw new BadParamsException("无法确定当前工作区(请显式传 workspace 参数)");
        }
        return id;
    }

    private static String requireId(String id) {
        if (id == null || id.isBlank()) {
            throw new BadParamsException("id 不能为空");
        }
        return id.trim();
    }

    private static String err(String message) {
        return Json.write(Map.of("error", message));
    }

    /** variables 参数解析（JSON 文本 {k:v} → 字符串表；缺省空表；非对象/非标量 → BAD_PARAMS）。 */
    private static Map<String, String> variablesOf(String variables) {
        if (variables == null || variables.isBlank()) {
            return Map.of();
        }
        JsonNode node;
        try {
            node = Json.parse(variables);
        } catch (RuntimeException e) {
            throw new BadParamsException("variables 必须是 JSON 对象文本 {k:v}: " + e.getMessage());
        }
        if (!node.isObject()) {
            throw new BadParamsException("variables 必须是 JSON 对象文本 {k:v}");
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

    /**
     * 阻塞至终态（可中断）：latch 等待窗口 + 任务 terminal() 兜底轮询——任务停止的中断
     * 直达 {@code engine.awaitTerminalMeta} 的 latch await（InterruptedException）；
     * 若中断未直达本线程（reactive 工具执行线程切换的场景），任务状态翻终态后由
     * terminal() 轮询兜住，两条路径都走级联取消 + AgentCancelledException。
     */
    private InstanceMeta awaitTerminalInterruptible(String instanceId) throws InterruptedException {
        while (true) {
            InstanceMeta m = engine.awaitTerminalMeta(instanceId, TASK_STOP_POLL_MS);
            if (m != null) {
                return m;
            }
            InstanceMeta current = store.findInstance(instanceId).orElse(null);
            if (current != null && current.status() == InstanceStatus.WAITING_USER) {
                return current;
            }
            if (current == null && !engine.isAlive(instanceId)) {
                return null; // 实例已被删除（终态后）：交 runResult 报不可读
            }
            if (ctx.terminal()) {
                cascadeCancel(instanceId);
                throw new AgentCancelledException("task cancelled");
            }
        }
    }

    /** 任务停止的级联取消（尽力而为：实例已终态/不存在时吞掉，取消语义由任务收口兜底）。 */
    private void cascadeCancel(String instanceId) {
        try {
            engine.cancel(instanceId, "task-stopped");
        } catch (RuntimeException e) {
            // 已终态竞态：取消意图已达,不再抛
        }
    }

    /** workflow_run 终态结果投影（done/failed/cancelled/waiting-user 四形）。 */
    private String runResult(InstanceMeta m) {
        if (m == null) {
            return err("实例终态 meta 不可读(实例可能已被删除)");
        }
        ObjectNode n = Json.obj()
                .put("instanceId", m.id())
                .put("definitionName", orEmpty(m.definitionName()))
                .put("status", statusWire(m.status()))
                .put("durationMs", m.durationMs() == null ? 0L : m.durationMs());
        switch (m.status() == null ? InstanceStatus.CREATED : m.status()) {
            case DONE -> n.set("nodes", nodeSummaries(m));
            case FAILED -> {
                InstanceMeta.Error err = m.error();
                String nodeId = err == null ? null : err.nodeId();
                int runNo = err != null && err.runNo() != null ? err.runNo() : 0;
                n.put("error", err == null || err.message() == null ? "" : err.message());
                if (nodeId != null) {
                    n.put("nodeId", nodeId);
                }
                n.put("runNo", runNo);
                n.put("message", err == null || err.message() == null ? "" : err.message());
                n.set("context", failureContext(m, nodeId, runNo));
                n.put("hint", "可用 workflow_rerun 从中断节点续跑(修正原因后重试该节点,已完成节点不重跑)");
            }
            case CANCELLED -> n.put("note", "实例在运行中被取消(用户取消或任务停止级联取消),可重新 workflow_run 全新运行");
            case WAITING_USER -> {
                if (m.pendingDecision() != null) {
                    n.set("pendingDecision", Json.toJson(m.pendingDecision()));
                }
                n.put("note", "实例挂起等待人工决策(onError=askUser):请把 pendingDecision.question 呈现给用户,"
                        + "决策经工作流实例页或 workflow.instance.decide RPC 落地后,用 workflow_instance_status 查询后续");
            }
            default -> n.put("note", "实例状态: " + statusWire(m.status()));
        }
        return Json.write(n);
    }

    /** 终态节点摘要（nodeStates 投影 + 展示名）。 */
    private ArrayNode nodeSummaries(InstanceMeta m) {
        ArrayNode nodes = Json.arr();
        if (m.nodeStates() == null) {
            return nodes;
        }
        Definition snap = m.definitionSnapshot();
        m.nodeStates().forEach((nodeId, st) -> nodes.add(Json.obj()
                .put("nodeId", nodeId)
                .put("nodeName", snap == null ? nodeId : displayNameOf(snap, nodeId))
                .put("status", st.status() == null ? "" : st.status())
                .put("runCount", st.runCount() == null ? 0 : st.runCount())));
        return nodes;
    }

    /**
     * done 态的未收净告警（BUG-01c）：实例完成但仍有 pending（不可达分支）或 failed
     * （被 onError 分支接住的失败）节点时，返回体不得是裸 done——计数 + 提示引导核对。
     */
    private static String completionWarning(InstanceMeta m) {
        if (m.nodeStates() == null) {
            return null;
        }
        int pending = 0;
        int failed = 0;
        for (InstanceMeta.NodeState st : m.nodeStates().values()) {
            String s = st.status() == null ? "" : st.status();
            if ("pending".equals(s)) {
                pending++;
            } else if ("failed".equals(s)) {
                failed++;
            }
        }
        if (pending == 0 && failed == 0) {
            return null;
        }
        return "实例已完成,但存在未执行/失败节点(pending=" + pending + ", failed=" + failed
                + "):失败被 onError 分支接住或分支未被激活,请核对 nodes 状态与 workflow_instance_log";
    }

    /** 失败节点历史运行输出摘要（该节点全部 runs 的输出裁剪；主 agent 诊断用）。 */
    private ArrayNode failureContext(InstanceMeta m, String nodeId, int failedRunNo) {
        ArrayNode out = Json.arr();
        for (RunRecord r : store.listRunRecords(m.workspaceId(), m.id())) {
            if (nodeId != null && !nodeId.equals(r.nodeId())) {
                continue;
            }
            RunRecord.RunOutput o = r.output();
            String text = o == null ? "" : (o.text() != null ? o.text()
                    : (o.stdout() == null ? "" : o.stdout()) + (o.stderr() == null ? "" : o.stderr()));
            ObjectNode line = Json.obj()
                    .put("runNo", r.runNo() == null ? 0 : r.runNo())
                    .put("status", r.error() == null ? "done" : "failed")
                    .put("output", truncate(text, 400));
            if (r.error() != null) {
                line.put("error", truncate(r.error().message(), 400));
            }
            out.add(line);
        }
        return out;
    }

    private static String requireInstanceId(String instanceId) {
        if (instanceId == null || instanceId.isBlank()) {
            throw new BadParamsException("instanceId 不能为空");
        }
        return instanceId.trim();
    }

    /** 定义快照里节点展示名（缺省回 nodeId；无快照回 nodeId）。 */
    private static String displayNameOf(Definition snap, String nodeId) {
        for (dev.everyagent.plugin.workflow.model.NodeDef n : snap.nodesOrEmpty()) {
            if (n != null && nodeId.equals(n.id())) {
                return n.name() == null || n.name().isBlank() ? nodeId : n.name();
            }
        }
        return nodeId;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…(截断)";
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
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
}
