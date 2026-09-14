package dev.everyagent.worker.task;

import com.openai.errors.OpenAIIoException;
import dev.everyagent.worker.config.WorkerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 模型「输出预算耗尽」护栏 advisor(红线:一个 advisor 只负责一个功能)。
 *
\r
 * <p>背景:reasoning 模型(如 glm-5.3)在长思考任务里会把整个 maxTokens 输出预算\r
 * 全部耗在 thinking 上、始终不产出正文/工具调用;某些 provider 此时<b>不优雅断开</b>——\r
 * 既不回 {@code finish_reason=length}、也不结束 SSE,而是静默挂起连接,客户端只能等\r
 * okhttp 读超时(默认 10 分钟)才 CANCEL 流,随后又被外层瞬时错误重试当成网络抖动\r
 * 反复重跑,每一波都重新吐出数万条瞬态 thinking 事件,最终把内存事件日志刷爆\r
 * (LogOverflowException)。\r
 *\r
 * <p>职责(单一):识别「输出量已达上限但未完成」这一<b>确定性</b>失败——\r
 * <ol>\r
 *   <li>实际收到 {@code finish_reason=length} → 报 {@link ModelLengthExhaustedException};</li>\r
 *   <li>流长时间无输出(超 {@code worker.limits.length-stall-ms})且本 advisor 自行估算的\r
 *       累计输出 token ≈ 配置 maxTokens → 判定等价于 finish_reason=length,报同一错误,\r
 *       避免空等 10 分钟读超时;</li>\r
 *   <li>provider 在预算耗尽处<b>粗暴断流</b>(不发 length 帧、也不静默挂起,长思考 chunk\r
 *       持续到达后以 IOException 瞬时中断)——流被网络级错误中断且自估输出 ≈ maxTokens\r
 *       时同样判定等价 length,报同一错误;否则该错误会被外层瞬时重试当作普通网络抖动\r
 *       退避重跑,每次重试重新整段长思考再次占满预算、再次断流,循环几十分钟。</li>\r
 * </ol>\r
 * 错误为自定义非重试异常(非 OpenAIServiceException/IO/超时),外层瞬时错误重试不会\r
 * 重复退避;错误信息给足调整建议(精简输入/拆分任务/降低 reasoningEffort/调大 maxTokens)。\r
 *
 * <p>位置:order = {@link ToolCallingAdvisor#DEFAULT_ORDER} + 300,位于瞬时错误重试
 * (+200)内侧、上下文压缩(+400)外侧——紧贴模型流,能逐 chunk 看到原始输出;其抛出的
 * 非重试异常穿透瞬时错误重试直达任务层收口。token 计数为<b>粗估</b>(无 tokenizer):
 * CJK 字符 ≈ 1 token、其余 ≈ 4 字符 1 token,阈值判定留 20%~40% 容差,仅供「是否耗尽」
 * 二分,不做精确计量。
 */
public class ModelLengthGuardAdvisor implements CallAdvisor, StreamAdvisor {

    private static final Logger log = LoggerFactory.getLogger(ModelLengthGuardAdvisor.class);

    /** finish_reason=length 语义:输出量达上限(思考/正文耗尽 maxTokens)但未完成。 */
    private static final Set<String> LENGTH = Set.of("length");

    /** 「约等于 maxTokens」下界(80%):自估 token 达到该比例才可能判 length 耗尽。 */
    private static final long NEAR_MAX_LOW_NUM = 4;
    private static final long NEAR_MAX_LOW_DEN = 5;
    /** 「约等于 maxTokens」上界(140%):超此比例判定为估算偏差过大,不按 length 收口。 */
    private static final long NEAR_MAX_HIGH_NUM = 7;
    private static final long NEAR_MAX_HIGH_DEN = 5;

    /** 日志归属 agent(只读 taskId/agentId,不发事件)。 */
    private final AgentEntity a;
    private final WorkerProperties props;

    public ModelLengthGuardAdvisor(AgentEntity a, WorkerProperties props) {
        this.a = a;
        this.props = props;
    }

    @Override
    public String getName() {
        return "Model Length Guard Advisor";
    }

    @Override
    public int getOrder() {
        // 瞬时错误重试(+200)内侧、上下文压缩(+400)外侧:看到模型原始 chunk。
        return ToolCallingAdvisor.DEFAULT_ORDER + 300;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        ChatClientResponse response = chain.nextCall(request);
        ChatResponse cr = response.chatResponse();
        if (cr != null && cr.hasFinishReasons(LENGTH)) {
            long think = estimateTokens(reasoningOf(cr));
            long text = estimateTokens(textOf(cr));
            throw lengthExhausted(think, text, maxTokensOf(request), Cause.FINISH_REASON);
        }
        return response;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return Flux.defer(() -> {
            Integer maxTokens = maxTokensOf(request);
            long stallMs = props.getLimits().getModelLengthStallMs();
            // 每订阅(每次模型调用尝试)独立状态,多任务/多重试并发安全。
            AtomicReference<String> thinkingAcc = new AtomicReference<>("");
            AtomicLong thinkCjk = new AtomicLong();
            AtomicLong thinkOther = new AtomicLong();
            AtomicLong textCjk = new AtomicLong();
            AtomicLong textOther = new AtomicLong();
            Flux<ChatClientResponse> flux = chain.nextStream(request)
                    .doOnNext(chunk -> {
                        accumulate(chunk, thinkingAcc, thinkCjk, thinkOther, textCjk, textOther);
                        ChatResponse cr = chunk.chatResponse();
                        if (cr != null && cr.hasFinishReasons(LENGTH)) {
                            throw lengthExhausted(tokensOf(thinkCjk, thinkOther),
                                    tokensOf(textCjk, textOther), maxTokens, Cause.FINISH_REASON);
                        }
                    });
            if (stallMs > 0) {
                flux = flux.timeout(Duration.ofMillis(stallMs));
            }
            // 流中断统一收口:stall 超时或网络级错误断流且自估输出已≈maxTokens → 判定等价
            // finish_reason=length。第三条路径兜底「provider 在输出预算耗尽处粗暴断流」(不发
            // length 帧、也不静默挂起,长思考 chunk 持续到达后以 IOException 瞬时中断):若不在此
            // 收口,该错误会被外层瞬时重试当作普通网络抖动退避重跑——每次重试重新整段长思考
            // 再次占满预算、再次断流,循环几十分钟。转换出的非重试异常穿透瞬时重试直达任务层。
            return flux.onErrorResume(e -> {
                boolean stall = e instanceof TimeoutException;
                if (!stall && !isNetworkError(e)) {
                    return Flux.error(e);
                }
                long think = tokensOf(thinkCjk, thinkOther);
                long text = tokensOf(textCjk, textOther);
                if (maxTokens != null && nearMax(think + text, maxTokens)) {
                    if (stall) {
                        log.warn("任务 {} agent {} 流 {}ms 无输出且自估输出 {} tokens≈maxTokens {}，"
                                        + "判定 finish_reason=length",
                                a.task.taskId, a.agentId, stallMs, think + text, maxTokens);
                    } else {
                        log.warn("任务 {} agent {} 流被网络级错误中断({}: {})且自估输出 {} tokens≈maxTokens {}，"
                                        + "判定 finish_reason=length",
                                a.task.taskId, a.agentId, e.getClass().getSimpleName(),
                                e.getMessage(), think + text, maxTokens);
                    }
                    return Flux.error(lengthExhausted(think, text, maxTokens,
                            stall ? Cause.STALL : Cause.DISCONNECTED));
                }
                // 输出远未达上限:真·网络/服务端停顿,原样上抛交瞬时重试。
                return Flux.error(e);
            });
        });
    }

    /** 逐 chunk 累加思考/正文 token 估算(思考为累积值差分,正文为增量)。 */
    private static void accumulate(ChatClientResponse chunk, AtomicReference<String> thinkingAcc,
            AtomicLong thinkCjk, AtomicLong thinkOther, AtomicLong textCjk, AtomicLong textOther) {
        ChatResponse cr = chunk.chatResponse();
        if (cr == null || cr.getResult() == null || cr.getResult().getOutput() == null) {
            return;
        }
        AssistantMessage out = cr.getResult().getOutput();
        String text = out.getText();
        if (text != null && !text.isEmpty()) {
            count(text, textCjk, textOther);
        }
        String thinking = reasoningOf(out);
        if (thinking != null && !thinking.isEmpty()) {
            String diff = diff(thinkingAcc, thinking);
            if (!diff.isEmpty()) {
                count(diff, thinkCjk, thinkOther);
            }
        }
    }

    /** 思考累积值 → 新增段(非前缀增长时重置追踪,防串轮;与 WorkerToolEventAdvisor 同纪律)。 */
    private static String diff(AtomicReference<String> acc, String accumulated) {
        String cur = acc.get();
        if (accumulated.equals(cur)) {
            return "";
        }
        if (accumulated.length() > cur.length() && accumulated.startsWith(cur)) {
            acc.set(accumulated);
            return accumulated.substring(cur.length());
        }
        acc.set(accumulated);
        return accumulated;
    }

    /** 逐码点累计 CJK 与非 CJK 字符数(粗估 token: CJK≈1 token、其余≈4 字符 1 token)。 */
    private static void count(String s, AtomicLong cjk, AtomicLong other) {
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (isCjk(cp)) {
                cjk.incrementAndGet();
            } else if (!Character.isWhitespace(cp) && !Character.isISOControl(cp)) {
                other.incrementAndGet();
            }
        }
    }

    private static boolean isCjk(int cp) {
        Character.UnicodeScript sc = Character.UnicodeScript.of(cp);
        return sc == Character.UnicodeScript.HAN || sc == Character.UnicodeScript.HIRAGANA
                || sc == Character.UnicodeScript.KATAKANA || sc == Character.UnicodeScript.HANGUL;
    }

    private static long tokensOf(AtomicLong cjk, AtomicLong other) {
        return cjk.get() + (other.get() + 3) / 4;
    }

    private static long estimateTokens(String s) {
        AtomicLong cjk = new AtomicLong();
        AtomicLong other = new AtomicLong();
        count(s == null ? "" : s, cjk, other);
        return tokensOf(cjk, other);
    }

    /** 自估 token 是否「约等于」maxTokens(20% 下容差 / 40% 上容差)。 */
    static boolean nearMax(long tokens, long maxTokens) {
        if (maxTokens <= 0) {
            return false;
        }
        long low = maxTokens * NEAR_MAX_LOW_NUM / NEAR_MAX_LOW_DEN;
        long high = maxTokens * NEAR_MAX_HIGH_NUM / NEAR_MAX_HIGH_DEN;
        return tokens >= low && tokens <= high;
    }

    private static Integer maxTokensOf(ChatClientRequest request) {
        if (request == null || request.prompt() == null) {
            return null;
        }
        ChatOptions opts = request.prompt().getOptions();
        return opts == null ? null : opts.getMaxTokens();
    }

    private static String reasoningOf(AssistantMessage out) {
        if (out.getMetadata() == null) {
            return "";
        }
        Object rc = out.getMetadata().get("reasoningContent");
        return rc instanceof String s ? s : "";
    }

    private static String reasoningOf(ChatResponse cr) {
        if (cr == null || cr.getResult() == null || cr.getResult().getOutput() == null) {
            return "";
        }
        return reasoningOf(cr.getResult().getOutput());
    }

    private static String textOf(ChatResponse cr) {
        if (cr == null || cr.getResult() == null || cr.getResult().getOutput() == null) {
            return "";
        }
        String t = cr.getResult().getOutput().getText();
        return t == null ? "" : t;
    }

    /** 构造给足信息的 length 耗尽错误(非重试:确定性失败)。 */
    private ModelLengthExhaustedException lengthExhausted(long thinkTokens, long textTokens,
            Integer maxTokens, Cause cause) {
        long total = thinkTokens + textTokens;
        StringBuilder sb = new StringBuilder(256);
        sb.append(switch (cause) {
            case FINISH_REASON -> "模型输出已达上限(finish_reason=length)";
            case STALL -> "模型长时间无输出且输出量已达上限(判定为 finish_reason=length)";
            case DISCONNECTED -> "模型流被中断且输出量已达上限(判定为 finish_reason=length)";
        });
        sb.append(":已输出约 ").append(total).append(" tokens")
                .append("(思考≈").append(thinkTokens).append(" / 正文≈").append(textTokens).append(')');
        if (maxTokens != null) {
            sb.append(",达到本次 maxTokens=").append(maxTokens);
        }
        sb.append(",但未产出有效完成结果。建议:1)精简输入或把任务拆分成多步,避免单轮让模型思考过久;")
                .append("2)降低 reasoningEffort 或减小单次输出预算;3)如需更长输出,调大 maxTokens 后重试。");
        return new ModelLengthExhaustedException(sb.toString());
    }

    /**
     * 网络级错误判定(沿 cause 链下沉,深度封顶防环):SDK IO 信封/IO/超时。
     * provider 在输出预算耗尽处粗暴断开 SSE(不发 finish_reason=length、也不静默挂起)时,
     * 客户端表现为 IO 异常——配合自估输出 ≈ maxTokens 即等价 length 耗尽(见 adviseStream 收口)。
     * 判定口径与 {@link TransientErrorRetryAdvisor} 的网络级瞬时判定一致。
     */
    static boolean isNetworkError(Throwable error) {
        int depth = 0;
        for (Throwable t = error; t != null && depth < 8; depth++, t = t.getCause()) {
            if (t instanceof OpenAIIoException || t instanceof IOException || t instanceof TimeoutException) {
                return true;
            }
        }
        return false;
    }

    /** length 判定成因(供错误文案区分来源)。 */
    private enum Cause {
        /** 实际收到 finish_reason=length 帧。 */
        FINISH_REASON,
        /** 流 stall 超时且自估输出 ≈ maxTokens。 */
        STALL,
        /** 流被网络级错误中断且自估输出 ≈ maxTokens(provider 粗暴断流)。 */
        DISCONNECTED
    }

    /** 输出预算耗尽(等价 finish_reason=length)。非重试,由任务层统一 error 收口。 */
    public static final class ModelLengthExhaustedException extends RuntimeException {
        public ModelLengthExhaustedException(String message) {
            super(message);
        }
    }
}
