package dev.everyagent.plugin.modellengthguard;

import com.openai.errors.OpenAIIoException;
import dev.everyagent.plugin.api.spi.TokenEstimator;
import dev.everyagent.plugin.api.config.WorkerConfig;
import dev.everyagent.plugin.api.agent.AgentContext;
import dev.everyagent.plugin.api.execution.ExecContext;
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
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 模型「输出预算耗尽」护栏 advisor（红线:一个 advisor 只负责一个功能）。
 *
 * <p>背景:reasoning 模型(如 glm-5.3)在长思考任务里会把整个 maxTokens 输出预算
 * 全部耗在 thinking 上、始终不产出正文/工具调用;某些 provider 此时<b>不优雅断开</b>——
 * 既不回 {@code finish_reason=length}、也不结束 SSE,而是静默挂起连接,客户端只能等
 * okhttp 读超时(默认 10 分钟)才 CANCEL 流,随后又被外层瞬时错误重试当成网络抖动
 * 反复重跑,每一波都重新吐出数万条瞬态 thinking 事件,最终把内存事件日志刷爆
 * (LogOverflowException)。
 *
 * <p>职责(单一):识别「输出量已达上限但未完成」这一<b>确定性</b>失败——
 * <ol>
 *   <li>实际收到 {@code finish_reason=length} → 透传该帧到外层,流结束时抛
 *       {@link ModelLengthExhaustedException};</li>
 *   <li>流长时间无输出(超 {@code worker.limits.length-stall-ms})且自估累计输出已达上限
 *       → 判定等价于 finish_reason=length,先下发合成 {@code finish_reason=length} 帧再抛
 *       同一错误,避免空等 10 分钟读超时;</li>
 *   <li>provider 在预算耗尽处<b>粗暴断流</b>(不发 length 帧、也不静默挂起,长思考 chunk
 *       持续到达后以 IOException 瞬时中断)——流被网络级错误中断且自估输出已达上限时同样
 *       判定等价 length,先下发合成 {@code finish_reason=length} 帧再抛同一错误;否则该错误会被
 *       外层瞬时重试当作普通网络抖动退避重跑,每次重试重新整段长思考再次占满预算、再次断流,
 *       循环几十分钟。</li>
 * </ol>
 * ②③的「输出已达上限」判定:配置了 maxTokens 时用比例(80%~140% 容差);<b>未配置
 * maxTokens</b>(provider 按服务端默认预算截断,客户端不可见)时用绝对阈值
 * {@code worker.limits.length-disconnect-min-tokens} 兜底——「断流/停滞 + 已输出数万
 * token」是预算耗尽的强信号,快速收口优于高代价重放。
 * 错误为自定义非重试异常(非 OpenAIServiceException/IO/超时),外层瞬时错误重试不会
 * 重复退避;错误信息给足调整建议(精简输入/拆分任务/降低 reasoningEffort/调大 maxTokens)。
 *
 * <p><b>补帧信号设计（跨插件协议契约，语义不可移除）</b>:在 stall/断流路径上,
 * 本 advisor 在抛出 {@link ModelLengthExhaustedException} 之前,会先向下游下发一个合成的
 * {@code finish_reason=length} 帧。合成帧是给 Adaptive（adaptive-max-tokens 插件）的
 * <b>数据面触发器</b>——Adaptive 只认 {@code finish_reason=length} 帧作为"模型输出预算
 * 不足"的信号来触发自适应调整策略;异常是给任务层的<b>控制面兜底</b>——任务层捕获后
 * 统一 error 收口。Reactor 保证 onNext 先于 onError 到达外层,因此 Adaptive 先收到合成帧
 * 再收到异常。对于真实 {@code finish_reason=length} 帧(路径①),帧天然已透传到外层,
 * 不需要额外合成——流 complete 时再抛异常即可。
 *
 * <p>位置:order = {@link ToolCallingAdvisor#DEFAULT_ORDER} + 300,位于瞬时错误重试
 * (+200)内侧、上下文压缩(+400)外侧——紧贴模型流,能逐 chunk 看到原始输出;其抛出的
 * 非重试异常穿透瞬时错误重试直达任务层收口。token 估算委托 {@link TokenEstimator}
 * (经真实 usage 在线校准的 CJK 粗估),阈值判定留 20%~40% 容差,仅供「是否耗尽」
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
    private final AgentContext a;
    private final WorkerConfig props;
    private final TokenEstimator estimator;
    private final String configId;

    public ModelLengthGuardAdvisor(AgentContext a, WorkerConfig props, TokenEstimator estimator) {
        this.a = a;
        this.props = props;
        this.estimator = estimator;
        ExecContext exec = a.execution();
        this.configId = exec.snapshot().configId();
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
            long think = estimator.estimate(reasoningOf(cr), configId);
            long text = estimator.estimate(textOf(cr), configId);
            throw lengthExhausted(think, text, maxTokensOf(request), Cause.FINISH_REASON);
        }
        return response;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        ExecContext exec = a.execution();
        return Flux.defer(() -> {
            Integer maxTokens = maxTokensOf(request);
            long stallMs = props.limits().modelLengthStallMs();
            // 每订阅(每次模型调用尝试)独立状态,多任务/多重试并发安全。
            AtomicReference<String> thinkingAcc = new AtomicReference<>("");
            AtomicLong thinkTokens = new AtomicLong();
            AtomicLong textTokens = new AtomicLong();
            // 路径①flag:doOnNext 检测到真实 finish_reason=length 帧时置位,
            // 流 complete 时若 flag 置位再抛异常——不在 doOnNext 就地抛(会把该帧转成 error,
            // 帧到不了外层)。帧先透传,异常后发,Reactor 保证 onNext 先于 onComplete/onError。
            AtomicBoolean lengthFlag = new AtomicBoolean(false);
            Flux<ChatClientResponse> flux = chain.nextStream(request)
                    .doOnNext(chunk -> {
                        accumulate(chunk, thinkingAcc, thinkTokens, textTokens);
                        ChatResponse cr = chunk.chatResponse();
                        if (cr != null && cr.hasFinishReasons(LENGTH)) {
                            lengthFlag.set(true);
                        }
                    })
                    .doOnComplete(() -> {
                        if (lengthFlag.get()) {
                            throw lengthExhausted(thinkTokens.get(),
                                    textTokens.get(), maxTokens, Cause.FINISH_REASON);
                        }
                    });
            if (stallMs > 0) {
                flux = flux.timeout(Duration.ofMillis(stallMs));
            }
            // 流中断统一收口:stall 超时或网络级错误断流且自估输出已达上限 → 判定等价
            // finish_reason=length。第三条路径兜底「provider 在输出预算耗尽处粗暴断流」(不发
            // length 帧、也不静默挂起,长思考 chunk 持续到达后以 IOException 瞬时中断):若不在此
            // 收口,该错误会被外层瞬时重试当作普通网络抖动退避重跑——每次重试重新整段长思考
            // 再次占满预算、再次断流,循环几十分钟。转换出的非重试异常穿透瞬时重试直达任务层。
            //
            // 补帧:在抛异常之前先 concatWith 合成 finish_reason=length 帧(给 Adaptive 数据面
            // 触发器),再 Flux.error(异常)(给任务层控制面兜底)。Reactor 保证 onNext 先于 onError。
            return flux.onErrorResume(e -> {
                boolean stall = e instanceof TimeoutException;
                if (!stall && !isNetworkError(e)) {
                    return Flux.error(e);
                }
                long think = thinkTokens.get();
                long text = textTokens.get();
                if (reachedOutputLimit(think + text, maxTokens)) {
                    String basis = lengthBasis(think + text, maxTokens);
                    if (stall) {
                        log.warn("任务 {} agent {} 流 {}ms 无输出且自估输出 {} tokens已达上限({}),"
                                        + "判定 finish_reason=length",
                                exec.subjectId(), a.agentId(), stallMs, think + text, basis);
                    } else {
                        log.warn("任务 {} agent {} 流被网络级错误中断({}: {})且自估输出 {} tokens已达上限({}),"
                                        + "判定 finish_reason=length",
                                exec.subjectId(), a.agentId(), e.getClass().getSimpleName(),
                                e.getMessage(), think + text, basis);
                    }
                    // 先下发合成 finish_reason=length 帧(Adaptive 数据面触发器),
                    // 再抛异常(任务层控制面兜底)。Reactor 保证 onNext 先于 onError 到达外层。
                    return Flux.just(syntheticLengthFrame())
                            .concatWith(Flux.error(lengthExhausted(think, text, maxTokens,
                                    stall ? Cause.STALL : Cause.DISCONNECTED)));
                }
                // 输出远未达上限:真·网络/服务端停顿,原样上抛交瞬时重试。
                return Flux.error(e);
            });
        });
    }

    /**
     * 构造合成 {@code finish_reason=length} 帧（跨插件协议契约，语义不可移除）。
     *
     * <p>合成帧是给 Adaptive（adaptive-max-tokens 插件）的<b>数据面触发器</b>——
     * Adaptive 只认 {@code finish_reason=length} 帧作为"模型输出预算不足"的信号来
     * 触发自适应调整策略。在 stall/断流路径上,provider 没有发送 length 帧,因此需要
     * advisor 合成一个,确保 Adaptive 能收到触发信号。帧内容为空,仅携带
     * {@code finishReason="length"} 元数据。
     */
    private static ChatClientResponse syntheticLengthFrame() {
        AssistantMessage msg = AssistantMessage.builder().content("").build();
        Generation gen = new Generation(msg,
                ChatGenerationMetadata.builder().finishReason("length").build());
        ChatResponse cr = new ChatResponse(List.of(gen));
        return new ChatClientResponse(cr, Map.of());
    }

    /** 逐 chunk 累加思考/正文 token 估算(思考为累积值差分,正文为增量)。 */
    private void accumulate(ChatClientResponse chunk, AtomicReference<String> thinkingAcc,
            AtomicLong thinkTokens, AtomicLong textTokens) {
        ChatResponse cr = chunk.chatResponse();
        if (cr == null || cr.getResult() == null || cr.getResult().getOutput() == null) {
            return;
        }
        AssistantMessage out = cr.getResult().getOutput();
        String text = out.getText();
        if (text != null && !text.isEmpty()) {
            textTokens.addAndGet(estimator.estimate(text, configId));
        }
        String thinking = reasoningOf(out);
        if (thinking != null && !thinking.isEmpty()) {
            String diff = diff(thinkingAcc, thinking);
            if (!diff.isEmpty()) {
                thinkTokens.addAndGet(estimator.estimate(diff, configId));
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

    /** 自估 token 是否「约等于」maxTokens(20% 下容差 / 40% 上容差)。 */
    static boolean nearMax(long tokens, long maxTokens) {
        if (maxTokens <= 0) {
            return false;
        }
        long low = maxTokens * NEAR_MAX_LOW_NUM / NEAR_MAX_LOW_DEN;
        long high = maxTokens * NEAR_MAX_HIGH_NUM / NEAR_MAX_HIGH_DEN;
        return tokens >= low && tokens <= high;
    }

    /**
     * 「输出已达上限」统一判定(stall/断流收口共用):
     * <ul>
     *   <li>模型配置了 maxTokens → {@link #nearMax}(比例判定,80%~140% 容差);</li>
     *   <li>未配置 maxTokens(provider 按服务端默认预算截断,客户端不可见)→ 绝对阈值
     *       {@code worker.limits.length-disconnect-min-tokens} 兜底:「断流/停滞 + 已输出
     *       数万 token」是预算耗尽的强信号,且此类波次重试代价极高(每次重放整段长思考),
     *       快速收口优于反复退避。</li>
     * </ul>
     */
    private boolean reachedOutputLimit(long tokens, Integer maxTokens) {
        if (maxTokens != null && maxTokens > 0) {
            return nearMax(tokens, maxTokens);
        }
        long min = props.limits().lengthDisconnectMinTokens();
        return min > 0 && tokens >= min;
    }

    /** 判定依据描述(供日志/错误信息区分「≈maxTokens」与「绝对阈值兜底」两种口径)。 */
    private String lengthBasis(long tokens, Integer maxTokens) {
        if (maxTokens != null && maxTokens > 0) {
            return "≈maxTokens " + maxTokens;
        }
        return "未配置 maxTokens,≥兜底阈值 " + props.limits().lengthDisconnectMinTokens();
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
     * 判定口径与 {@code TransientErrorRetryAdvisor} 的网络级瞬时判定一致。
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
}
