package dev.everyagent.plugin.workflow.slash;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.slash.SlashTokenResolver;
import dev.everyagent.plugin.workflow.store.WorkflowStore;
import tools.jackson.databind.JsonNode;

import java.util.Map;

/**
 * 「运行工作流」token 的提交解析器（kind={@code workflow.run}，架构 §7.20
 * 运行入口统一走任务）。
 *
 * <p>与 task.wait / unattended.mode 的「返回空串=剥离」不同：本 token 承载需 AI
 * 理解的运行意图，{@link #resolveSubmissionText} 把 payload 渲染为<b>可读替换文本</b>
 * （SlashTokenResolver javadoc 的第二种合法返回值）——工作流名 + definitionId +
 * 参数，并引导主 agent 调用 {@code workflow_run} hidden 工具执行（variables 原样
 * 作为工具参数传入）。定义名缺失时可经 {@link WorkflowStore} 反查补全（构造持 store）。
 *
 * <p>消费链：任务页「运行」按钮组装「引导语 + token 原串」发 {@code task.run} →
 * worker {@code SlashTokenResolveAdvisor}（复用，不新增 Advisor）扫描用户消息按 kind
 * 分发到本 resolver → 替换文本进模型上下文 → 主 agent 调 workflow_run。
 */
public class WorkflowRunSlashResolver implements SlashTokenResolver {

    private final WorkflowStore store;

    public WorkflowRunSlashResolver(WorkflowStore store) {
        this.store = store;
    }

    @Override
    public String kind() {
        return WorkflowRunToken.KIND;
    }

    @Override
    public String resolveSubmissionText(JsonNode payload) {
        JsonNode p = payload == null ? Json.obj() : payload;
        String definitionId = p.path("definitionId").asString("");
        String name = displayName(p.path("definitionName").asString(""), definitionId);
        String params = paramsLine(p.path("variables"));
        return "[运行工作流] 《" + name + "》(definitionId=" + definitionId + "; " + params + ")"
                + "——请调用 workflow_run 工具执行该工作流"
                + "（把 variables 原样作为 variables 参数传入；如缺参数请先向用户确认）";
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

    /** 参数行：{@code 参数: k=v, k2=v2}（与 token summary 的「; 」分隔不同——替换文本内层用「, 」，
     *  避免与外层「; 」嵌套混淆）；无参数给「无参数」。 */
    private static String paramsLine(JsonNode variables) {
        StringBuilder sb = new StringBuilder("参数: ");
        boolean first = true;
        if (variables != null && variables.isObject()) {
            for (Map.Entry<String, JsonNode> e : variables.properties()) {
                if (!first) {
                    sb.append(", ");
                }
                sb.append(e.getKey()).append('=').append(e.getValue().asString(""));
                first = false;
            }
        }
        return first ? "无参数" : sb.toString();
    }

}
