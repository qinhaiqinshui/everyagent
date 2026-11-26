package dev.everyagent.worker.task;

import org.springframework.stereotype.Component;

import dev.everyagent.contract.json.Json;
import dev.everyagent.worker.slash.SlashTokenHandler;
import tools.jackson.databind.JsonNode;

/**
 * 「任务引用」胶囊 token(kind={@code task.reference})的提交解析器:@ 弹层选中任务名
 * 建议后插入的<b>中性引用</b>胶囊——前端不做提交解析、opaque 原串上行,由本 resolver
 * (Spring 收集进 {@link SlashTokenHandler},零核心改动)在用户消息进入模型前替换为
 * AI 可读文本「[引用任务] 标题 (taskId=…)」。
 *
 * <p><b>中性引用语义</b>(与 workflow.reference 同思路):解析只留任务标题 + taskId,
 * 不渲染任何工具引导——对该任务做什么(继续会话/读上下文/仅提及)由胶囊外的普通文本
 * 描述,主 agent 依文字自选任务域工具。payload 缺 taskId 时返回 null(handler 兜底
 * 保留原串,绝不误删半成品 token)。
 */
@Component
public class TaskReferenceTokenResolver implements SlashTokenHandler.SlashTokenResolver {

    /** 任务引用 token 的固定 kind(与前端 taskReferenceToken.ts 一致)。 */
    public static final String KIND = "task.reference";

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public String resolveSubmissionText(JsonNode payload) {
        JsonNode p = payload == null ? Json.obj() : payload;
        String taskId = p.path("taskId").asString("");
        if (taskId.isBlank()) {
            return null; // 半成品 payload:保留原串
        }
        String title = p.path("title").asString("");
        String name = title.isBlank() ? "任务 " + taskId : title;
        return "[引用任务] 《" + name + "》(taskId=" + taskId + ")";
    }
}
