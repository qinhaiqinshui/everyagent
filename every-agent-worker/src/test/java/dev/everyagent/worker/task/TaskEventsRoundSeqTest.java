package dev.everyagent.worker.task;

import dev.everyagent.worker.proto.Events.ToolCallPart;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TaskEvents 同轮共享 seq(雪花 ID):同一 agent 一轮的 delta/thinking/message 同 seq;
 * 下一轮重新分配且递增;不同 agent 轮次互不干扰;usage 等其它事件独立 seq;
 * 空 agentId(主 agent 空串写法)与 mainAgentId 归并同轮。
 */
class TaskEventsRoundSeqTest {

    @Test
    void sameRoundSharesSeqAndNextRoundAdvances() {
        EventLog log = new EventLog(1000);
        TaskEvents e = new TaskEvents(log, "mainAgent");
        long d1 = e.delta("agent-a", "你好");
        long t1 = e.thinking("agent-a", "思考中");
        long m1 = e.message("agent-a", "思考全文", "回答正文", List.of());
        assertEquals(d1, t1, "thinking 与首 delta 同轮同 seq");
        assertEquals(d1, m1, "message 与流式 chunk 同轮同 seq");
        // 下一轮:重新分配、递增
        long d2 = e.delta("agent-a", "再次");
        assertNotEquals(d1, d2, "下一轮新 seq");
        assertTrue(d2 > d1, "进程内递增");
        long m2 = e.message("agent-a", "", "再次", List.of());
        assertEquals(d2, m2);
        assertTrue(m2 > m1);
    }

    @Test
    void messageWithoutStreamingAllocatesFreshRoundSeq() {
        EventLog log = new EventLog(1000);
        TaskEvents e = new TaskEvents(log, "mainAgent");
        long m = e.message("agent-a", "思考", "正文", List.of()); // 无流式 chunk
        long m2 = e.message("agent-a", "", "下一轮", List.of());
        assertTrue(m2 > m, "无 chunk 的 message 也要独立轮 seq 且递增");
        assertNotEquals(m, m2);
    }

    @Test
    void differentAgentsHaveIndependentRoundSeqs() {
        EventLog log = new EventLog(1000);
        TaskEvents e = new TaskEvents(log, "mainAgent");
        long a = e.delta("agent-a", "x");
        long b = e.delta("agent-b", "y");
        assertNotEquals(a, b, "不同 agent 各自轮 seq");
        long ma = e.message("agent-a", "", "x", List.of());
        long mb = e.message("agent-b", "", "y", List.of());
        assertEquals(a, ma);
        assertEquals(b, mb);
    }

    @Test
    void usageAndUserMessageGetIndependentSeqs() {
        EventLog log = new EventLog(1000);
        TaskEvents e = new TaskEvents(log, "mainAgent");
        long d = e.delta("agent-a", "x");
        long m = e.message("agent-a", "", "x",
                List.of(new ToolCallPart("c1", "bash", "{}")));
        assertEquals(d, m, "同轮共享");
        long u = e.usage("agent-a", "gpt", 100_000L,
                new dev.everyagent.worker.proto.TaskDtos.Usage(10, 20, 30),
                new dev.everyagent.worker.proto.TaskDtos.Usage(10, 20, 30));
        assertTrue(u > m, "usage 独立雪花 seq 不与 message 共享");
        long um = e.userMessage("用户新输入");
        assertTrue(um > u, "user.message 独立递增");
    }

    @Test
    void emptyAgentIdMapsToMainRound() {
        EventLog log = new EventLog(1000);
        TaskEvents e = new TaskEvents(log, "mainAgent");
        long d = e.delta("", "x"); // 主 agent 空串写法
        long m = e.message("mainAgent", "", "x", List.of()); // mainAgentId 写法
        assertEquals(d, m, "空 agentId 与 mainAgentId 归并同一轮");
    }
}
