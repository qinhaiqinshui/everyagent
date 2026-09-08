package dev.everyagent.worker.task;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上下文压缩核心算法单测:未触发直通、三阶段逐级升级、工具调用配对完整性。
 * 消息 token 用「x 个英文字符 ≈ bytes/3」构造;trigger/target/reserve 直接入参,不依赖窗口。
 */
class ContextCompressorTest {

    // ---- 构造辅助 ----

    private static UserMessage user(String s) {
        return new UserMessage(s);
    }

    private static AssistantMessage assistant(String s) {
        return AssistantMessage.builder().content(s).build();
    }

    private static AssistantMessage assistantTool(String id, String name, String args) {
        return AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", name, args)))
                .build();
    }

    private static ToolResponseMessage toolResp(String id, String name, String data) {
        return ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(id, name, data)))
                .build();
    }

    /** 生成长度为 n 的英文字符串(≈ n/3 token)。 */
    private static String txt(int n) {
        return "x".repeat(Math.max(0, n));
    }

    // ---- 用例 ----

    @Test
    void belowTriggerReturnsUnchanged() {
        List<Message> msgs = new ArrayList<>();
        msgs.add(user("你好"));
        msgs.add(assistant(txt(300))); // 约 100 token

        ContextCompressor.Result r = ContextCompressor.compress(msgs, 300, 150, 0);
        assertFalse(r.compressed(), "未超过触发阈值不应压缩");
        assertEquals(0, r.stage());
    }

    @Test
    void stageAKeepsHistoryUserAndFinalReplacesToolCalls() {
        // 历史轮:user + assistant(toolCall) + toolResult + final;本轮:user + final
        List<Message> msgs = new ArrayList<>();
        msgs.add(user("历史问题" + txt(300)));              // ~100t
        msgs.add(assistantTool("c1", "fs_read", txt(150))); // ~50t
        msgs.add(toolResp("c1", "fs_read", txt(150)));      // ~50t
        msgs.add(assistant("历史最终回复" + txt(300)));        // ~100t
        msgs.add(user("本轮问题" + txt(150)));               // ~50t
        msgs.add(assistant("本轮回复" + txt(300)));          // ~100t

        long used = ContextCompressor.estimateTokens(msgs); // 约 450t
        // 触发阈值小于当前用量,目标阈值让「历史轮(user+占位+final)+本轮」压缩后达标
        ContextCompressor.Result r = ContextCompressor.compress(msgs, used - 1, 400, 0);
        assertTrue(r.compressed());
        assertEquals(1, r.stage(), "应停在阶段 A");
        // 历史轮工具调用被替换:结果不含 toolCall 与 toolResponse 内容,含占位标记
        String joined = r.messages().toString();
        assertFalse(joined.contains("fs_read"), "历史轮工具调用不应进入上下文");
        assertTrue(joined.contains(ContextCompressor.PLACEHOLDER), "工具调用应以占位标记");
        assertTrue(joined.contains("历史问题"), "历史轮 user 保留");
        assertTrue(joined.contains("历史最终回复"), "历史轮 AI 最终回复保留");
        assertTrue(joined.contains("本轮回复"), "本轮完整保留");
    }

    @Test
    void stageBTrimsFirstHalfOfCurrentTurnToolPairs() {
        // 本轮 3 对工具调用:drop ceil(3/2)=2 对,保留最后 1 对
        List<Message> msgs = new ArrayList<>();
        msgs.add(user("本轮" + txt(150)));                   // ~50t
        for (int i = 1; i <= 3; i++) {
            msgs.add(assistantTool("t" + i, "tool" + i, txt(150)));
            msgs.add(toolResp("t" + i, "tool" + i, "data" + i + txt(150)));
        }
        msgs.add(assistant("最终" + txt(150)));              // ~50t

        ContextCompressor.Result r = ContextCompressor.compress(msgs, 200, 250, 0);
        assertTrue(r.compressed());
        assertEquals(2, r.stage());
        assertEquals(2, r.droppedCurrentToolPairs());
        String joined = r.messages().toString();
        assertFalse(joined.contains("tool1"), "前 50% 工具调用对(第1、2对)被压缩");
        assertFalse(joined.contains("data1"));
        assertFalse(joined.contains("data2"));
        assertTrue(joined.contains("tool3"), "后 50%(最新)工具结果保留");
        assertTrue(joined.contains("data3"));
        assertTrue(joined.contains("最终"), "本轮最终回复保留");
    }

    @Test
    void stageCDropsOldestHistoryTurnsUntilTarget() {
        // 两个大历史轮 + 小编本轮:阶段A/B不足以降到 target,触发阶段C逐轮删历史
        List<Message> msgs = new ArrayList<>();
        msgs.add(user("H1Q" + txt(600)));   // ~200t
        msgs.add(assistant("H1A" + txt(600))); // ~200t
        msgs.add(user("H2Q" + txt(600)));   // ~200t
        msgs.add(assistant("H2A" + txt(600))); // ~200t
        msgs.add(user("CURQ" + txt(150)));  // ~50t
        msgs.add(assistant("CURA" + txt(150))); // ~50t

        ContextCompressor.Result r = ContextCompressor.compress(msgs, 600, 150, 0);
        assertTrue(r.compressed());
        assertEquals(3, r.stage());
        assertTrue(r.droppedHistoryTurns() >= 1, "阶段C应删除最旧历史轮");
        String joined = r.messages().toString();
        // 新行为:被删历史轮不再彻底丢弃,以「更早历史」user 截断简版保留
        assertTrue(joined.contains(ContextCompressor.EARLIER_HISTORY_PREFIX),
                "被删历史轮应以【更早历史】简版保留,防永久失忆");
        assertTrue(joined.contains("H1Q"), "被删历史轮 user 开头片段保留");
        assertFalse(joined.contains("H1A"), "被删历史轮的 AI 最终回复不再保留全文本");
        assertTrue(joined.contains("CURQ"), "本轮保留");
        // tool_call/result 配对铁律不被简版破坏
        List<Message> m = r.messages();
        for (int i = 0; i < m.size(); i++) {
            if (m.get(i) instanceof ToolResponseMessage) {
                assertTrue(i > 0 && m.get(i - 1) instanceof AssistantMessage am
                                && am.getToolCalls() != null && !am.getToolCalls().isEmpty(),
                        "toolResponse 不得独立存在,必须与其 assistant(toolCall) 同留同弃");
            }
        }
    }

    @Test
    void stageCUsesSummarizerWhenAvailable() {
        // 与上方同构:阶段 C 触发且摘要器可用 → 用单条【历史摘要】代替多轮简版
        List<Message> msgs = new ArrayList<>();
        msgs.add(user("S1Q" + txt(600)));
        msgs.add(assistant("S1A" + txt(600)));
        msgs.add(user("S2Q" + txt(600)));
        msgs.add(assistant("S2A" + txt(600)));
        msgs.add(user("CURQ" + txt(150)));
        msgs.add(assistant("CURA" + txt(150)));

        ContextSummarizer summarizer = (text, maxTokens) -> "关键摘要ABC";
        ContextCompressor.Result r = ContextCompressor.compress(msgs, 600, 150, 0, summarizer);
        assertTrue(r.compressed());
        assertEquals(3, r.stage());
        String joined = r.messages().toString();
        assertTrue(joined.contains(ContextCompressor.HISTORY_SUMMARY_PREFIX + "关键摘要ABC"),
                "有摘要器时用【历史摘要】");
        assertFalse(joined.contains(ContextCompressor.EARLIER_HISTORY_PREFIX),
                "摘要器成功时不再保留多轮简版");
    }

    @Test
    void stageCFallsBackToBriefWhenSummarizerEmptyOrError() {
        // 摘要器返回空串 / 抛异常 → 降级为【更早历史】简版,绝不影响压缩主流程
        List<Message> msgs = new ArrayList<>();
        msgs.add(user("F1Q" + txt(600)));
        msgs.add(assistant("F1A" + txt(600)));
        msgs.add(user("F2Q" + txt(600)));
        msgs.add(assistant("F2A" + txt(600)));
        msgs.add(user("CURQ" + txt(150)));
        msgs.add(assistant("CURA" + txt(150)));

        // 空摘要 → 简版
        ContextCompressor.Result rEmpty = ContextCompressor.compress(msgs, 600, 150, 0,
                (text, maxTokens) -> "");
        assertTrue(rEmpty.compressed());
        assertTrue(rEmpty.messages().toString().contains(ContextCompressor.EARLIER_HISTORY_PREFIX),
                "空摘要降级为【更早历史】");
        assertFalse(rEmpty.messages().toString().contains(ContextCompressor.HISTORY_SUMMARY_PREFIX));

        // 异常 → 简版
        ContextCompressor.Result rErr = ContextCompressor.compress(msgs, 600, 150, 0,
                (text, maxTokens) -> { throw new RuntimeException("mock 失败"); });
        assertTrue(rErr.compressed());
        assertTrue(rErr.messages().toString().contains(ContextCompressor.EARLIER_HISTORY_PREFIX),
                "异常降级为【更早历史】");
        assertFalse(rErr.messages().toString().contains(ContextCompressor.HISTORY_SUMMARY_PREFIX));
    }

    @Test
    void recompressBaselineIsIdempotent() {
        // 首次压缩停在阶段 A(历史轮工具调用被替换为占位符);
        // 再压缩时占位符作为普通 system 消息参与轮次切分,不得再次包裹成嵌套占位符。
        List<Message> msgs = new ArrayList<>();
        msgs.add(new SystemMessage("sys" + txt(100)));
        msgs.add(user("历史" + txt(300)));
        msgs.add(assistantTool("c1", "fs_read", txt(150)));
        msgs.add(toolResp("c1", "fs_read", txt(150)));
        msgs.add(assistant("历史最终" + txt(300)));
        msgs.add(user("本轮" + txt(150)));
        msgs.add(assistant("本轮最终" + txt(150)));

        long used = ContextCompressor.estimateTokens(msgs);
        // target 足够高,让阶段 A 即可达标 → first 结果必含 PLACEHOLDER
        ContextCompressor.Result first = ContextCompressor.compress(msgs, used - 1, used, 0);
        assertEquals(1, first.stage(), "首次压缩应停在阶段 A");
        assertTrue(first.messages().toString().contains(ContextCompressor.PLACEHOLDER));

        long used2 = ContextCompressor.estimateTokens(first.messages());
        ContextCompressor.Result second = ContextCompressor.compress(first.messages(), used2 - 1, used2 + 10, 0);
        assertEquals(1, second.stage());
        String joined = second.messages().toString();
        assertTrue(joined.contains("sys"), "system 前缀恒保留");
        assertTrue(joined.contains(ContextCompressor.PLACEHOLDER), "再压缩后仍保留占位符");
        assertFalse(joined.contains(ContextCompressor.PLACEHOLDER + ContextCompressor.PLACEHOLDER),
                "占位符不得嵌套/重复");
    }

    @Test
    void truncateHugeToolResultsKeepsListIntactAndDoesNotMutate() {
        List<Message> msgs = new ArrayList<>();
        String huge = "HEAD" + txt(100_000) + "TAIL";
        msgs.add(user("说明"));
        msgs.add(toolResp("t1", "big_tool", huge));
        msgs.add(assistant("收尾"));

        List<Message> out = ContextCompressor.truncateHugeToolResults(msgs, 4096);

        assertEquals(msgs.size(), out.size(), "列表大小不变");
        // 非 tool 消息原样保留(同一引用)
        assertSame(msgs.get(0), out.get(0));
        assertSame(msgs.get(2), out.get(2));
        // 超大 tool result 被截断,含省略标记且长度不超预算
        ToolResponseMessage trm = (ToolResponseMessage) out.get(1);
        String data = trm.getResponses().get(0).responseData();
        assertTrue(data.length() <= 4096, "截断后总长度 ≤ maxChars");
        assertTrue(data.contains("…(中间省略"), "含省略占位符");
        assertTrue(data.startsWith("HEAD"), "保留开头");
        assertTrue(data.endsWith("TAIL"), "保留结尾");
        // 原消息未被 mutated
        assertEquals(huge, ((ToolResponseMessage) msgs.get(1)).getResponses().get(0).responseData());
    }

    @Test
    void overheadParticipatesInTriggerAndStop() {
        // 构造一个历史轮(含工具调用)+ 小编本轮,便于观察阶段推进。
        List<Message> msgs = new ArrayList<>();
        msgs.add(user("hq" + txt(600)));
        msgs.add(assistantTool("c", "t", txt(300)));
        msgs.add(toolResp("c", "t", txt(300)));
        msgs.add(assistant("ha" + txt(600)));
        msgs.add(user("q" + txt(150)));
        msgs.add(assistant("a" + txt(150)));

        long used = ContextCompressor.estimateTokens(msgs);

        // 1) 触发口径:estimateTokens + overhead <= trigger 时不压缩。
        ContextCompressor.Result no = ContextCompressor.compress(msgs, used + 50, used + 50, 50);
        assertFalse(no.compressed(), "estimate+overhead 未超 trigger 不应压缩");

        // 2) overhead 参与触发:相同消息,overhead 变大后超过 trigger → 触发压缩。
        ContextCompressor.Result big = ContextCompressor.compress(msgs, used + 10, used + 10, 500);
        assertTrue(big.compressed(), "overhead 计入估算后应触发压缩");

        // 3) 停止口径:若漏加 overhead,阶段 A 估算 <= target 会误停在阶段 A;
        //    正确实现应判定 estimate+overhead > target 而继续到阶段 C。
        List<Message> stageAView = List.of(
                user("hq" + txt(600)),
                new SystemMessage(ContextCompressor.PLACEHOLDER),
                assistant("ha" + txt(600)),
                user("q" + txt(150)),
                assistant("a" + txt(150)));
        long stageAEst = ContextCompressor.estimateTokens(stageAView);
        long overhead = 50;
        long target = stageAEst + overhead - 1;
        ContextCompressor.Result r = ContextCompressor.compress(msgs, used - 1, target, overhead);
        assertEquals(3, r.stage(), "停止条件应计入 overhead,否则会误停阶段 A");
        assertTrue(r.droppedHistoryTurns() >= 1);
        assertTrue(ContextCompressor.estimateTokens(r.messages()) + overhead <= target);
    }

    @Test
    void neverBreaksToolPairIntegrity() {
        // 阶段A 压缩后:保留的 assistant(toolCall) 名下必须紧跟其 toolResult,不得拆对
        List<Message> msgs = new ArrayList<>();
        msgs.add(user("历史" + txt(300)));                    // ~100t
        msgs.add(assistantTool("keep", "keep_tool", txt(300))); // ~100t
        msgs.add(toolResp("keep", "keep_tool", txt(300)));    // ~100t
        msgs.add(assistant("历史最终" + txt(300)));            // ~100t
        msgs.add(user("本轮" + txt(150)));
        msgs.add(assistant("本轮最终" + txt(150)));
        // 构造「低于 trigger」:不触发。单独验证 findToolPairs 语义由阶段A隐含:
        // 触发压缩后不得出现 toolResult 而无其 assistant 配对(反之亦然)。
        ContextCompressor.Result r = ContextCompressor.compress(msgs, 100, 50, 0);
        // 若触发了(必然触发),检查任何保留的 toolResponse 前面紧跟 assistant(toolCall)
        if (r.compressed()) {
            List<Message> m = r.messages();
            for (int i = 0; i < m.size(); i++) {
                if (m.get(i) instanceof ToolResponseMessage) {
                    assertTrue(i > 0 && m.get(i - 1) instanceof AssistantMessage am
                                    && am.getToolCalls() != null && !am.getToolCalls().isEmpty(),
                            "toolResponse 不得独立存在,必须与其 assistant(toolCall) 同留同弃");
                }
            }
        }
    }

    @Test
    void systemMessagesPreservedAsPrefix() {
        List<Message> msgs = new ArrayList<>();
        msgs.add(new SystemMessage("子 agent system" + txt(200)));
        msgs.add(user("q" + txt(900)));
        msgs.add(assistant("a" + txt(900)));

        ContextCompressor.Result r = ContextCompressor.compress(msgs, 200, 50, 0);
        assertTrue(r.compressed());
        assertTrue(r.messages().stream().anyMatch(m -> m instanceof SystemMessage),
                "system 消息作为前缀恒保留");
    }
}