package dev.everyagent.plugin.workflow.slash;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.slash.SlashTokenEncoder;

/**
 * 「工作流引用」胶囊 token 的 worker 侧实现（opaque token 三要素——固定 kind、构造；
 * 命中扫描不需要，token 命中统一走 {@code SlashTokenResolveAdvisor} 扫描分发）。
 *
 * <p><b>中性引用语义</b>（原「运行工作流」token 引用化改造）：本 token 只承载
 * 「引用了哪个工作流」——label/summary 明文 + payload {@code definitionId/definitionName}，
 * <b>不含运行参数</b>（参数写入 token 外的普通文字）；对该工作流做什么操作（运行/删除/
 * 重跑…）由胶囊外的普通文本描述，主 agent 依文字自选 hidden 工具
 * （workflow_run / workflow_delete / workflow_rerun…）。提交解析见
 * {@link WorkflowReferenceSlashResolver}。
 *
 * <p>{@link #LEGACY_KIND}（{@code workflow.run}）：引用化之前的旧 kind——resolver
 * 以 LEGACY_KIND 再注册一份保证旧草稿/重发消息仍可解析（渲染为同样的引用文本，
 * 旧 payload 里的 variables 被有意丢弃）。
 */
public final class WorkflowReferenceToken {

    /** 工作流引用 token 的固定 kind（前端构造 / worker 提交解析共用，逐字一致）。 */
    public static final String KIND = "workflow.reference";

    /** 引用化之前的旧 kind（兼容解析注册用；新构造一律用 {@link #KIND}）。 */
    public static final String LEGACY_KIND = "workflow.run";

    private WorkflowReferenceToken() {
    }

    /**
     * 构造「工作流『工作流名』」胶囊的 opaque token（中性引用，无参数）。
     *
     * @param definitionId    定义 id（wf_ 前缀；payload 必带）
     * @param definitionName  定义名（空/null 时 label 退用 definitionId，payload 原样携带
     *                        空串——resolver 侧可经 WorkflowStore 反查补全）
     */
    public static String buildToken(String definitionId, String definitionName) {
        String name = definitionName == null || definitionName.isBlank()
                ? orId(definitionId) : definitionName;
        return SlashTokenEncoder.buildToken(
                KIND,
                "工作流「" + name + "」",
                definitionId == null ? "" : definitionId,
                Json.obj()
                        .put("definitionId", definitionId == null ? "" : definitionId)
                        .put("definitionName", definitionName == null ? "" : definitionName));
    }

    /** label 用的展示名兜底：定义名空 → definitionId（仍空 → 占位，防 buildToken 抛 label 空）。 */
    private static String orId(String definitionId) {
        return definitionId == null || definitionId.isBlank() ? "未知工作流" : definitionId;
    }
}
