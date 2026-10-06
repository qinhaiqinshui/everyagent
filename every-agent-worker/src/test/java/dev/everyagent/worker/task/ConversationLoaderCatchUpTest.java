package dev.everyagent.worker.task;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.event.EventRecord;
import dev.everyagent.plugin.api.event.Events;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 运行期会话追回(队列续跑)单元回归——consumeInput 消费下一条输入前,
 * {@link ConversationLoader#catchUpRuntime} 从内存事件日志尾部把上一段落的最终回答轮
 * (无工具调用的收口轮)补进会话内存;工具轮/中断段落/子 agent 事件/幂等边界在此锁定。
 * payload 形态对齐现行发射侧({@code content} + {@code data.toolCalls})。
 */
class ConversationLoaderCatchUpTest {

    private static final String MAIN = "a_main1";

    private static EventRecord rec(long seq, String event, String agentId, String payloadJson) {
        return new EventRecord(seq, seq, event, agentId, Json.parse(payloadJson), null);
    }

    private static List<Message> conv(String... userTexts) {
        List<Message> out = new ArrayList<>();
        for (String t : userTexts) {
            out.add(new UserMessage(t));
        }
        return out;
    }

    @Test
    void finalAnswerCaughtUpBeforeNextUser() {
        List<Message> c = conv("问a");
        List<EventRecord> tail = List.of(
                rec(1, Events.USER_MESSAGE, MAIN, "{\"content\":\"问a\"}"),
                rec(2, Events.MESSAGE, MAIN, "{\"content\":\"答a\",\"data\":{\"toolCalls\":[]}}"),
                rec(3, Events.USAGE, MAIN, "{\"data\":{\"round\":{\"inputTokens\":10}}}"));

        ConversationLoader.catchUpRuntime(c, tail, MAIN);

        assertEquals(2, c.size(), "最终回答轮补进会话");
        AssistantMessage am = assertInstanceOf(AssistantMessage.class, c.get(c.size() - 1));
        assertEquals("答a", am.getText());
        assertTrue(am.getToolCalls() == null || am.getToolCalls().isEmpty());
    }

    @Test
    void idempotentWhenConversationTailAlreadyAssistant() {
        List<Message> c = conv("问a");
        c.add(new AssistantMessage("答a"));

        ConversationLoader.catchUpRuntime(c, List.of(
                rec(2, Events.MESSAGE, MAIN, "{\"content\":\"答a\",\"data\":{}}")), MAIN);

        assertEquals(2, c.size(), "会话尾部已是 assistant(冷启动重建已含/前次已追回)不重复追加");
    }

    @Test
    void interruptedToolRoundYieldsNothing() {
        List<Message> c = conv("问a");
        List<EventRecord> tail = List.of(
                rec(1, Events.USER_MESSAGE, MAIN, "{\"content\":\"问a\"}"),
                rec(2, Events.MESSAGE, MAIN, "{\"content\":\"\",\"data\":{\"toolCalls\":"
                        + "[{\"id\":\"t1\",\"name\":\"fs.read\",\"arguments\":\"{}\"}]}}"),
                rec(3, "tool.result", MAIN, "{\"content\":\"..\"}"));

        ConversationLoader.catchUpRuntime(c, tail, MAIN);

        assertEquals(1, c.size(), "中断于工具轮:无最终回答可追回(与旧 advisor 行为一致)");
    }

    @Test
    void stopsAtPreviousUserMessage() {
        List<Message> c = conv("问a");
        // 上一条输入之后没有任何 message(如运行即刻被取消):追回到窗口起点即止
        ConversationLoader.catchUpRuntime(c, List.of(
                rec(1, Events.USER_MESSAGE, MAIN, "{\"content\":\"问a\"}")), MAIN);

        assertEquals(1, c.size(), "窗口止于上一条 user.message,无可追回");
    }

    @Test
    void subAgentEventsIgnored() {
        List<Message> c = conv("问a");
        List<EventRecord> tail = List.of(
                rec(1, Events.USER_MESSAGE, MAIN, "{\"content\":\"问a\"}"),
                rec(2, Events.MESSAGE, MAIN, "{\"content\":\"答a\",\"data\":{}}"),
                // 主 agent 收口后子 agent 仍在收尾:其 message/usage 不进主会话
                rec(3, Events.MESSAGE, "sub-9", "{\"content\":\"子答\",\"data\":{}}"),
                rec(4, Events.USAGE, "sub-9", "{\"data\":{}}"));

        ConversationLoader.catchUpRuntime(c, tail, MAIN);

        AssistantMessage am = assertInstanceOf(AssistantMessage.class, c.get(c.size() - 1));
        assertEquals("答a", am.getText(), "追回主 agent 的最终回答,子 agent 事件跳过");
    }
}
