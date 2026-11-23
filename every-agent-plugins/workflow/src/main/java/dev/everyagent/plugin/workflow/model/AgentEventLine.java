package dev.everyagent.plugin.workflow.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import tools.jackson.databind.JsonNode;

/**
 * 命名 agent 会话日志行（instances/&lt;wi&gt;/agents/&lt;name&gt;.jsonl——照抄 task 域
 * &lt;agentId&gt;.jsonl：行=任务流事件、追加写永不重写、行内记 agentId）。
 *
 * <p>事件名与 payload 形态逐字对齐 task 域任务流事件（user.message / message /
 * tool.result / usage）；delta/thinking 瞬态不落盘。agentId 形如
 * {@code writer#wi_x9y8z}（归户键），磁盘文件名用裸 agentName（人工可读）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentEventLine(
        String seq,
        Long ts,
        String event,
        String agentId,
        JsonNode payload) {

    /** 数值形态 seq（排序用）。 */
    public long seqValue() {
        try {
            return Long.parseLong(seq);
        } catch (NumberFormatException e) {
            return -1L;
        }
    }
}
