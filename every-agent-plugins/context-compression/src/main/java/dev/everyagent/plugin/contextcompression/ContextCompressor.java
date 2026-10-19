package dev.everyagent.plugin.contextcompression;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.ai.chat.messages.UserMessage;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 上下文压缩核心算法(纯逻辑,可独立单测;薄壳 {@link ContextCompressionAdvisor} 调用于
 * 每轮模型请求前)。
 *
 * <p>口径(用户确认):压缩对象分为「历史轮」与「本轮」;当估算用量 > 触发阈值
 * (窗口 × triggerRatio × safetyRatio,默认 95%)时触发,按阶段逐级压缩直到 ≤ 目标阈值
 * (窗口 × targetRatio × safetyRatio,默认 50%):
 * <ol>
 *   <li><b>阶段 A</b>:历史轮只保留「用户输入 + AI 最终回复」,中间工具调用换成占位标记
 *       (压缩只是不进上下文,内存 conversation 与磁盘事件日志始终全量,数据不删除);</li>
 *   <li><b>阶段 B</b>:仍超目标则裁剪本轮<b>前 50%</b> 工具调用对(占位替换,保留后 50%
 *       最新工具结果);</li>
 *   <li><b>阶段 C</b>:仍超目标则从最旧历史轮开始整轮删除;被删轮不再彻底丢弃,
 *       而是以「更早历史」user 截断简版保留(有摘要器时优先用「历史摘要」)。</li>
 * </ol>
 * 兜底:全部阶段做完仍超目标(本轮单 turn 巨大)时保留本轮完整,交给服务商 max 上限兜底。
 *
 * <p>配对完整性铁律:assistant(toolCalls) 与其后的 ToolResponseMessage 必须整对同留同弃,
 * 禁止拆对(截断从不破坏 tool_call/result 配对)。
 *
 * <p>阈值比较统一使用 {@code estimateTokens(x) + overhead},其中 overhead 同时承载「固定预留」
 * 与「offset 校准」两种含义,<b>可为负</b>(负 = 粗估高估正文,压低 precheck;见
 * {@link #calibratedOverhead});四参入口的 overhead 仍只表示固定预留,等价于原 reserve。
 *
 * <p>token 估算:口径可注入({@link TextTokens})——生产环境由全局
 * {@link dev.everyagent.plugin.api.spi.TokenEstimator#estimate} 提供(单一口径,
 * CJK≈1/其余≈4 字符),使压缩与限流/长度护栏共用同一分词口径;未注入时回退内置
 * UTF-8 bytes/3 粗估。阈值比较的统一形式为 {@code estimateTokens(x) + overhead}。
 */
public final class ContextCompressor {

    private ContextCompressor() {
    }

    /** 被压缩工具调用的占位标记(短,提示模型该轮发生过工具调用过程但细节已压缩)。 */
    public static final String PLACEHOLDER = "【该轮工具调用过程已压缩】";

    /** 阶段 C 被删历史轮的 user 文本截断保留长度(字符)。 */
    public static final int MAX_USER_KEEP_CHARS = 200;

    /** 阶段 C 无摘要器或摘要失败时,被删历史轮的保留前缀。 */
    public static final String EARLIER_HISTORY_PREFIX = "【更早历史】";

    /** 阶段 C 摘要器成功时的保留前缀。 */
    public static final String HISTORY_SUMMARY_PREFIX = "【历史摘要】";

    /** 阶段 C 调用摘要器的目标 token 上限(简化常量)。 */
    private static final int MAX_SUMMARY_TOKENS = 512;

    /** 压缩结果。stage:0=未触发 1=A 2=B 3=C;compressed=false 表示无需改写。 */
    public record Result(List<Message> messages, int stage, int droppedHistoryTurns,
            int droppedCurrentToolPairs, boolean compressed) {
    }

    /** 一轮会话:user 消息 + 中间消息(工具调用等) + 最终 assistant 回复(可空)。 */
    private record Turn(Message user, List<Message> middle, Message finalAssistant) {
    }

    // ---- 公共入口 ----

    /**
     * 四参兼容入口:等价于 {@link #compress(List, long, long, long, ContextSummarizer, TextTokens)}
     * 传入 summarizer=null、tokens=null,其中 overhead 语义与原 reserve 一致(固定预留)。
     */
    public static Result compress(List<Message> messages, long trigger, long target, long overhead) {
        return compress(messages, trigger, target, overhead, null, null);
    }

    /**
     * 按阈值检查并压缩。used + overhead ≤ trigger 时原样返回(compressed=false);
     * 否则逐级压缩到 ≤ target,返回改写后的发送视图。overhead 同时承载固定预留与
     * offset 校准(可为负,见 {@link #calibratedOverhead});summarizer 非空时,阶段 C 用其
     * 生成「历史摘要」,失败/缺失时降级为「更早历史」user 截断简版。
     *
     * @param tokens token 估算口径;为空回退内置 bytes/3(生产由全局
     *               {@code TokenEstimator#estimate} 提供,见插件 provider)
     */
    public static Result compress(List<Message> messages, long trigger, long target, long overhead,
            ContextSummarizer summarizer) {
        return compress(messages, trigger, target, overhead, summarizer, null);
    }

    /**
     * 全景入口:{@code tokens} 非空时全部阈值比较改用其口径(逐片段累加),为空时回退内置
     * bytes/3。三阶段算法与 §7.5 描述一致,口径替换只影响「是否触发 / 是否已达 target」的判定,
     * 不改写任何消息内容与配对。
     */
    public static Result compress(List<Message> messages, long trigger, long target, long overhead,
            ContextSummarizer summarizer, TextTokens tokens) {
        if (messages == null || messages.isEmpty()) {
            return new Result(messages, 0, 0, 0, false);
        }
        if (estimateTokens(messages, tokens) + overhead <= trigger) {
            return new Result(messages, 0, 0, 0, false);
        }

        Split split = splitTurns(messages);

        // 阶段 A:历史轮去工具调用(user + 占位 + final;本轮完整)
        List<Message> aView = new ArrayList<>(split.prefix);
        for (Turn t : split.historyTurns) {
            aView.add(t.user);
            if (!t.middle.isEmpty()) {
                // 幂等:无论 middle 是否已含占位符,统一只输出一个占位符,绝不嵌套
                aView.add(new SystemMessage(PLACEHOLDER));
            }
            if (t.finalAssistant != null) {
                aView.add(t.finalAssistant);
            }
        }
        aView.addAll(split.currentTurn);
        if (estimateTokens(aView, tokens) + overhead <= target) {
            return new Result(aView, 1, 0, 0, true);
        }

        // 阶段 B:本轮前 50% 工具调用对 → 占位(保留后 50% 最新工具结果)
        List<Message> curTrimmed = trimFirstHalfToolPairs(split.currentTurn);
        List<Message> bView = new ArrayList<>(split.prefix);
        for (Turn t : split.historyTurns) {
            bView.add(t.user);
            if (!t.middle.isEmpty()) {
                bView.add(new SystemMessage(PLACEHOLDER));
            }
            if (t.finalAssistant != null) {
                bView.add(t.finalAssistant);
            }
        }
        bView.addAll(curTrimmed);
        int droppedPairs = curPairDropCount(split.currentTurn);
        if (estimateTokens(bView, tokens) + overhead <= target) {
            return new Result(bView, 2, 0, droppedPairs, true);
        }

        // 阶段 C:从最旧历史轮开始整轮删除,直到 ≤ target 或历史清空。
        // 被删历史轮不再彻底丢弃:统一保留为「更早历史」user 截断简版(或 LLM 摘要),
        // 放在 prefix 之后、remaining 之前,防永久失忆。
        List<Turn> remaining = new ArrayList<>(split.historyTurns);
        List<Turn> dropped = new ArrayList<>(); // 已删历史轮(旧→新)
        while (!remaining.isEmpty()) {
            List<Message> tmp = new ArrayList<>(split.prefix);
            tmp.addAll(buildUserBriefs(dropped)); // 估量阶段以简版保守计入
            for (Turn t : remaining) {
                tmp.add(t.user);
                if (!t.middle.isEmpty()) {
                    tmp.add(new SystemMessage(PLACEHOLDER));
                }
                if (t.finalAssistant != null) {
                    tmp.add(t.finalAssistant);
                }
            }
            tmp.addAll(curTrimmed);
            if (estimateTokens(tmp, tokens) + overhead <= target) {
                break;
            }
            dropped.add(remaining.remove(0)); // 删除最旧历史轮(移入保留摘要区)
        }
        List<Message> cView = new ArrayList<>(split.prefix);
        cView.addAll(buildDroppedSummaryRegion(dropped, summarizer)); // 被删轮保留摘要区
        for (Turn t : remaining) {
            cView.add(t.user);
            if (!t.middle.isEmpty()) {
                cView.add(new SystemMessage(PLACEHOLDER));
            }
            if (t.finalAssistant != null) {
                cView.add(t.finalAssistant);
            }
        }
        cView.addAll(curTrimmed);
        return new Result(cView, 3, dropped.size(), droppedPairs, true);
    }

    // ---- 超大工具结果截断 ----

    /**
     * 对任意 {@link ToolResponseMessage} 中 {@code responseData} 字符数 > maxChars 的
     * 做确定性截断:保留开头 60% 与结尾 40%(约),中间以 {@code …(中间省略 N 字符)…}
     * 占位,使截断后总字符数 ≈ maxChars。仅修改返回新列表中的消息,原消息不 mutated,
     * 列表大小与角色/配对完全不变(仍为逐条 ToolResponseMessage)。maxChars≤0 视为不截断;
     * null/空入参原样返回。
     */
    public static List<Message> truncateHugeToolResults(List<Message> messages, int maxChars) {
        if (messages == null) {
            return null;
        }
        List<Message> out = new ArrayList<>(messages.size());
        if (maxChars <= 0) {
            out.addAll(messages);
            return out;
        }
        for (Message m : messages) {
            if (m instanceof ToolResponseMessage trm) {
                out.add(truncateToolResponseMessage(trm, maxChars));
            } else {
                out.add(m);
            }
        }
        return out;
    }

    /** 截断单个 ToolResponseMessage:超过 maxChars 的 responseData 逐条首尾保留。 */
    private static Message truncateToolResponseMessage(ToolResponseMessage trm, int maxChars) {
        List<ToolResponse> responses = trm.getResponses();
        if (responses == null || responses.isEmpty()) {
            return trm;
        }
        boolean changed = false;
        List<ToolResponse> newResponses = new ArrayList<>(responses.size());
        for (ToolResponse r : responses) {
            if (r == null) {
                newResponses.add(null);
                continue;
            }
            String data = r.responseData();
            if (data != null && data.length() > maxChars) {
                newResponses.add(new ToolResponse(r.id(), r.name(), truncateMiddle(data, maxChars)));
                changed = true;
            } else {
                newResponses.add(r);
            }
        }
        if (!changed) {
            return trm; // 无超长 result,不改动
        }
        return ToolResponseMessage.builder()
                .responses(newResponses)
                .metadata(trm.getMetadata())
                .build();
    }

    /**
     * 首尾截断:保留开头约 60% 与结尾约 40%,中间以省略占位符标注被省略字符数,
     * 并收缩头尾使结果总长尽量贴近 maxChars 且不超预算。
     */
    private static String truncateMiddle(String data, int maxChars) {
        int len = data.length();
        if (len <= maxChars) {
            return data;
        }
        int head = Math.max(1, (int) (maxChars * 0.6));
        int tail = Math.max(1, maxChars - head);
        String placeholder = omittedPlaceholder(len, head, tail);
        int excess = head + tail + placeholder.length() - maxChars;
        while (excess > 0 && (head > 1 || tail > 1)) {
            int hShrink = Math.min(head - 1, (int) Math.ceil(excess * 0.6));
            int tShrink = Math.min(tail - 1, excess - hShrink);
            head -= hShrink;
            tail -= tShrink;
            placeholder = omittedPlaceholder(len, head, tail);
            excess = head + tail + placeholder.length() - maxChars;
        }
        // 极窄 maxChars 下占位符本身已超预算:退化为纯头部截断,保证不超预算
        if (head + tail + placeholder.length() > maxChars) {
            return data.substring(0, maxChars);
        }
        return data.substring(0, head) + placeholder + data.substring(len - tail);
    }

    /** 构造 {@code …(中间省略 N 字符)…} 占位符,N=被省略字符数。 */
    private static String omittedPlaceholder(int totalChars, int headChars, int tailChars) {
        int omitted = Math.max(0, totalChars - headChars - tailChars);
        return "…(中间省略 " + omitted + " 字符)…";
    }

    // ---- token 估算 ----

    /**
     * 文本 → token 估算函数(估算口径注入点)。生产环境由全局 {@code TokenEstimator#estimate}
     * 提供(见 provider);为空时回退内置 UTF-8 bytes/3 粗估。
     *
     * <p>入参可能为 {@code null}/空串,实现须容忍并返回 0。
     */
    @FunctionalInterface
    public interface TextTokens {
        long of(String text);
    }

    /** 遍历一条消息的全部可计费文本片段(正文 / 工具名 / 工具参数 / 工具结果)。 */
    private static void forEachSegment(Message m, java.util.function.Consumer<String> sink) {
        if (m == null) {
            return;
        }
        sink.accept(m.getText());
        if (m instanceof AssistantMessage am) {
            if (am.getToolCalls() != null) {
                for (ToolCall tc : am.getToolCalls()) {
                    if (tc == null) {
                        continue;
                    }
                    sink.accept(tc.name());
                    sink.accept(tc.arguments());
                }
            }
        } else if (m instanceof ToolResponseMessage trm) {
            if (trm.getResponses() != null) {
                for (ToolResponse r : trm.getResponses()) {
                    if (r == null) {
                        continue;
                    }
                    sink.accept(r.name());
                    sink.accept(r.responseData());
                }
            }
        }
    }

    /**
     * 估算消息列表 token(内置口径:UTF-8 bytes/3,整体向上取整一次)。
     *
     * <p>无注入时的回退口径,与 {@code WorkerConfig.DEFAULT_CONTEXT_WINDOW_TOKENS} 同源注释。
     */
    public static long estimateTokens(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        long[] bytes = {0};
        for (Message m : messages) {
            forEachSegment(m, s -> bytes[0] += bytesOf(s));
        }
        return (bytes[0] + 2) / 3;
    }

    /**
     * 按注入口径估算消息列表 token:逐片段累加 {@code tokens.of(segment)},覆盖正文 / 工具名 /
     * 工具参数 / 工具结果(与 {@link #estimateTokens(List)} 遍历同一组片段)。
     *
     * <p>{@code tokens} 为空时回退内置 bytes/3(便于旧调用方与单测零改动)。
     */
    public static long estimateTokens(List<Message> messages, TextTokens tokens) {
        if (tokens == null) {
            return estimateTokens(messages);
        }
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        long[] sum = {0};
        for (Message m : messages) {
            forEachSegment(m, s -> sum[0] += tokens.of(s));
        }
        return sum[0];
    }

    private static long bytesOf(String s) {
        return s == null || s.isEmpty() ? 0 : s.getBytes(StandardCharsets.UTF_8).length;
    }

    // ---- offset 校准 ----

    /**
     * offset 校准(双向):把「上一轮 provider 实测输入 token − 上一轮发送视图粗估算」作为
     * 阈值比较用的净修正项 overhead,抵消粗估与实际计数的系统性差异。测量缺失(任一入参
     * ≤0)时回退固定 {@code reserve}(首轮 / 换模型 / 无实测)。
     *
     * <p><b>必须双向采纳</b>:
     * <ul>
     *   <li>{@code offset > 0}:粗估漏算隐藏开销(工具定义、系统模板、分词差异)→ 抬升 precheck,
     *       防漏触发;</li>
     *   <li>{@code offset < 0}:粗估<b>高估</b>正文(UTF-8 bytes/3 对 ASCII/代码正文尤其明显,
     *       实测可达 1.5~3 倍)→ 必须同样压低 precheck,否则阈值退化为「粗估 × ratio」而非
     *       「实测 × ratio」,上下文远未真实占满即触发压缩(2026-10 t_ra74 实测:估算 22.9 万、
     *       provider 实测 8.4 万,却在窗口 85.5% 线前反复压缩)。</li>
     * </ul>
     *
     * @param measuredInputTokens 上一轮 provider 实测输入 token(usage.round.inputTokens)
     * @param estimatedLastSent   上一轮实际发送视图的粗估算(与 {@link #estimateTokens} 同口径)
     * @param reserve             无实测时的固定预留(worker.limits.context-tool-reserve-tokens)
     * @return 阈值比较用 overhead(可为负;调用方以 {@code estimateTokens(working) + overhead}
     *         与 trigger/target 比较)
     */
    public static long calibratedOverhead(long measuredInputTokens, long estimatedLastSent, long reserve) {
        if (measuredInputTokens <= 0 || estimatedLastSent <= 0) {
            return reserve;
        }
        return measuredInputTokens - estimatedLastSent;
    }

    // ---- 冷启动校准种子 ----

    /** 冷启动种子比率夹紧下限(实测/估算;防畸小样本把 precheck 压得过低而漏触发。t_ra74 实测 0.37,不被夹掉)。 */
    public static final double SEED_RATIO_FLOOR = 0.35;

    /** 冷启动种子比率夹紧上限(实测含工具定义/系统模板等固定开销,小视图轮次比率可能 >1)。 */
    public static final double SEED_RATIO_CEIL = 2.0;

    /**
     * 冷启动种子比率:上一运行最近一次全量未压缩视图的 {@code 实测 inputTokens / 同视图估算},
     * 夹紧到 [{@link #SEED_RATIO_FLOOR}, {@link #SEED_RATIO_CEIL}];入参非正返回 null(不可用)。
     *
     * <p>跨运行用比率而非加性 offset:历史跨运行持续增长时,加性修正量固定、占比越来越小
     * (修正不足,粗估高估仍会误触发,2026-10 t_ngj2);比率随内容规模自缩放,且把工具定义/
     * 系统模板等固定开销折算在内(旧视图更小、固定开销占比更大 → 比率偏高 → 方向保守不偏漏)。
     * 首个实测回来后调用方自动回到 {@link #calibratedOverhead} 的每轮加性重算。
     */
    public static Double seedRatio(long measuredInputTokens, long estimatedSentTokens) {
        if (measuredInputTokens <= 0 || estimatedSentTokens <= 0) {
            return null;
        }
        double r = (double) measuredInputTokens / (double) estimatedSentTokens;
        return Math.min(SEED_RATIO_CEIL, Math.max(SEED_RATIO_FLOOR, r));
    }

    // ---- 轮次切分 ----

    /** 切分结果:prefix(system 等恒保留) + historyTurns + currentTurn(最新 user 及其后)。 */
    private record Split(List<Message> prefix, List<Turn> historyTurns, List<Message> currentTurn) {
    }

    private static Split splitTurns(List<Message> messages) {
        List<Message> prefix = new ArrayList<>();
        List<Turn> historyTurns = new ArrayList<>();
        List<TurnBuilder> open = new ArrayList<>();
        for (Message m : messages) {
            if (m == null) {
                continue;
            }
            if (m instanceof UserMessage) {
                open.add(new TurnBuilder(m));
            } else if (m instanceof SystemMessage) {
                // system 属固定前缀,恒保留(多 system 保序累积)
                if (open.isEmpty()) {
                    prefix.add(m);
                } else {
                    open.get(open.size() - 1).middle.add(m);
                }
            } else {
                // assistant / toolResponse 归属当前打开的轮次;无打开轮时并入前缀(异常兜底)
                if (open.isEmpty()) {
                    prefix.add(m);
                } else {
                    open.get(open.size() - 1).middle.add(m);
                }
            }
        }
        List<Message> currentTurn;
        if (open.isEmpty()) {
            currentTurn = List.of();
        } else {
            Turn last = open.remove(open.size() - 1).build();
            currentTurn = new ArrayList<>();
            currentTurn.add(last.user);
            currentTurn.addAll(last.middle);
            if (last.finalAssistant != null) {
                currentTurn.add(last.finalAssistant);
            }
        }
        for (TurnBuilder b : open) {
            historyTurns.add(b.build());
        }
        return new Split(prefix, historyTurns, currentTurn);
    }

    /** 轮次构造器:user + 中间消息;build 时把最后一个无 toolCalls 的 assistant 提为 final。 */
    private static final class TurnBuilder {
        private final Message user;
        private final List<Message> middle = new ArrayList<>();

        TurnBuilder(Message user) {
            this.user = user;
        }

        Turn build() {
            Message finalAssistant = null;
            if (!middle.isEmpty()) {
                Message last = middle.get(middle.size() - 1);
                if (last instanceof AssistantMessage am
                        && (am.getToolCalls() == null || am.getToolCalls().isEmpty())) {
                    finalAssistant = last;
                }
            }
            List<Message> mid = new ArrayList<>(middle.size());
            for (Message m : middle) {
                if (m == finalAssistant) {
                    continue;
                }
                mid.add(m);
            }
            return new Turn(user, mid, finalAssistant);
        }
    }

    // ---- 三阶段辅助 ----

    /** 本轮前 50% 工具调用对(ceil(count/2))替换为占位,保留后 50% 最新工具结果。 */
    private static List<Message> trimFirstHalfToolPairs(List<Message> currentTurn) {
        List<Message> out = new ArrayList<>(currentTurn.size());
        List<int[]> pairs = findToolPairs(currentTurn);
        int drop = (pairs.size() + 1) / 2; // ceil
        if (drop == 0) {
            return currentTurn;
        }
        Set<Integer> dropAsst = new HashSet<>();
        Set<Integer> dropResp = new HashSet<>();
        for (int k = 0; k < drop; k++) {
            dropAsst.add(pairs.get(k)[0]);
            dropResp.add(pairs.get(k)[1]);
        }
        for (int i = 0; i < currentTurn.size(); i++) {
            Message m = currentTurn.get(i);
            if (dropResp.contains(i)) {
                continue; // 工具结果随 assistant 占位一并省略
            }
            if (dropAsst.contains(i)) {
                out.add(new SystemMessage(PLACEHOLDER));
            } else {
                out.add(m);
            }
        }
        return out;
    }

    /** 本轮中被占位替换的工具调用对数量(阶段 B 实际裁剪数)。 */
    private static int curPairDropCount(List<Message> currentTurn) {
        return (findToolPairs(currentTurn).size() + 1) / 2;
    }

    /** 扫描工具调用对:[assistant(toolCalls), 紧随其后的 ToolResponseMessage]。 */
    private static List<int[]> findToolPairs(List<Message> msgs) {
        List<int[]> pairs = new ArrayList<>();
        for (int i = 0; i < msgs.size(); i++) {
            Message m = msgs.get(i);
            if (m instanceof AssistantMessage am
                    && am.getToolCalls() != null && !am.getToolCalls().isEmpty()) {
                int j = i + 1;
                while (j < msgs.size() && !(msgs.get(j) instanceof ToolResponseMessage)) {
                    j++;
                }
                if (j < msgs.size()) {
                    pairs.add(new int[] { i, j });
                    i = j; // 跳过已配对的 tool response
                }
            }
        }
        return pairs;
    }

    // ---- 阶段 C 保留/摘要 ----

    /**
     * 构造被删历史轮的「保留摘要区」:有 summarizer 且返回非空时用单条
     * {@code SystemMessage(【历史摘要】+摘要)} 代替;否则(无摘要器 / 摘要失败)降级为
     * 每个被删轮的 {@code UserMessage(【更早历史】+ user 截断简版)} 集合。
     */
    private static List<Message> buildDroppedSummaryRegion(List<Turn> dropped, ContextSummarizer summarizer) {
        if (dropped.isEmpty()) {
            return List.of();
        }
        if (summarizer != null) {
            try {
                String summary = summarizer.summarize(joinDroppedTurnsText(dropped), MAX_SUMMARY_TOKENS);
                if (summary != null && !summary.isBlank()) {
                    return List.of(new SystemMessage(HISTORY_SUMMARY_PREFIX + summary.trim()));
                }
            } catch (RuntimeException ex) {
                // 摘要异常视为失败:降级为用户截断简版,绝不阻塞压缩主流程
            }
        }
        return buildUserBriefs(dropped);
    }

    /** 被删历史轮的 user 截断简版集合(每轮一条 {@code 【更早历史】+前 MAX_USER_KEEP_CHARS 字符})。 */
    private static List<Message> buildUserBriefs(List<Turn> dropped) {
        List<Message> briefs = new ArrayList<>(dropped.size());
        for (Turn t : dropped) {
            String text = msgText(t.user);
            String kept = text.length() > MAX_USER_KEEP_CHARS ? text.substring(0, MAX_USER_KEEP_CHARS) : text;
            briefs.add(new UserMessage(EARLIER_HISTORY_PREFIX + kept));
        }
        return briefs;
    }

    /** 拼接被删历史轮(user + 最终回复)供摘要器使用。 */
    private static String joinDroppedTurnsText(List<Turn> dropped) {
        StringBuilder sb = new StringBuilder();
        for (Turn t : dropped) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append("用户: ").append(msgText(t.user));
            if (t.finalAssistant != null) {
                sb.append("\n回答: ").append(msgText(t.finalAssistant));
            }
        }
        return sb.toString();
    }

    /** 取消息文本(消息或文本为 null 时返回空串)。 */
    private static String msgText(Message m) {
        if (m == null) {
            return "";
        }
        String text = m.getText();
        return text == null ? "" : text;
    }
}
