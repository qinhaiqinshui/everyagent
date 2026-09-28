package dev.everyagent.worker.task;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.model.EmitEvent;
import dev.everyagent.worker.proto.Events;
import dev.everyagent.worker.proto.Events.ToolCallPart;
import dev.everyagent.worker.proto.SnowflakeId;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TaskEvents emit 统一入口:同一轮的 delta/thinking/message 共享同一 id(= seq);
 * 下一轮重新分配且递增;不同 agent 轮次互不干扰;usage 等其它事件独立 seq;
 * 空 agentId(主 agent)由 TaskEvents 兜底填入 mainAgentId。
 */
class TaskEventsRoundSeqTest {

    /** message 事件的 data:thinking + toolCalls。 */
    private static ObjectNode msgData(String thinking, List<ToolCallPart> toolCalls) {
        ObjectNode d = Json.obj();
        if (thinking != null && !thinking.isEmpty()) {
            d.put("thinking", thinking);
        }
        d.set("toolCalls", EventPayloads.toolCallsToJson(toolCalls));
        return d;
    }

    @Test
    void sameRoundSharesSeqAndNextRoundAdvances() {
        EventLog log = new EventLog(1000);
        TaskEvents e = new TaskEvents(log, "mainAgent");
        long rid = SnowflakeId.next();
        long d1 = e.emit(EmitEvent.transientOf(rid, Events.DELTA, "agent-a",
                null, null, "你好", null, null, EmitEvent.Mode.APPEND));
        long t1 = e.emit(EmitEvent.transientOf(rid, Events.THINKING, "agent-a",
                null, null, "思考中", null, null, EmitEvent.Mode.APPEND));
        long m1 = e.emit(EmitEvent.of(rid, Events.MESSAGE, "agent-a",
                null, null, "回答正文", null,
                msgData("思考全文", List.of()), EmitEvent.Mode.REPLACE));
        assertEquals(d1, t1, "thinking 与首 delta 同轮同 seq");
        assertEquals(d1, m1, "message 与流式 chunk 同轮同 seq");
        // 下一轮:重新分配、递增
        long rid2 = SnowflakeId.next();
        long d2 = e.emit(EmitEvent.transientOf(rid2, Events.DELTA, "agent-a",
                null, null, "再次", null, null, EmitEvent.Mode.APPEND));
        assertNotEquals(d1, d2, "下一轮新 seq");
        assertTrue(d2 > d1, "进程内递增");
        long m2 = e.emit(EmitEvent.of(rid2, Events.MESSAGE, "agent-a",
                null, null, "再次", null,
                msgData("", List.of()), EmitEvent.Mode.REPLACE));
        assertEquals(d2, m2);
        assertTrue(m2 > m1);
    }

    @Test
    void messageWithoutStreamingAllocatesFreshRoundSeq() {
        EventLog log = new EventLog(1000);
        TaskEvents e = new TaskEvents(log, "mainAgent");
        long rid = SnowflakeId.next();
        long m = e.emit(EmitEvent.of(rid, Events.MESSAGE, "agent-a",
                null, null, "正文", null,
                msgData("思考", List.of()), EmitEvent.Mode.REPLACE)); // 无流式 chunk
        long rid2 = SnowflakeId.next();
        long m2 = e.emit(EmitEvent.of(rid2, Events.MESSAGE, "agent-a",
                null, null, "下一轮", null,
                msgData("", List.of()), EmitEvent.Mode.REPLACE));
        assertTrue(m2 > m, "无 chunk 的 message 也要独立轮 seq 且递增");
        assertNotEquals(m, m2);
    }

    @Test
    void differentAgentsHaveIndependentRoundSeqs() {
        EventLog log = new EventLog(1000);
        TaskEvents e = new TaskEvents(log, "mainAgent");
        long ridA = SnowflakeId.next();
        long ridB = SnowflakeId.next();
        long a = e.emit(EmitEvent.transientOf(ridA, Events.DELTA, "agent-a",
                null, null, "x", null, null, EmitEvent.Mode.APPEND));
        long b = e.emit(EmitEvent.transientOf(ridB, Events.DELTA, "agent-b",
                null, null, "y", null, null, EmitEvent.Mode.APPEND));
        assertNotEquals(a, b, "不同 agent 各自轮 seq");
        long ma = e.emit(EmitEvent.of(ridA, Events.MESSAGE, "agent-a",
                null, null, "x", null, msgData("", List.of()), EmitEvent.Mode.REPLACE));
        long mb = e.emit(EmitEvent.of(ridB, Events.MESSAGE, "agent-b",
                null, null, "y", null, msgData("", List.of()), EmitEvent.Mode.REPLACE));
        assertEquals(a, ma);
        assertEquals(b, mb);
    }

    @Test
    void usageAndUserMessageGetIndependentSeqs() {
        EventLog log = new EventLog(1000);
        TaskEvents e = new TaskEvents(log, "mainAgent");
        long rid = SnowflakeId.next();
        long d = e.emit(EmitEvent.transientOf(rid, Events.DELTA, "agent-a",
                null, null, "x", null, null, EmitEvent.Mode.APPEND));
        long m = e.emit(EmitEvent.of(rid, Events.MESSAGE, "agent-a",
                null, null, "x", null,
                msgData("", List.of(new ToolCallPart("c1", "bash", "{}"))),
                EmitEvent.Mode.REPLACE));
        assertEquals(d, m, "同轮共享");
        var usageData = Json.obj();
        usageData.put("model", "gpt");
        usageData.put("contextWindowTokens", 100_000L);
        usageData.set("round", Json.toJson(
                new dev.everyagent.worker.proto.TaskDtos.Usage(10, 20, 30)));
        usageData.set("total", Json.toJson(
                new dev.everyagent.worker.proto.TaskDtos.Usage(10, 20, 30)));
        long u = e.emit(EmitEvent.of(SnowflakeId.next(), Events.USAGE, "agent-a",
                null, null, null, null, usageData, EmitEvent.Mode.REPLACE));
        assertTrue(u > m, "usage 独立雪花 seq 不与 message 共享");
        long um = e.emit(EmitEvent.of(SnowflakeId.next(), Events.USER_MESSAGE, null,
                null, null, "用户新输入", null, null, EmitEvent.Mode.REPLACE));
        assertTrue(um > u, "user.message 独立递增");
    }

    @Test
    void emptyAgentIdMapsToMainRound() {
        EventLog log = new EventLog(1000);
        TaskEvents e = new TaskEvents(log, "mainAgent");
        long rid = SnowflakeId.next();
        // 主 agent:null agentId → TaskEvents 兜底填 mainAgentId
        long d = e.emit(EmitEvent.transientOf(rid, Events.DELTA, null,
                null, null, "x", null, null, EmitEvent.Mode.APPEND));
        long m = e.emit(EmitEvent.of(rid, Events.MESSAGE, "mainAgent",
                null, null, "x", null, msgData("", List.of()), EmitEvent.Mode.REPLACE));
        assertEquals(d, m, "null agentId 与 mainAgentId 归并同一轮(同 id)");
    }
}
