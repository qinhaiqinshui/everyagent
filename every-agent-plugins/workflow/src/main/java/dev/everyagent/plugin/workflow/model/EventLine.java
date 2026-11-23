package dev.everyagent.plugin.workflow.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import tools.jackson.databind.JsonNode;

/**
 * 实例事件日志行（instances/&lt;wi&gt;/events.jsonl，每行一事件；追加写、永不修剪）。
 *
 * <p>{@code seq} = {@code SnowflakeId.next()}，wire 上<b>字符串</b>携带（雪花 ID 超 2^53，
 * 防 JS 精度丢失——同 task seq 纪律）。事件全集（wire 契约）：
 * {@code wf.created{instanceId,definitionId,name,variables}} /
 * {@code wf.status{status,error?}} /
 * {@code node.started{nodeId,nodeName,runNo,agent?,inputPreview}} /
 * {@code node.output{nodeId,runNo,exitCode?,outputPreview,activatedEdges[]}} /
 * {@code node.error{nodeId,runNo,message,exitCode?}} /
 * {@code node.skipped{nodeId,runNo}} /
 * {@code supervisor.decision{nodeId,runNo,decision(action,input?),reason?}} /
 * {@code wf.askUser{nodeId,runNo,question,options[],context}} /
 * {@code wf.cancelled{by}} / {@code wf.done{durationMs}}。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EventLine(
        String seq,
        Long ts,
        String event,
        JsonNode payload) {

    /** 数值形态 seq（排序/游标推进用；解析失败由调用方容错跳行）。 */
    public long seqValue() {
        try {
            return Long.parseLong(seq);
        } catch (NumberFormatException e) {
            return -1L;
        }
    }
}
