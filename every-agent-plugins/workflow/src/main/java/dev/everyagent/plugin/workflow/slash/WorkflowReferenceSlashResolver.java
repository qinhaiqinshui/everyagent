package dev.everyagent.plugin.workflow.slash;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.slash.SlashTokenResolver;
import dev.everyagent.plugin.workflow.store.WorkflowStore;
import tools.jackson.databind.JsonNode;

/**
 * 「工作流引用」token 的提交解析器（kind 由构造参数指定：新 kind=
 * {@code workflow.reference} 与兼容注册的 LEGACY {@code workflow.run} 各注册一份）。
 *
 * <p><b>中性引用渲染</b>（原 WorkflowRunSlashResolver 的「运行引导」语义移除）：
 * 替换文本只留<b>工作流名 + definitionId</b>——
 * {@code [引用工作流] 《名》(definitionId=wf_xxx)}；不渲染参数（token 已不携带，
 * 引用化后参数写在普通文字里）、不渲染 workflow_run 工具引导（对该工作流做什么
 * 操作由胶囊外的普通文本描述，主 agent 依文字自选 hidden 工具）。定义名缺失时经
 * {@link WorkflowStore} 反查补全（构造持 store）。旧 LEGACY payload 即使残留
 * variables 也照此渲染（有意丢弃）。
 */
public class WorkflowReferenceSlashResolver implements SlashTokenResolver {

    private final WorkflowStore store;

    /** 本实例服务的 kind（{@link WorkflowReferenceToken#KIND} 或 {@link #LEGACY_KIND}）。 */
    private final String kind;

    public WorkflowReferenceSlashResolver(WorkflowStore store, String kind) {
        this.store = store;
        this.kind = kind;
    }

    @Override
    public String kind() {
        return kind;
    }

    @Override
    public String resolveSubmissionText(JsonNode payload) {
        JsonNode p = payload == null ? Json.obj() : payload;
        String definitionId = p.path("definitionId").asString("");
        String name = displayName(p.path("definitionName").asString(""), definitionId);
        return "[引用工作流] 《" + name + "》(definitionId=" + (definitionId.isBlank() ? "未知" : definitionId) + ")";
    }

    /** 展示名：payload 定义名 → store 按 id 反查 → definitionId → 占位（恒非空）。 */
    private String displayName(String definitionName, String definitionId) {
        if (definitionName != null && !definitionName.isBlank()) {
            return definitionName;
        }
        if (store != null && definitionId != null && !definitionId.isBlank()) {
            String found = store.findDefinition(definitionId)
                    .map(d -> d.name() == null ? "" : d.name())
                    .orElse("");
            if (!found.isBlank()) {
                return found;
            }
        }
        if (definitionId != null && !definitionId.isBlank()) {
            return definitionId;
        }
        return "未知工作流";
    }
}
