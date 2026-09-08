package dev.everyagent.worker.task;

import tools.jackson.databind.JsonNode;

/**
 * 事件日志单条记录(架构 §5.3):seq 为 Snowflake ID(long,进程内严格单调递增、不重复);
 * 同一轮 AI 回复的流式 chunk 与定型 message 共享同一 seq;seq 有空洞(瞬态占号但部分不落盘)。
 */
public record EventRecord(long seq, long ts, String event, String agentId, JsonNode payload, JsonNode ext) {
}
