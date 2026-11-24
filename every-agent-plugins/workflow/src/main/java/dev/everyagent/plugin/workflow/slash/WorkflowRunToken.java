package dev.everyagent.plugin.workflow.slash;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.slash.SlashTokenEncoder;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 「运行工作流」胶囊 token 的 worker 侧实现（仿 {@code taskwait.WaitTaskToken}：
 * opaque token 三要素——固定 kind、构造；命中扫描不需要，token 命中统一走
 * {@code SlashTokenResolveAdvisor} 扫描分发，本类不另设 scan 辅助）。
 *
 * <p>职责：
 * <ul>
 *   <li>固定 {@link #KIND} = {@code workflow.run}（与前端 RunDialog 组装的
 *       {@code ctx.ui.buildComposerToken} kind 逐字一致）；</li>
 *   <li>{@link #buildToken} 构造自包含 opaque token（顶层 label/summary 明文，
 *       payload base64url），payload 携带 {@code definitionId} +
 *       {@code definitionName} + {@code variables}——三字段名与前端 payload
 *       对象逐字一致，序列化（字段序、base64url 规则）与前端
 *       {@code composerToken/composerOpaqueToken.buildOpaqueTokenText} 逐字节对齐：
 *       相同入参两侧产出的 token 串相同。</li>
 * </ul>
 *
 * <p>与 task.wait 的差异：本 token 承载<b>需 AI 理解</b>的内容（哪个工作流、什么参数），
 * 提交解析由 {@link WorkflowRunSlashResolver} 把 payload 渲染为「工作流名 + 参数 +
 * 调用 workflow_run 引导」的可读文本<b>替换</b>原串（task.wait 是触发标记、返回空串剥离）。
 * 真正的运行执行由主 agent 调 {@code workflow_run} hidden 工具完成。
 */
public final class WorkflowRunToken {

    /** 运行工作流 token 的固定 kind（前端构造 / worker 提交解析共用，逐字一致）。 */
    public static final String KIND = "workflow.run";

    private WorkflowRunToken() {
    }

    /**
     * 构造「运行『工作流名』」胶囊的 opaque token。
     *
     * @param definitionId    定义 id（wf_ 前缀；payload 必带）
     * @param definitionName  定义名（空/null 时 label 退用 definitionId，payload 原样携带
     *                        空串——resolver 侧可经 WorkflowStore 反查补全）
     * @param variables       运行变量（null 安全=空表；LinkedHashMap 保序，
     *                        与前端表单声明序一致）
     */
    public static String buildToken(String definitionId, String definitionName,
            Map<String, String> variables) {
        String name = definitionName == null || definitionName.isBlank()
                ? orId(definitionId) : definitionName;
        return SlashTokenEncoder.buildToken(
                KIND,
                "运行「" + name + "」",
                summarizeVariables(variables),
                Json.obj()
                        .put("definitionId", definitionId == null ? "" : definitionId)
                        .put("definitionName", definitionName == null ? "" : definitionName)
                        .set("variables", Json.toJson(variables == null ? Map.of() : variables)));
    }

    /** label 用的展示名兜底：定义名空 → definitionId（仍空 → 占位，防 buildToken 抛 label 空）。 */
    private static String orId(String definitionId) {
        return definitionId == null || definitionId.isBlank() ? "未知工作流" : definitionId;
    }

    /**
     * 参数摘要（token summary 与 resolver 文案共用口径）：
     * {@code 参数: k=v; k2=v2}（「; 」分隔，保插入序）；无参数给「无参数」。
     */
    static String summarizeVariables(Map<String, String> variables) {
        Map<String, String> vars = variables == null ? Map.of() : variables;
        if (vars.isEmpty()) {
            return "无参数";
        }
        StringBuilder sb = new StringBuilder("参数: ");
        boolean first = true;
        for (Map.Entry<String, String> e : new LinkedHashMap<>(vars).entrySet()) {
            if (!first) {
                sb.append("; ");
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
            first = false;
        }
        return sb.toString();
    }
}
