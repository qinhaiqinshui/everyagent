package dev.everyagent.worker.task;

import dev.everyagent.contract.json.Json;
import dev.everyagent.worker.proto.Events;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RoundIndexStore 轮次扫描纯单测(无 Spring):
 * 闭合/未闭合轮、子 agent started→done 区间、过程事件不干扰、
 * 用户口径:一轮 = 用户输入 → 直到第一条主 Agent「无工具调用且有正文」的 message;
 * 中间不论是否有用户输入,都属于当前轮。
 */
class RoundIndexStoreTest {

    private static final String MAIN = "a_main1";
    private static final String SUB = "sub_x9";

    private final RoundIndexStore store = new RoundIndexStore();

    private static EventRecord rec(long seq, String event, String agentId, JsonNode payload) {
        return new EventRecord(seq, seq, event, agentId, payload, null);
    }

    private static JsonNode payload(Object... kv) {
        var o = Json.obj();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            Object k = kv[i];
            Object v = kv[i + 1];
            if (v instanceof String s) {
                o.put((String) k, s);
            } else if (v instanceof Long l) {
                o.put((String) k, l);
            } else if (v instanceof Boolean b) {
                o.put((String) k, b);
            } else if (v instanceof JsonNode n) {
                o.set((String) k, n);
            } else {
                throw new IllegalArgumentException("不支持的值类型: " + (v == null ? "null" : v.getClass()));
            }
        }
        return o;
    }

    @Test
    void singleClosedRound() {
        List<EventRecord> events = List.of(
                rec(1, Events.USER_MESSAGE, MAIN, payload("text", "你好")),
                rec(2, Events.THINKING, MAIN, payload("text", "思考中")),
                rec(3, Events.MESSAGE, MAIN, payload("thinking", "思考全文", "text", "回复正文")));
        List<RoundIndex.Round> rounds = store.scan(events, MAIN);
        assertEquals(1, rounds.size());
        RoundIndex.Round r = rounds.get(0);
        assertEquals(1, r.index());
        assertEquals(1, r.startSeq());
        assertEquals(3, r.endSeq());
        assertEquals("你好", r.user());
        assertEquals("回复正文", r.finalReply());
        assertTrue(r.subs().isEmpty());
    }

    @Test
    void multipleRoundsIndexedSequentially() {
        List<EventRecord> events = List.of(
                rec(1, Events.USER_MESSAGE, MAIN, payload("text", "第一问")),
                rec(2, Events.MESSAGE, MAIN, payload("text", "第一答")),
                rec(3, Events.USER_MESSAGE, MAIN, payload("text", "第二问")),
                rec(4, Events.MESSAGE, MAIN, payload("text", "第二答")));
        List<RoundIndex.Round> rounds = store.scan(events, MAIN);
        assertEquals(2, rounds.size());
        RoundIndex.Round r1 = rounds.get(0);
        assertEquals(1, r1.index());
        assertEquals(1, r1.startSeq());
        assertEquals(2, r1.endSeq());
        assertEquals("第一问", r1.user());
        assertEquals("第一答", r1.finalReply());
        RoundIndex.Round r2 = rounds.get(1);
        assertEquals(2, r2.index());
        assertEquals(3, r2.startSeq());
        assertEquals(4, r2.endSeq());
        assertEquals("第二问", r2.user());
        assertEquals("第二答", r2.finalReply());
    }

    @Test
    void unclosedTailRound() {
        List<EventRecord> events = List.of(
                rec(1, Events.USER_MESSAGE, MAIN, payload("text", "第一问")),
                rec(2, Events.MESSAGE, MAIN, payload("text", "第一答")),
                rec(3, Events.USER_MESSAGE, MAIN, payload("text", "未答之问")));
        List<RoundIndex.Round> rounds = store.scan(events, MAIN);
        assertEquals(2, rounds.size());
        RoundIndex.Round open = rounds.get(1);
        assertEquals(2, open.index());
        assertEquals(3, open.startSeq());
        assertNull(open.endSeq(), "无最终回复 → 未闭合");
        assertEquals("未答之问", open.user());
        assertEquals("", open.finalReply(), "未闭合轮 finalReply 为空");
    }

    @Test
    void subAgentStartedDoneWithinRound() {
        List<EventRecord> events = List.of(
                rec(1, Events.USER_MESSAGE, MAIN, payload("text", "让子任务处理")),
                rec(2, Events.AGENT_STARTED, SUB,
                        payload("agentId", SUB, "title", "子任务·代码", "input", "写个函数")),
                rec(3, Events.AGENT_DONE, SUB, payload("agentId", SUB, "result", "完成")),
                rec(4, Events.MESSAGE, MAIN, payload("text", "子任务已完成")));
        List<RoundIndex.Round> rounds = store.scan(events, MAIN);
        assertEquals(1, rounds.size());
        RoundIndex.Round r = rounds.get(0);
        assertEquals(1, r.subs().size());
        RoundIndex.SubRange sub = r.subs().get(0);
        assertEquals(SUB, sub.agentId());
        assertEquals("子任务·代码", sub.title());
        assertEquals(2, sub.startSeq());
        assertEquals(3, sub.endSeq());
        assertEquals(4, r.endSeq());
    }

    @Test
    void subAgentNotDoneKeepsNullEndSeq() {
        List<EventRecord> events = List.of(
                rec(1, Events.USER_MESSAGE, MAIN, payload("text", "派子任务")),
                rec(2, Events.AGENT_STARTED, SUB,
                        payload("agentId", SUB, "title", "未收尾子任务")),
                rec(3, Events.MESSAGE, MAIN, payload("text", "主 agent 先回")));
        List<RoundIndex.Round> rounds = store.scan(events, MAIN);
        assertEquals(1, rounds.size());
        RoundIndex.SubRange sub = rounds.get(0).subs().get(0);
        assertEquals(2, sub.startSeq());
        assertNull(sub.endSeq(), "扫描窗口末尾仍未 done → endSeq null");
    }

    @Test
    void processEventsDoNotInterfereWithRound() {
        List<EventRecord> events = List.of(
                rec(1, Events.USER_MESSAGE, MAIN, payload("text", "问")),
                rec(2, Events.DELTA, MAIN, payload("text", "流")),
                rec(3, Events.THINKING, MAIN, payload("text", "思")),
                rec(4, Events.TOOL_RESULT, MAIN, payload("callId", "c1", "summary", "ok")),
                rec(5, Events.AGENT_STATUS, MAIN, payload("status", "running")),
                rec(6, Events.TASK_TRACE, MAIN, payload("traceId", "t1", "kind", "request_retry")),
                rec(7, Events.USAGE, MAIN, payload("model", "m")),
                rec(8, Events.ASK_CREATE, MAIN, payload("askId", "a1", "kind", "choice")),
                rec(9, Events.ERROR, MAIN, payload("message", "瞬时错误")),
                rec(10, Events.CANCELLED, MAIN, payload("by", "user")),
                rec(11, Events.MESSAGE, MAIN, payload("text", "最终回复")));
        List<RoundIndex.Round> rounds = store.scan(events, MAIN);
        assertEquals(1, rounds.size(), "过程事件不单独成轮");
        RoundIndex.Round r = rounds.get(0);
        assertEquals(1, r.startSeq());
        assertEquals(11, r.endSeq(), "最终回复 seq 落在过程事件之后");
        assertEquals("最终回复", r.finalReply());
    }

    @Test
    void messageWithToolCallsIsNotFinalReply() {
        List<EventRecord> events = List.of(
                rec(1, Events.USER_MESSAGE, MAIN, payload("text", "问")),
                rec(2, Events.MESSAGE, MAIN, payload("thinking", "想", "text", "调工具",
                        "toolCalls", Json.arr().addObject().put("id", "c1").put("name", "bash")
                                .put("arguments", "{}"))),
                rec(3, Events.TOOL_RESULT, MAIN, payload("callId", "c1", "summary", "ok")),
                rec(4, Events.MESSAGE, MAIN, payload("text", "最终回复")));
        List<RoundIndex.Round> rounds = store.scan(events, MAIN);
        assertEquals(1, rounds.size(), "带 toolCalls 的 message 不算最终回复,不成第二轮");
        RoundIndex.Round r = rounds.get(0);
        assertEquals(4, r.endSeq(), "最终回复 = 其后无工具且有正文的那条");
        assertEquals("最终回复", r.finalReply());
    }

    @Test
    void messageWithoutTextIsNotFinalReply() {
        List<EventRecord> events = List.of(
                rec(1, Events.USER_MESSAGE, MAIN, payload("text", "问")),
                rec(2, Events.MESSAGE, MAIN, payload("thinking", "空正文轮")), // 无 text 字段
                rec(3, Events.MESSAGE, MAIN, payload("text", "")), // text 空串
                rec(4, Events.MESSAGE, MAIN, payload("text", "真正回复")));
        List<RoundIndex.Round> rounds = store.scan(events, MAIN);
        assertEquals(1, rounds.size(), "无正文的 message 不算最终回复");
        assertEquals(4, rounds.get(0).endSeq());
        assertEquals("真正回复", rounds.get(0).finalReply());
    }

    @Test
    void subEventsBeforeAnyMainRoundAreIgnored() {
        List<EventRecord> events = List.of(
                rec(1, Events.AGENT_STARTED, SUB, payload("agentId", SUB, "title", "孤儿子任务")),
                rec(2, Events.AGENT_DONE, SUB, payload("agentId", SUB, "result", "x")),
                rec(3, Events.USER_MESSAGE, MAIN, payload("text", "问")),
                rec(4, Events.MESSAGE, MAIN, payload("text", "答")));
        List<RoundIndex.Round> rounds = store.scan(events, MAIN);
        assertEquals(1, rounds.size(), "主 agent 轮之前的子事件被忽略");
        assertTrue(rounds.get(0).subs().isEmpty());
    }

    @Test
    void reindexShiftsIndexByBase() {
        List<RoundIndex.Round> scanned = store.scan(List.of(
                rec(1, Events.USER_MESSAGE, MAIN, payload("text", "问")),
                rec(2, Events.MESSAGE, MAIN, payload("text", "答"))), MAIN);
        List<RoundIndex.Round> shifted = store.reindex(scanned, 5);
        assertEquals(1, shifted.size(), "扫描窗口内只有 1 轮");
        assertEquals(6, shifted.get(0).index(), "已有 5 行 → 新轮从 6 起");
        assertEquals(1, shifted.get(0).startSeq());
        assertEquals("问", shifted.get(0).user());
        assertEquals("答", shifted.get(0).finalReply());
    }

    // ---- 返工:中间用户输入并入当前轮(仅无开着的轮时 user.message 才开轮)----

    @Test
    void intermediateUserMessageMergesIntoCurrentRound() {
        // 一轮内夹第二条用户输入(授权回复/中断后补充):不开新轮,归当前轮过程
        List<RoundIndex.Round> rounds = store.scan(List.of(
                rec(1, Events.USER_MESSAGE, MAIN, payload("text", "第一问")),
                rec(2, Events.MESSAGE, MAIN, payload("thinking", "想", "text", "先调工具",
                        "toolCalls", Json.arr().addObject().put("id", "c1").put("name", "bash")
                                .put("arguments", "{}"))),
                rec(3, Events.TOOL_RESULT, MAIN, payload("callId", "c1", "summary", "ok")),
                rec(4, Events.USER_MESSAGE, MAIN, payload("text", "中间补充输入")),
                rec(5, Events.DELTA, MAIN, payload("text", "续答")),
                rec(6, Events.MESSAGE, MAIN, payload("text", "最终回复"))), MAIN);
        assertEquals(1, rounds.size(), "中间 user.message 不开新轮");
        RoundIndex.Round r = rounds.get(0);
        assertEquals(1, r.startSeq());
        assertEquals(6, r.endSeq());
        assertEquals("第一问", r.user(), "user = 开轮那条输入");
        assertEquals("最终回复", r.finalReply());
    }

    @Test
    void intermediateInputBeforeFinalReplyKeepsSingleRoundAcrossSubAgent() {
        List<RoundIndex.Round> rounds = store.scan(List.of(
                rec(1, Events.USER_MESSAGE, MAIN, payload("text", "主问")),
                rec(2, Events.AGENT_STARTED, SUB, payload("agentId", SUB, "title", "子任务")),
                rec(3, Events.USER_MESSAGE, MAIN, payload("text", "授权回复")),
                rec(4, Events.AGENT_DONE, SUB, payload("agentId", SUB, "result", "ok")),
                rec(5, Events.MESSAGE, MAIN, payload("text", "答"))), MAIN);
        assertEquals(1, rounds.size());
        assertEquals("主问", rounds.get(0).user());
        assertEquals(1, rounds.get(0).subs().size());
    }

    @Test
    void unclosedRoundAfterIntermediateInputStaysSingleOpenRound() {
        // 中间输入后直到末尾都没有最终回复:仍只有开轮那一个未闭合轮
        List<RoundIndex.Round> rounds = store.scan(List.of(
                rec(1, Events.USER_MESSAGE, MAIN, payload("text", "第一问")),
                rec(2, Events.DELTA, MAIN, payload("text", "半截")),
                rec(3, Events.USER_MESSAGE, MAIN, payload("text", "中间补充")),
                rec(4, Events.DELTA, MAIN, payload("text", "又半截"))), MAIN);
        assertEquals(1, rounds.size(), "中间输入不开新轮,尾部未闭合仍是一轮");
        RoundIndex.Round open = rounds.get(0);
        assertNull(open.endSeq());
        assertEquals("第一问", open.user());
    }
}
