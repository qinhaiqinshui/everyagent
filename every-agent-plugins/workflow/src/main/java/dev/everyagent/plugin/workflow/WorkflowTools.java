package dev.everyagent.plugin.workflow;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.exception.BadParamsException;
import dev.everyagent.plugin.api.spi.ToolContext;
import dev.everyagent.plugin.api.tool.PlainTextToolCallResultConverter;
import dev.everyagent.plugin.workflow.model.Definition;
import dev.everyagent.plugin.workflow.store.WorkflowStore;
import dev.everyagent.plugin.workflow.validate.DefinitionValidator;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * workflow 定义 CRUD 五工具（hidden：模型经「工作流编排」skill 文档按名调用）。
 *
 * <p>绑定 createHiddenTools 传入的 {@link ToolContext}（随工具回调同生命周期）——
 * <b>零 per-run 捕获</b>，需要工作区时：工具参数显式传 workspace（工作区根绝对路径，
 * 经注册表解析 id）优先，缺省回落当前任务工作区 {@code ctx.workspaceId()}。
 *
 * <p>全部返回<b>纯文本 JSON</b>（PlainTextToolCallResultConverter，防框架把 String 再
 * JSON 编码一层）。错误约定：{@code {"error": "...", "details": [...]}}（details 仅
 * 校验失败带，逐项错误供模型自纠）。
 */
public final class WorkflowTools {

    private final WorkflowStore store;
    private final ToolContext ctx;

    public WorkflowTools(WorkflowStore store, ToolContext ctx) {
        this.store = store;
        this.ctx = ctx;
    }

    @Tool(description = "创建一个可复用的工作流定义(多节点执行 DAG:开始/命令/AI 节点 + 条件边 + 运行变量 + 命名 agent,"
            + "由用户在工作流画布查看与运行)。入参是完整定义 JSON 文本(结构见「工作流编排」技能文档);"
            + "id/createdAt/updatedAt/workspaceId 等系统字段可省略,自动分配。返回 {id};"
            + "校验失败返回 {error, details[逐项原因]}。",
            resultConverter = PlainTextToolCallResultConverter.class)
    public String workflow_create(
            @ToolParam(description = "完整工作流定义 JSON 文本(结构见「工作流编排」技能)") String definition) {
        return saveDefinitionJson(null, definition);
    }

    @Tool(description = "按 id 读取完整工作流定义 JSON(含节点/边/变量/agents 全图)。",
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

    // ── 内部 ──────────────────────────────────────────────

    /** 解析 + 校验 + 保存；idOverride 非空时覆盖定义 id（update 语义）。 */
    private String saveDefinitionJson(String idOverride, String definitionText) {
        if (definitionText == null || definitionText.isBlank()) {
            return err("definition 参数不能为空(JSON 文本)");
        }
        Definition draft;
        try {
            draft = Json.MAPPER.readValue(definitionText, Definition.class);
        } catch (RuntimeException e) {
            return err("定义 JSON 解析失败: " + e.getMessage());
        }
        if (idOverride != null) {
            draft = new Definition(draft.schemaVersion(), idOverride, draft.name(), draft.description(),
                    draft.workspaceId(), draft.createdAt(), draft.updatedAt(), draft.variables(),
                    draft.onError(), draft.supervisor(), draft.agents(), draft.nodes(), draft.edges());
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
}
