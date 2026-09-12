package dev.everyagent.worker.task;

import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIRetryableException;
import com.openai.errors.OpenAIServiceException;
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
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.net.SocketException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 瞬时错误退避重试 advisor(架构 §5.2 + 红线:一个 advisor 只负责一个功能)。
 *
 * <p>仿 node 侧 {@code ai.call.retry_request} 节点(novel_agent-n
 * {@code src/ai/chain/nodes/retryRequest.ts}):可重试瞬时错误(限流 429 / 5xx /
 * 网络抖动)按配置退避策略({@code worker.retry.strategy},默认 fixed 固定间隔,
 * 可选 exponential 指数退避)重调同一请求,最多 {@code maxRequestRetries} 次;
 * 耗尽后原错误
 * 上抛(任务层收口)。n 的「耗尽后询问继续」属 task 层交互,worker 无对应交互通道,
 * 直接失败(用户可再运行任务)。
 *
 * <p>错误分类:Spring AI 2.0 的 OpenAI 兼容层基于官方 openai-java SDK(异步桥接,
 * 错误原样进 Flux),可重试判定即 SDK 错误信封 —— {@link OpenAIServiceException} 的
 * statusCode 为 429/5xx、{@link OpenAIRetryableException} SDK 瞬时标记、
 * {@link OpenAIIoException}/IO/超时(对应 n 的 540 网络瞬时占位码);
 * 401/403/400、取消、SSE 解析错误等不重试。
 *
 * <p>位置:order 高于 {@link ToolCallingAdvisor#DEFAULT_ORDER}(+300)+ 空响应重试
 * (+100),即工具循环<b>最内层</b>——本 advisor 只包住"单次 HTTP 模型调用"(与 n 的
 * ai 层 terminal 包装同构);一次空响应尝试可包含多次本重试。
 *
 * <p>流式防重复护栏(与 n 的「2xx 首 delta 前才重试」同语义):已有任何有效信号
 * (正文/reasoning/工具调用)chunk 下发后流中断的,默认不重试——重放会造成前端 delta 与
 * 轮内聚合重复;但<b>网络级瞬时错误</b>({@link SocketException}「Socket closed」/IO/超时)
 * 例外,即便已下发信号仍按退避重试:整波重试成功后本轮权威 {@code message} 以全文替换
 * 方式修正瞬态重复,终态内容仍正确,权衡下「可重试恢复」优于「因网络抖动直接失败」。
 * 退避等待期间 dispose(任务取消)会取消 {@link Mono#delay},不再触发重订(与 n 的
 * 中止感知 delayWithProgress 等效)。
 *
 * <p>设计纪律:与 {@link WorkerToolEventAdvisor} 同构——per-run 物化(持 {@link AgentEntity}
 * 引用仅用于日志归属);重试计数与"已下发信号"标记是 per-subscription 状态
 * ({@code Flux.defer} 闭包捕获),多任务并发安全。
 *
 * <p>重试可重复性:Spring AI 2.0.1 的 {@code DefaultAroundAdvisorChain} 是
 * {@code Deque.pop()} 一次性消费,重试必须走 {@code original.copy(this)}(见
 * {@link #attemptStream(ChatClientRequest, StreamAdvisorChain, StreamAdvisorChain,
 * AtomicBoolean, AtomicInteger)})——copy 出的新链只含「本 advisor 之后」的 advisors,
 * fresh Deque 可无限重试,自动包含链尾观测与未来新增的更内层 advisor,无需直打
 * ChatModel、无需改框架。
 */
public class TransientErrorRetryAdvisor implements CallAdvisor, StreamAdvisor {

    private static final Logger log = LoggerFactory.getLogger(TransientErrorRetryAdvisor.class);

    /** 日志归属 agent(只读 taskId/agentId,不发事件)。 */
    private final AgentEntity a;
    /** 重试参数(worker.retry,默认与 n 护栏全局默认一致)。 */
    private final WorkerProperties.Retry cfg;

    public TransientErrorRetryAdvisor(AgentEntity a, WorkerProperties.Retry cfg) {
        this.a = a;
        this.cfg = cfg;
    }

    @Override
    public String getName() {
        return "Transient Error Retry Advisor";
    }

    @Override
    public int getOrder() {
        // 工具循环最内层:紧贴模型 HTTP 调用;空响应重试(+100)在其外逐轮包裹。
        return ToolCallingAdvisor.DEFAULT_ORDER + 200;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        final CallAdvisorChain original = chain;
        CallAdvisorChain current = chain;
        int attempt = 0;
        long totalDelayMs = 0;
        while (true) {
            try {
                ChatClientResponse response = current.nextCall(request);
                if (attempt > 0) {
                    // 整波重试成功:仅此一条落盘(瞬态 attempt/progress 不落盘)。
                    a.task.events.retryResolved(a.agentId, attempt, totalDelayMs);
                }
                return response;
            } catch (RuntimeException e) {
                if (!isRetryable(e) || ++attempt > cfg.getMaxRequestRetries()) {
                    if (attempt > 0) {
                        // 整波重试耗尽失败:仅此一条落盘。
                        a.task.events.retryExhausted(a.agentId, attempt - 1,
                                cfg.getMaxRequestRetries(), e.getMessage());
                    }
                    throw e;
                }
                long ms = cfg.backoffMs(attempt);
                totalDelayMs += ms;
                a.task.events.retryAttempt(a.agentId, attempt, cfg.getMaxRequestRetries(), ms, e.getMessage());
                sleepBackoff(e, attempt);
                // 重试 = 从原始链 copy 出「本 advisor 之后」的新链(fresh Deque,规避一次性消费)。
                current = original.copy(this);
            }
        }
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        final StreamAdvisorChain original = chain;
        return Flux.defer(() -> {
            AtomicBoolean emittedSignal = new AtomicBoolean(false);
            AtomicInteger retries = new AtomicInteger(0);
            AtomicLong totalDelayMs = new AtomicLong(0);
            return attemptStream(request, original, original, emittedSignal, retries, totalDelayMs);
        });
    }

    /**
     * 单次尝试:透传 chunk 并标记"已下发有效信号";错误时若可重试、且(尚未下发任何信号,
     * 或为网络级瞬时错误)按退避重订同一请求;耗尽/不可重试/已下发信号且非网络错误 →
     * 原错误上抛(经工具循环 → 任务 error)。
     *
     * <p>重试可重复性(关键):Spring AI 2.0.1 的 {@code DefaultAroundAdvisorChain} 是
     * {@code Deque.pop()} 一次性消费——同一 chain 第二次 {@code nextStream} 必抛
     * {@code No StreamAdvisors available to execute}。因此重试必须走
     * {@code original.copy(this)}(框架原生 API:返回只含「本 advisor 之后」advisors 的全新链,
     * fresh Deque,可无限重试,且自动包含未来新增的更内层 advisor 与链尾观测)。
     *
     * <p>两个铁律:① copy 的基底必须是 advisor 收到的<b>原始链</b>({@code original})——
     * 对 copy 出的 tail 再 copy 会因原始列表不含本 advisor 而 {@code indexOf=-1} 抛
     * {@code IllegalArgumentException};② 每次重试都必须 fresh copy,不可复用上次的 tail
     * (其 Deque 同样会被消费空)。
     *
     * <p>事件与落盘纪律:重试开始发 {@code retry.attempt}(瞬态),退避期间每秒
     * {@code retry.progress}(瞬态,不落盘——每秒写盘会爆磁盘);整波重试<b>成功</b>后
     * {@code retry.resolved} 落盘一条,<b>耗尽</b>后 {@code retry.exhausted} 落盘一条。
     *
     * @param chain    本次要执行的链(首次 = {@code original},其后 = 每次 copy 出的 fresh tail)
     * @param original copy 的基底,始终为 advisor 收到的原始链
     * @param totalDelayMs 累计退避总耗时(供 retry.resolved 一条落盘)
     */
    private Flux<ChatClientResponse> attemptStream(ChatClientRequest request, StreamAdvisorChain chain,
            StreamAdvisorChain original, AtomicBoolean emittedSignal, AtomicInteger retries,
            AtomicLong totalDelayMs) {
        return chain.nextStream(request)
                .doOnNext(chunk -> {
                    if (hasSignal(chunk)) {
                        emittedSignal.set(true);
                    }
                })
                .doOnComplete(() -> {
                    // 重试过且最终成功:整波仅此一条落盘。
                    if (retries.get() > 0) {
                        a.task.events.retryResolved(a.agentId, retries.get(), totalDelayMs.get());
                    }
                })
                .onErrorResume(error -> {
                    // 防重复护栏:已下发有效信号后默认不重试(重放会造成前端 delta 与轮内聚合
                    // 重复);网络级瞬时错误(Socket closed/IO/超时)例外——整波重试成功后,
                    // 本轮权威 message 以全文替换修正瞬态重复,终态内容仍正确(见类 javadoc)。
                    if (!isRetryable(error) || (emittedSignal.get() && !isNetworkError(error))) {
                        return Flux.error(error);
                    }
                    int attempt = retries.incrementAndGet();
                    if (attempt > cfg.getMaxRequestRetries()) {
                        // 整波重试耗尽失败:仅此一条落盘。
                        a.task.events.retryExhausted(a.agentId, attempt - 1,
                                cfg.getMaxRequestRetries(), error.getMessage());
                        return Flux.error(error);
                    }
                    long ms = cfg.backoffMs(attempt);
                    totalDelayMs.addAndGet(ms);
                    String errMsg = error.getMessage();
                    a.task.events.retryAttempt(a.agentId, attempt, cfg.getMaxRequestRetries(), ms, errMsg);
                    log.warn("任务 {} agent {} 模型瞬时错误({}: {}),{}ms 后重试({}/{})",
                            a.task.taskId, a.agentId, error.getClass().getSimpleName(),
                            errMsg, ms, attempt, cfg.getMaxRequestRetries());
                    return backoffAndRetry(ms, attempt, errMsg, request, original,
                            emittedSignal, retries, totalDelayMs);
                });
    }

    /**
     * 退避 + 倒计时:每秒一条 {@code retry.progress}(瞬态不落盘),最后一秒结束后触发重试。
     * 中止(dispose)会取消 interval,不再触发重试(与 n 的中止感知 delayWithProgress 等效);
     * 实际等待 = 向上取整到秒,略大于 {@code delayMs},可接受。
     */
    private Flux<ChatClientResponse> backoffAndRetry(long delayMs, int attempt, String error,
            ChatClientRequest request, StreamAdvisorChain original,
            AtomicBoolean emittedSignal, AtomicInteger retries, AtomicLong totalDelayMs) {
        long ticks = Math.max(1, (delayMs + 999) / 1000);
        return Flux.interval(Duration.ZERO, Duration.ofSeconds(1))
                .take(ticks)
                .concatMap(i -> {
                    long remaining = Math.max(0, delayMs - (i + 1) * 1000);
                    long elapsed = delayMs - remaining;
                    a.task.events.retryProgress(a.agentId, attempt, cfg.getMaxRequestRetries(),
                            delayMs, elapsed, remaining, error);
                    if (i == ticks - 1) {
                        return attemptStream(request, original.copy(this), original,
                                emittedSignal, retries, totalDelayMs);
                    }
                    return Flux.empty();
                });
    }

    /** 阻塞退避(仅非流式路径);中断 → 取消类异常穿透,交任务层收口为 cancelled。 */
    private void sleepBackoff(Throwable error, int attempt) {
        long ms = cfg.backoffMs(attempt);
        log.warn("任务 {} agent {} 模型瞬时错误({}: {}),{}ms 后重试({}/{})",
                a.task.taskId, a.agentId, error.getClass().getSimpleName(), error.getMessage(),
                ms, attempt, cfg.getMaxRequestRetries());
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new AgentCancelledException("interrupted");
        }
    }

    /**
     * 瞬时错误判定(沿 cause 链下沉,深度封顶防环):SDK 错误信封状态码 429/5xx、
     * SDK 瞬时标记、IO/超时、{@link SocketException}(常见「Socket closed」,网络抖动)
     * → 可重试;其余(401/403/400、取消、解析错误等)不可重试。
     * 注:{@link SocketException} 本就是 {@link IOException} 子类,显式列出仅为语义明确。
     */
    private static boolean isRetryable(Throwable error) {
        int depth = 0;
        for (Throwable t = error; t != null && depth < 8; depth++, t = t.getCause()) {
            if (t instanceof OpenAIServiceException svc) {
                int code = svc.statusCode();
                return code == 429 || (code >= 500 && code <= 599);
            }
            if (t instanceof OpenAIRetryableException || t instanceof OpenAIIoException
                    || t instanceof IOException || t instanceof TimeoutException
                    || t instanceof SocketException) {
                return true;
            }
        }
        return false;
    }

    /**
     * 网络级瞬时错误判定(沿 cause 链下沉):IO/超时/SDK IO 信封/{@link SocketException}
     * (「Socket closed」,{@link IOException} 子类已覆盖)。这些错误即使已下发部分有效信号,
     * 仍允许整波重试(网络抖动重放,终态由本轮权威 {@code message} 全文替换修正);
     * 429/5xx 等服务端状态错误不在此列,仍守「已下发信号不重试」的防重复护栏。
     */
    private static boolean isNetworkError(Throwable error) {
        int depth = 0;
        for (Throwable t = error; t != null && depth < 8; depth++, t = t.getCause()) {
            if (t instanceof OpenAIIoException || t instanceof IOException || t instanceof TimeoutException) {
                return true;
            }
        }
        return false;
    }

    /**
     * 响应/单 chunk 是否携带有效信号(正文/reasoning/工具调用任一非空):
     * 流式防重复护栏用——已有信号下发后的流中断默认不重试,避免重放重复;
     * 网络级瞬时错误例外(见 {@link #isNetworkError} 与 onErrorResume 判定)。
     * 判定口径与 {@link EmptyResponseRetryAdvisor} 的空响应三条件一致。
     */
    private static boolean hasSignal(ChatClientResponse response) {
        ChatResponse cr = response.chatResponse();
        if (cr == null || cr.getResult() == null || cr.getResult().getOutput() == null) {
            return false;
        }
        AssistantMessage out = cr.getResult().getOutput();
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
