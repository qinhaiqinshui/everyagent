package dev.everyagent.worker.task;

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

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 空响应重试 advisor(架构 §5.2 + 红线:一个 advisor 只负责一个功能)。
 *
 * <p>仿 node 侧 {@code agent.execute.empty_response_retry} 节点(novel_agent-n
 * {@code src/agent/agent/nodes/emptyResponseRetry.ts}):本轮模型响应为空
 * (无正文、无 reasoning、无工具调用)时,按指数退避重调同一请求,直到拿到非空响应或
 * 重试额度耗尽;耗尽抛 {@link ModelCallException}(任务层收口为 error 事件,对应 n 的
 * code=492 empty_response_retry_exceeded 文案语义)。
 *
 * <p>位置:order 高于 {@link ToolCallingAdvisor#DEFAULT_ORDER}(+300),位于工具循环
 * <b>内侧</b>——循环每轮的下行使本 advisor 只包住"单次模型调用"(与 n 的 per-round 节点
 * 同构);重试 = 对同一 request 重新订阅(同请求重调,不重放已执行的工具;工具循环仍完全
 * 由框架驱动,AGENTS.md §13 合规)。流式各次尝试 concat 在同一轮 Flux 上,框架的轮内聚合
 * 把空尝试(零文本)与成功尝试无缝合并,轮末权威 {@code message} 只含成功尝试的内容;
 * 空尝试对前端不可见(无 delta)。
 *
 * <p>设计纪律:与 {@link WorkerToolEventAdvisor} 同构——per-run 物化(持 {@link AgentEntity}
 * 引用仅用于日志归属);重试计数与"已见信号"标记是 per-subscription 状态({@code Flux.defer}
 * 闭包捕获,每轮/每次订阅全新),不持有跨轮可变状态,多任务并发安全。
 *
 * <p>重试可重复性:Spring AI 2.0.1 的 {@code DefaultAroundAdvisorChain} 是
 * {@code Deque.pop()} 一次性消费,重试必须走 {@code original.copy(this)}(见
 * {@link #attemptStream(ChatClientRequest, StreamAdvisorChain, StreamAdvisorChain,
 * AtomicBoolean, AtomicInteger)})——copy 出的新链只含「本 advisor 之后」的 advisors,
 * fresh Deque 可无限重试,自动包含链尾观测与更内层的瞬时错误重试 advisor,无需直打
 * ChatModel、无需改框架。
 */
public class EmptyResponseRetryAdvisor implements CallAdvisor, StreamAdvisor {

    private static final Logger log = LoggerFactory.getLogger(EmptyResponseRetryAdvisor.class);

    /** 日志归属 agent(只读 taskId/agentId,不发事件)。 */
    private final AgentEntity a;
    /** 重试参数(worker.retry,默认与 n 护栏全局默认一致)。 */
    private final WorkerProperties.Retry cfg;
    /** 空响应最大尝试次数 = 重试数 + 1(与 n 的 maxEmptyAttempts 同构)。 */
    private final int maxAttempts;

    public EmptyResponseRetryAdvisor(AgentEntity a, WorkerProperties.Retry cfg) {
        this.a = a;
        this.cfg = cfg;
        this.maxAttempts = cfg.getMaxEmptyResponseRetries() + 1;
    }

    @Override
    public String getName() {
        return "Empty Response Retry Advisor";
    }

    @Override
    public int getOrder() {
        // 工具循环内侧第一圈:每轮模型调用都穿过本 advisor;
        // 瞬时错误重试(+200)更内,一次"空响应尝试"可含多次瞬时退避重调(与 n 的层次一致)。
        return ToolCallingAdvisor.DEFAULT_ORDER + 100;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        final CallAdvisorChain original = chain;
        CallAdvisorChain current = chain;
        ChatClientResponse response = current.nextCall(request);
        int attempt = 1; // 已完成的空响应尝试数(含首次)
        long totalDelayMs = 0;
        while (!hasSignal(response) && attempt < maxAttempts) {
            long ms = cfg.backoffMs(attempt);
            totalDelayMs += ms;
            a.task.events.retryAttempt(a.agentId, attempt, maxAttempts, ms, "empty_response");
            sleepBackoff(attempt);
            // 重试 = 从原始链 copy 出「本 advisor 之后」的新链(fresh Deque,规避一次性消费)。
            current = original.copy(this);
            response = current.nextCall(request);
            attempt++;
        }
        if (!hasSignal(response)) {
            // 空响应重试耗尽:整波仅此一条落盘。
            a.task.events.retryExhausted(a.agentId, attempt - 1, maxAttempts, "empty_response");
            throw new ModelCallException("模型连续返回空响应 " + maxAttempts + " 次,重试已耗尽", null);
        }
        if (attempt > 1) {
            // 重试后拿到非空响应:整波仅此一条落盘。
            a.task.events.retryResolved(a.agentId, attempt - 1, totalDelayMs);
        }
        return response;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        final StreamAdvisorChain original = chain;
        return Flux.defer(() -> {
            AtomicBoolean sawSignal = new AtomicBoolean(false);
            AtomicInteger emptyAttempts = new AtomicInteger(0);
            AtomicLong totalDelayMs = new AtomicLong(0);
            return attemptStream(request, original, original, sawSignal, emptyAttempts, totalDelayMs);
        });
    }

    /**
     * 单次尝试:透传 chunk 并标记"已见有效信号";完成时若整轮为空(任何 chunk 都无
     * 正文/reasoning/工具调用)则按退避重订同一请求;耗尽以 {@link ModelCallException}
     * 错误化本轮(经工具循环聚合上抛 → 任务 error 事件)。中止(dispose)会取消退避
     * 计时,不再触发下一次重订(与 n 的中止感知 delayForRetry 等效)。
     *
     * <p>重试可重复性(关键):Spring AI 2.0.1 的 {@code DefaultAroundAdvisorChain} 是
     * {@code Deque.pop()} 一次性消费——同一 chain 第二次 {@code nextStream} 必抛
     * {@code No StreamAdvisors available to execute}。因此重试必须走
     * {@code original.copy(this)}(框架原生 API:返回只含「本 advisor 之后」advisors 的全新链,
     * fresh Deque,可无限重试;对瞬时错误重试 advisor(+200,更内层)同样在 copy 链内生效,
     * 嵌套重试语义正确)。两个铁律:① copy 基底必须是原始链({@code original}),对 tail 再
     * copy 会 {@code indexOf=-1} 抛 {@code IllegalArgumentException};② 每次重试 fresh
     * copy,不可复用上次 tail(其 Deque 同样会被消费空)。
     *
     * <p>事件与落盘纪律:退避期间每秒 {@code retry.progress}(瞬态,不落盘——每秒写盘会爆
     * 磁盘);整波重试<b>成功</b>后 {@code retry.resolved} 落盘一条,<b>耗尽</b>后
     * {@code retry.exhausted} 落盘一条。
     *
     * @param chain    本次要执行的链(首次 = {@code original},其后 = 每次 copy 出的 fresh tail)
     * @param original copy 的基底,始终为 advisor 收到的原始链
     */
    private Flux<ChatClientResponse> attemptStream(ChatClientRequest request, StreamAdvisorChain chain,
            StreamAdvisorChain original, AtomicBoolean sawSignal, AtomicInteger emptyAttempts,
            AtomicLong totalDelayMs) {
        return chain.nextStream(request)
                .doOnNext(chunk -> {
                    if (hasSignal(chunk)) {
                        sawSignal.set(true);
                    }
                })
                .concatWith(Flux.defer(() -> {
                    if (sawSignal.get()) {
                        // 重试过且最终拿到非空信号:整波仅此一条落盘。
                        if (emptyAttempts.get() > 0) {
                            a.task.events.retryResolved(a.agentId, emptyAttempts.get(), totalDelayMs.get());
                        }
                        return Flux.empty();
                    }
                    int attempt = emptyAttempts.incrementAndGet();
                    if (attempt >= maxAttempts) {
                        // 空响应重试耗尽:整波仅此一条落盘。
                        a.task.events.retryExhausted(a.agentId, attempt, maxAttempts, "empty_response");
                        return Flux.error(new ModelCallException(
                                "模型连续返回空响应 " + maxAttempts + " 次,重试已耗尽", null));
                    }
                    long ms = cfg.backoffMs(attempt);
                    totalDelayMs.addAndGet(ms);
                    a.task.events.retryAttempt(a.agentId, attempt, maxAttempts, ms, "empty_response");
                    log.warn("任务 {} agent {} 模型返回空响应,{}ms 后重试({}/{})",
                            a.task.taskId, a.agentId, ms, attempt, maxAttempts - 1);
                    return backoffAndRetry(ms, attempt, request, original,
                            sawSignal, emptyAttempts, totalDelayMs);
                }));
    }

    /**
     * 退避 + 倒计时:每秒一条 {@code retry.progress}(瞬态不落盘),最后一秒结束后触发重试。
     * 中止(dispose)会取消 interval,不再触发重试;实际等待 = 向上取整到秒。
     */
    private Flux<ChatClientResponse> backoffAndRetry(long delayMs, int attempt,
            ChatClientRequest request, StreamAdvisorChain original,
            AtomicBoolean sawSignal, AtomicInteger emptyAttempts, AtomicLong totalDelayMs) {
        long ticks = Math.max(1, (delayMs + 999) / 1000);
        return Flux.interval(Duration.ZERO, Duration.ofSeconds(1))
                .take(ticks)
                .concatMap(i -> {
                    long remaining = Math.max(0, delayMs - (i + 1) * 1000);
                    long elapsed = delayMs - remaining;
                    a.task.events.retryProgress(a.agentId, attempt, maxAttempts,
                            delayMs, elapsed, remaining, "empty_response");
                    if (i == ticks - 1) {
                        return attemptStream(request, original.copy(this), original,
                                sawSignal, emptyAttempts, totalDelayMs);
                    }
                    return Flux.empty();
                });
    }

    /** 阻塞退避(仅非流式路径);中断 → 取消类异常穿透,交任务层收口为 cancelled。 */
    private void sleepBackoff(int attempt) {
        long ms = cfg.backoffMs(attempt);
        log.warn("任务 {} agent {} 模型返回空响应,{}ms 后重试({}/{})",
                a.task.taskId, a.agentId, ms, attempt, maxAttempts - 1);
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new AgentCancelledException("interrupted");
        }
    }

    /**
     * 响应/单 chunk 是否携带有效信号:正文、reasoning、工具调用任一非空
     * (与 n 的 isEmptyResponse 三条件对齐;reasoning 是 OpenAI 兼容 chunk metadata 的
     * 累积值,见 OpenAiChatModel REASONING_CONTENT)。空帧/usage 修正帧天然为 false。
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
