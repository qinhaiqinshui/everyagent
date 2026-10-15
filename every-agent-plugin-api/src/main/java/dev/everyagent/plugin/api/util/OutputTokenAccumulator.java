package dev.everyagent.plugin.api.util;

import dev.everyagent.plugin.api.spi.TokenEstimator;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 流式输出估算累加器：正文增量 + 思考累积值差分 + 工具调用参数按 callId 累积，
 * 统一限流 / 校准 / 护栏的<b>输出估算口径</b>。
 *
 * <p>职责（单一）：把模型流式返回的每个 {@link AssistantMessage} chunk 折算成估算输出 token，
 * 累计成「本轮输出的估算总量」。校准侧（把估算与真实 {@code completionTokens} 对账）与限流侧
 * （活跃流 tpm 压力估算）必须走<b>同一套</b>口径，否则两处各自实现必然再次分叉——本类即那唯一
 * 实现，两侧都调用它。
 *
 * <p>为什么必须纳入工具调用参数：真实 usage 的 {@code completionTokens} <b>含</b>工具调用
 * 参数（name + arguments），旧口径只累 {@code getText()} + reasoningContent ⇒ 分子含、
 * 分母不含，{@code actual/estimated} 系统性偏高，把校准系数拉到上界 3.0，纯文本轮次也跟着高估 ≈2.2 倍。
 *
 * <p>三段口径：
 * <ol>
 *   <li><b>正文</b>：{@link AssistantMessage#getText()} 非空 → 直接按<b>增量</b>计（OpenAI 流式
 *       chunk 的 {@code getText()} 本就是增量，与既有 {@code TokenCalibrationAdvisor.accumulate} 同口径）；</li>
 *   <li><b>思考</b>：{@code metadata["reasoningContent"]} 在 Spring AI 2.0.1 里是<b>到当前 chunk 为止
 *       的累积值</b>，必须做前缀差分取增量（前缀增长取增量、非前缀重置追踪防串轮；与
 *       {@code WorkerToolEventAdvisor.diffThinking} 同纪律）；</li>
 *   <li><b>工具调用参数</b>：{@code arguments()} 在 chunk 中通常是<b>增量拼接</b>，但也可能被实现成
 *       累积值——故按 callId 分桶累积，兼容两种实现（见 {@link #accumulateToolArg}）；
 *       {@code name()} 同样处理（独立键），避免累积值实现下重复计。</li>
 * </ol>
 *
 * <p><b>非线程安全</b>：仅供单个订阅 / 单轮模型调用内使用（每次订阅 new 一个，检测到 usage 帧后
 * {@link #reset()} 复用）。
 */
public final class OutputTokenAccumulator {

    private final TokenEstimator estimator;
    private final String configId;

    /** 累计估算 token（正文增量 + 思考差分 + 工具调用参数）。 */
    private long tokens = 0;
    /** 思考累积值追踪（前缀差分用；非前缀变化时重置）。 */
    private String thinkingAcc = "";
    /** 工具调用参数 / name 分桶（key = callId 或 callId+后缀）。 */
    private final Map<String, String> toolArgAcc = new HashMap<>();

    public OutputTokenAccumulator(TokenEstimator estimator, String configId) {
        this.estimator = estimator;
        this.configId = configId;
    }

    /**
     * 纳入一个流式 chunk 的输出，累计估算 token。null 安全（null 直接忽略）。
     *
     * @param out chunk 的 {@link AssistantMessage}（正文 / reasoningContent / toolCalls 皆可为空）
     */
    public void accept(AssistantMessage out) {
        if (out == null) {
            return;
        }
        // 1. 正文增量
        String text = out.getText();
        if (text != null && !text.isEmpty()) {
            tokens += estimator.estimate(text, configId);
        }
        // 2. 思考累积值差分
        String thinking = reasoningOf(out);
        if (thinking != null && !thinking.isEmpty()) {
            String delta = diffThinking(thinking);
            if (!delta.isEmpty()) {
                tokens += estimator.estimate(delta, configId);
            }
        }
        // 3. 工具调用参数（按 callId 分桶，兼容增量拼接与累积值两种实现）
        List<AssistantMessage.ToolCall> calls = out.getToolCalls();
        if (calls != null) {
            for (int i = 0; i < calls.size(); i++) {
                AssistantMessage.ToolCall tc = calls.get(i);
                if (tc == null) {
                    continue;
                }
                String key = (tc.id() == null || tc.id().isBlank()) ? "#" + i : tc.id();
                String argDelta = accumulateToolArg(toolArgAcc, key, tc.arguments());
                if (!argDelta.isEmpty()) {
                    tokens += estimator.estimate(argDelta, configId);
                }
                String name = tc.name();
                if (name != null && !name.isEmpty()) {
                    String nameDelta = accumulateToolArg(toolArgAcc, key + "\u0000name", name);
                    if (!nameDelta.isEmpty()) {
                        tokens += estimator.estimate(nameDelta, configId);
                    }
                }
            }
        }
    }

    /** 当前累计估算输出 token。 */
    public long tokens() {
        return tokens;
    }

    /** 归零（清估算、思考追踪、工具参数桶），供同一订阅内检测到 usage 帧后复用。 */
    public void reset() {
        tokens = 0;
        thinkingAcc = "";
        toolArgAcc.clear();
    }

    /**
     * 思考累积值 → 新增段（前缀增长取增量；非前缀增长视为重置，返回整段并重置追踪，防串轮）。
     */
    private String diffThinking(String accumulated) {
        String cur = thinkingAcc;
        if (accumulated.equals(cur)) {
            return "";
        }
        if (accumulated.length() > cur.length() && accumulated.startsWith(cur)) {
            thinkingAcc = accumulated;
            return accumulated.substring(cur.length());
        }
        thinkingAcc = accumulated;
        return accumulated;
    }

    /**
     * 工具调用参数 / name 的累积差分：兼容「增量拼接」与「累积值」两种实现。
     *
     * <p>{@code prev} = 已累计值，{@code cur} = 本 chunk 值：
     * <ul>
     *   <li>{@code cur} 为空 → 无增量；</li>
     *   <li>{@code cur} 以 {@code prev} 开头 → 增量拼接：取 {@code cur} 的剩余后缀，累计值更新为 {@code cur}；</li>
     *   <li>否则 → 累积值实现（或异常重置）：整段 {@code cur} 即增量，累计值更新为 {@code prev+cur}
     *       （下次若给到更长前缀仍能正确差出增量）。</li>
     * </ul>
     *
     * @return 本次新增段（空串表示无新增，不计）
     */
    private static String accumulateToolArg(Map<String, String> acc, String key, String arguments) {
        String prev = acc.getOrDefault(key, "");
        String cur = (arguments == null) ? "" : arguments;
        String delta;
        if (cur.isEmpty()) {
            delta = "";
        } else if (cur.startsWith(prev)) {
            delta = cur.substring(prev.length());
            acc.put(key, cur);
        } else {
            delta = cur;
            acc.put(key, prev + cur);
        }
        return delta;
    }

    private static String reasoningOf(AssistantMessage out) {
        if (out.getMetadata() == null) {
            return "";
        }
        Object rc = out.getMetadata().get("reasoningContent");
        return rc instanceof String s ? s : "";
    }
}