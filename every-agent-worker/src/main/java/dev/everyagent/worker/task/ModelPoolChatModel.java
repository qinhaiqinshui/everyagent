package dev.everyagent.worker.task;

import com.openai.errors.OpenAIIoException;
import dev.everyagent.worker.proto.TaskDtos.ModelSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 模型池 ChatModel(架构 §5.2 + 演进记录第 18 轮):`worker.models` 里
 * {@code provider: model-pool} 配置产出的组合模型。
 *
 * <p>职责:把「请求异常时换池内下一模型重试」的容灾做成 <b>ChatModel 层能力</b>——
 * 普通模型 / 主 agent / 子 agent / AI 审议只要 configId 指向池配置,构建出的 chatModel
 * 即本类,天然具备容灾;不再有 slash 开关、不再有任务级 {@code modelPoolFailover}、不再有
 * advisor 换模型。成员按配置顺序逐个尝试,首个 = 主模型。
 *
 * <p>错误分类(与旧 {@code ModelPoolFailoverAdvisor} 一致):
 * <ul>
 *   <li><b>网络异常</b>({@link OpenAIIoException}/IO/超时,如没有网络)→ 不介入,
 *       原样上抛——换模型救不了断网;</li>
 *   <li><b>终态异常</b>({@link ModelCallException} 空响应耗尽、取消类)→ 原样上抛不切换;</li>
 *   <li>其余(瞬时错误退避耗尽后的 429/5xx、401/403/400、解析错误等)→ 换池内下一成员轮询。</li>
 * </ul>
 * 池耗尽<b>不做任何包络</b>:最后一个原始异常原样上抛,让外层瞬时错误重试 advisor 能正常
 * 退避重跑(用户决策:容灾耗尽不代表上游重试也应放弃,429/5xx 仍按既有退避策略重试)。
 *
 * <p>流式防重复护栏:已下发任何正文/推理/工具调用 chunk 后流中断的不切换(重放会造成
 * 前端 delta 重复),原错误上抛——与 {@link TransientErrorRetryAdvisor} 的护栏同语义。
 *
 * <p>options 处理(Spring AI 2.0.1:OpenAiChatModel 把 baseUrl/apiKey 固定在建模型时,
 * prompt 携带 options 不再与模型默认 options 合并):每个成员在 {@code ChatModelFactory}
 * 构建时用<b>自己的完整快照</b>;转发 prompt 前把 prompt options 改写为该成员快照
 * ({@link #withOptions}),并补原 prompt options 的工具集(toolCallbacks)。
 * 成功返回的是成员模型的真实响应——usage/模型名随响应自然落到事件与 usage trace。
 *
 * <p>trace:每次切换经 {@code TaskEvents.modelFailoverSwitch} 发
 * {@code task.trace(kind=model_failover)},同一波连续失败切换共用一条 trace,
 * 波收口 {@code modelFailoverClose};events 可为 null(无事件上下文时仅记日志不发 trace)。
 * 实例内无跨请求共享状态:轮询游标/防重标记 per-subscription(Flux.defer 闭包捕获)。
 */
public class ModelPoolChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(ModelPoolChatModel.class);

    private final List<ChatModel> members;
    private final List<OpenAiChatOptions> memberOptions;
    private final List<ModelSnapshot> memberSnapshots;
    /** 任务事件发射器(发容灾切换 trace);可为 null = 不发 trace 仅记日志。 */
    private final TaskEvents events;
    /** 日志归属 agent(仅日志前缀,不发事件)。 */
    private final String agentId;
    /** getOptions() 返回项(OpenAiChatOptions 保证 ToolCallingAdvisor 正常透传工具集)。 */
    private final OpenAiChatOptions defaultOptions;

    public ModelPoolChatModel(List<ChatModel> members, List<OpenAiChatOptions> memberOptions,
            List<ModelSnapshot> memberSnapshots, TaskEvents events, String agentId) {
        this.members = List.copyOf(members);
        this.memberOptions = List.copyOf(memberOptions);
        this.memberSnapshots = List.copyOf(memberSnapshots);
        this.events = events;
        this.agentId = agentId == null ? "" : agentId;
        this.defaultOptions = memberOptions.isEmpty()
                ? OpenAiChatOptions.builder().build()
                : memberOptions.get(0);
    }

    @Override
    public ChatOptions getOptions() {
        return defaultOptions;
    }

    // ---- 非流式 ----

    @Override
    public ChatResponse call(Prompt prompt) {
        String failoverTraceId = null;
        for (int i = 0; ; i++) {
            if (i >= members.size()) {
                // 不可达:循环早退见下
                throw new IllegalStateException("模型池成员列表为空");
            }
            try {
                ChatResponse response = members.get(i).call(withOptions(prompt, memberOptions.get(i)));
                closeFailover(failoverTraceId); // 成功:这一波容灾切换结束(链尾即最终成功模型)
                return response;
            } catch (RuntimeException e) {
                if (isNetwork(e) || isTerminal(e)) {
                    closeFailover(failoverTraceId);
                    throw e;
                }
                if (i >= members.size() - 1) {
                    log.warn("池模型 {} 轮询 {} 个成员全部失败, 原样上抛最后一个异常: {}",
                            agentId, members.size(), e.getMessage());
                    // 不做任何包络:原始异常往上抛,让外层瞬时错误重试 advisor 能正常退避重跑。
                    closeFailover(failoverTraceId);
                    throw e;
                }
                // 同一波连续失败切换共用一条 trace:完整切换链进 content,summary 只留最后切换模型。
                failoverTraceId = logSwitch(e, i + 1, failoverTraceId);
            }
        }
    }

    // ---- 流式 ----

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.defer(() -> {
            AtomicBoolean emitted = new AtomicBoolean(false);
            AtomicInteger cursor = new AtomicInteger(0);
            // 同一波连续失败切换共用一条容灾 trace(traceId 由首次切换创建,后续切换追加模型)。
            AtomicReference<String> failoverTraceId = new AtomicReference<>();
            return attemptStream(prompt, emitted, cursor, failoverTraceId);
        });
    }

    /**
     * 单次尝试:首成员直接发,失败后按成员顺序换下一个;透传 chunk 并标记「已下发有效信号」;
     * 错误时若可换成员则递归换下一个,否则(网络/终态/已下发信号/成员耗尽)原样上抛。
     */
    private Flux<ChatResponse> attemptStream(Prompt prompt, AtomicBoolean emitted,
            AtomicInteger cursor, AtomicReference<String> failoverTraceId) {
        int i = cursor.get();
        Flux<ChatResponse> attempt = members.get(i).stream(withOptions(prompt, memberOptions.get(i)));
        return attempt
                .doOnNext(chunk -> {
                    if (hasSignal(chunk)) {
                        emitted.set(true);
                    }
                })
                .doOnComplete(() -> closeFailover(failoverTraceId.get())) // 成功:波结束,链尾即最终成功模型
                .onErrorResume(error -> {
                    if (emitted.get() || isNetwork(error) || isTerminal(error)) {
                        closeFailover(failoverTraceId.get());
                        return Flux.error(error);
                    }
                    int next = cursor.incrementAndGet();
                    if (next >= members.size()) {
                        log.warn("池模型 {} 轮询 {} 个成员全部失败, 原样上抛最后一个异常: {}",
                                agentId, members.size(), error.getMessage());
                        // 不做任何包络:原始异常往上抛,让外层瞬时错误重试 advisor 能正常退避重跑。
                        closeFailover(failoverTraceId.get());
                        return Flux.error(error);
                    }
                    // 同一波连续失败切换共用一条 trace:完整切换链进 content,summary 只留最后切换模型。
                    failoverTraceId.set(logSwitch(error, next, failoverTraceId.get()));
                    return attemptStream(prompt, emitted, cursor, failoverTraceId);
                });
    }

    // ---- 换成员日志 + trace ----

    /** 换成员日志 + 容灾切换 trace(唯一归属点;同一波连续失败切换共用一条 trace,逐次追加模型)。 */
    private String logSwitch(Throwable error, int nextIndex, String failoverTraceId) {
        ModelSnapshot snap = memberSnapshots.get(nextIndex);
        log.warn("池模型 {} 请求异常({}: {}), 切换成员 {}({}) 重试({}/{})",
                agentId, error.getClass().getSimpleName(), error.getMessage(),
                snap.configId(), snap.model(), nextIndex + 1, members.size());
        if (events != null) {
            return events.modelFailoverSwitch(failoverTraceId, snap);
        }
        return failoverTraceId;
    }

    /** 容灾切换波收口:移除 traceId 对应链状态,之后新波重新新建 trace。 */
    private void closeFailover(String failoverTraceId) {
        if (events != null && failoverTraceId != null) {
            events.modelFailoverClose(failoverTraceId);
        }
    }

    // ---- 换成员请求:改写 prompt options 为该成员完整快照(并补原工具集) ----

    /**
     * 换成员请求:保留原 prompt 的 instructions 与工具集,仅把 options 换成该成员完整快照。
     * Spring AI 2.0.1 的 OpenAiChatModel 把 baseUrl/apiKey 固定在建模型时,故成员模型必须在
     * {@code ChatModelFactory} 构建时用各自快照,这里把请求 options 对齐到该成员再发出。
     */
    private static Prompt withOptions(Prompt prompt, OpenAiChatOptions memberOptions) {
        OpenAiChatOptions base = memberOptions;
        Object cur = prompt.getOptions();
        if (cur instanceof OpenAiChatOptions o && o.getToolCallbacks() != null
                && !o.getToolCallbacks().isEmpty()) {
            base = base.mutate().toolCallbacks(o.getToolCallbacks()).build();
        }
        return prompt.mutate().chatOptions(base).build();
    }

    // ---- 错误分类与防重护栏 ----

    /**
     * 网络异常判定(沿 cause 链下沉,深度封顶防环):SDK IO 信封 / IO / 超时 →
     * 断网类,换模型无意义,不介入原样上抛。
     */
    private static boolean isNetwork(Throwable error) {
        int depth = 0;
        for (Throwable t = error; t != null && depth < 8; depth++, t = t.getCause()) {
            if (t instanceof OpenAIIoException || t instanceof IOException || t instanceof TimeoutException) {
                return true;
            }
        }
        return false;
    }

    /**
     * 终态异常判定(沿 cause 链下沉):空响应耗尽({@link ModelCallException})与取消类
     * (AgentCancelled/Interrupted/Cancellation)不换模型,原样上抛。
     */
    private static boolean isTerminal(Throwable error) {
        int depth = 0;
        for (Throwable t = error; t != null && depth < 8; depth++, t = t.getCause()) {
            if (t instanceof ModelCallException || t instanceof AgentCancelledException
                    || t instanceof InterruptedException || t instanceof CancellationException) {
                return true;
            }
        }
        return false;
    }

    /**
     * 响应/单 chunk 是否携带有效信号(正文/reasoning/工具调用任一非空):
     * 流式防重复护栏用——已有信号下发后的流中断不换模型,避免重放重复
     * (判定口径与 {@link TransientErrorRetryAdvisor#hasSignal} 一致)。
     */
    private static boolean hasSignal(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return false;
        }
        AssistantMessage out = response.getResult().getOutput();
        if (out.getText() != null && !out.getText().isEmpty()) {
            return true;
        }
        if (out.getToolCalls() != null && !out.getToolCalls().isEmpty()) {
            return true;
        }
        Object rc = out.getMetadata() == null ? null : out.getMetadata().get("reasoningContent");
        return rc instanceof String s && !s.isEmpty();
    }
}