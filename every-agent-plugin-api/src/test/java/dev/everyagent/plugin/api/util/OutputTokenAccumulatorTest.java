package dev.everyagent.plugin.api.util;

import dev.everyagent.plugin.api.spi.TokenEstimator;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OutputTokenAccumulator} 单测:验证「正文 + 思考差分 + 工具调用参数」的统一估算口径
 * 能逼近真实 usage;并防回归——只算正文会严重低估(旧缺陷)。
 */
class OutputTokenAccumulatorTest {

    /** factor 恒 1.0 的估算器桩:CJK≈1 token、其余≈4 字符 1 token(与既有口径一致)。 */
    private static final TokenEstimator STUB = new TokenEstimator() {
        @Override public long estimate(String text, String configId) { return rawTokens(text); }
        @Override public void calibrate(String configId, long estimatedTokens, long actualTokens) { }
        @Override public double factorOf(String configId) { return 1.0; }
        @Override public long sampleCountOf(String configId) { return 0; }
    };

    private static long rawTokens(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        long cjk = 0, other = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            Character.UnicodeScript sc = Character.UnicodeScript.of(cp);
            boolean isCjk = sc == Character.UnicodeScript.HAN
                    || sc == Character.UnicodeScript.HIRAGANA
                    || sc == Character.UnicodeScript.KATAKANA
                    || sc == Character.UnicodeScript.HANGUL;
            if (isCjk) {
                cjk++;
            } else if (!Character.isWhitespace(cp) && !Character.isISOControl(cp)) {
                other++;
            }
        }
        return cjk + (other + 3) / 4;
    }

    private static String repeat(char c, int n) {
        return String.valueOf(c).repeat(n);
    }

    private static AssistantMessage chunk(String body, String thinkingAccumulated,
            List<AssistantMessage.ToolCall> calls) {
        Map<String, Object> md = new HashMap<>();
        if (thinkingAccumulated != null) {
            md.put("reasoningContent", thinkingAccumulated);
        }
        return AssistantMessage.builder()
                .content(body == null ? "" : body)
                .properties(md)
                .toolCalls(calls == null ? List.of() : calls)
                .build();
    }

    private static AssistantMessage.ToolCall call(String id, String args) {
        return new AssistantMessage.ToolCall(id, "function", null, args);
    }

    /**
     * 含工具调用的合成流:正文 400 ASCII → 100 token、思考 200 字符 → 50 token、
     * 两个 callId 的工具参数合计 1600 ASCII → 400 token,真实 usage = 550。
     * 断言估算/真实落入 [0.9, 1.2];并断言「只算正文」得到 ≈100(比值 ≈0.18)——防回归关键。
     *
     * <p>分段长度刻意取 4 的倍数(每段恰为整数 token),使手工核算的 550 与累加器逐段求和一致。
     */
    @Test
    void estimatesIncludeToolCallArgumentsAndApproachRealUsage() {
        final long actual = 550;
        // 每 chunk 的字符(均非空白,CJK 为 0)
        final int bodyChars = 80;      // → 20 token / chunk × 5 = 100
        final int thinkDelta = 40;     // → 10 token / chunk × 5 = 50
        final int argChars = 160;      // → 40 token / chunk × 5 = 200 / call
        final char[] args1 = {'A', 'B', 'C', 'D', 'E'};
        final char[] args2 = {'F', 'G', 'H', 'I', 'J'};

        OutputTokenAccumulator acc = new OutputTokenAccumulator(STUB, "cfg");
        OutputTokenAccumulator bodyOnly = new OutputTokenAccumulator(STUB, "cfg");

        for (int i = 1; i <= 5; i++) {
            String body = repeat('a', bodyChars);
            // 思考:累积值逐个 chunk 变长(40,80,...,200)→ 前缀差分应得到每段 40 字符
            String thinking = repeat('b', thinkDelta * i);
            // 工具参数:每个 chunk 给一段全新的、互不为前缀的拼接片(增量拼接实现)
            List<AssistantMessage.ToolCall> calls = new ArrayList<>();
            calls.add(call("c1", repeat(args1[i - 1], argChars)));
            calls.add(call("c2", repeat(args2[i - 1], argChars)));

            acc.accept(chunk(body, thinking, calls));
            // 只喂正文(无思考、无工具调用),复现旧口径
            bodyOnly.accept(chunk(body, null, null));
        }

        assertEquals(actual, acc.tokens(), "统一口径估算应精确等于手工核算的真实 usage");
        double ratio = (double) acc.tokens() / (double) actual;
        assertTrue(ratio >= 0.9 && ratio <= 1.2,
                "含工具调用参数的估算/真实比值应落入 [0.9, 1.2],实际 " + ratio);

        // 防回归:旧口径(只算正文)会口径漏计工具调用参数
        assertEquals(100, bodyOnly.tokens(), "只算正文应得 ≈100");
        double bodyOnlyRatio = (double) bodyOnly.tokens() / (double) actual;
        assertTrue(bodyOnlyRatio < 0.9,
                "只算正文的比值应显著偏低(≈0.18),实际 " + bodyOnlyRatio);
        assertEquals(100.0 / actual, bodyOnlyRatio, 1e-9, "比值约 0.18");
    }

    /**
     * 累积值式 tool arguments(每 chunk 给全量前缀)不得被重复计数——
     * 若错误地逐 chunk 直接相加,会得到 10+20+30+40=100,而非正确的 40。
     */
    @Test
    void cumulativeToolArgumentsAreNotDoubleCounted() {
        OutputTokenAccumulator acc = new OutputTokenAccumulator(STUB, "cfg");
        // 累积值:40,80,120,160 字符 → 正确增量合计 160 字符 → 40 token
        int[] cumulative = {40, 80, 120, 160};
        for (int n : cumulative) {
            acc.accept(chunk("", null, List.of(call("c1", repeat('x', n)))));
        }
        assertEquals(40, acc.tokens(), "累积值实现下按前缀差分,不得重复计数");
        assertEquals(rawTokens(repeat('x', 160)), acc.tokens());
    }

    /** 工具调用 name(也可能被实现成累积值)同样按 id 分桶差分,避免重复计。 */
    @Test
    void toolCallNameCountedOnceUnderCumulativeStyle() {
        OutputTokenAccumulator acc = new OutputTokenAccumulator(STUB, "cfg");
        AssistantMessage.ToolCall tc = new AssistantMessage.ToolCall("c1", "function", "read_file", "");
        acc.accept(chunk("", null, List.of(tc)));
        acc.accept(chunk("", null, List.of(tc))); // 同一 name 重复给,不得再计
        assertEquals(rawTokens("read_file"), acc.tokens(), "name 只在首次出现时计一次");
    }

    /** reset 归零后复用:正文/思考/工具参数追踪全部清空。 */
    @Test
    void resetClearsAllTracking() {
        OutputTokenAccumulator acc = new OutputTokenAccumulator(STUB, "cfg");
        acc.accept(chunk("abcd", "thinking", List.of(call("c1", "abcd"))));
        assertTrue(acc.tokens() > 0);
        acc.reset();
        assertEquals(0, acc.tokens());
        // reset 后同一累积思考值应被当作首次出现(重新计)
        acc.accept(chunk("", "thinking", null));
        assertEquals(rawTokens("thinking"), acc.tokens());
    }

    /** null 安全。 */
    @Test
    void nullSafe() {
        OutputTokenAccumulator acc = new OutputTokenAccumulator(STUB, "cfg");
        acc.accept(null);
        assertEquals(0, acc.tokens());
    }
}