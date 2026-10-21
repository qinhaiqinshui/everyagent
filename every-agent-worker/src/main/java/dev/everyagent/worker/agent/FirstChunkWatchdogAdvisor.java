package dev.everyagent.worker.agent;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 模型流首帧看门狗(架构 §7.4.2;红线:一个 advisor 只负责一个功能)。
 *
 * <p>唯一职责 = 对「流订阅成功后长时间没有任何 chunk」的病态静默给出确定性中断:
 * {@code Flux.timeout(first, next)} 在首个 chunk 超时窗口({@code worker.model-first-chunk-timeout-ms})
 * 或后续 chunk 间隔窗口({@code worker.model-timeout-ms})到期时抛 {@link TimeoutException},
 * 该异常属瞬时错误分类,由外层 {@code TransientErrorRetryAdvisor} 按既有退避路径重试,
 * 耗尽后任务显式报错——不再静默永久挂起。
 *
 * <p>封堵的正是 okhttp readTimeout 与 model-length-guard stall 都触不到的两类形态
 * (2026-10 t_6342 实锤):
 * <ol>
 *   <li>网关周期性下发 SSE 保活字节(注释行,不产 chunk)——okhttp 字节级 readTimeout
 *       被无限续命,流永不完成也永不报错;</li>
 *   <li>openai-java 异步桥在响应头到达前不随 dispose 取消底层 call——泄漏 call 占满
 *       okhttp dispatcher per-host 槽位后,新请求在队列里无限等待、零超时。</li>
 * </ol>
 *
 * <p><b>位置:advisor 链最内层</b>({@code ToolCallingAdvisor.DEFAULT_ORDER + 550},
 * RateLimit +500 之内、紧贴真实模型 HTTP 调用):①排队等限流 permit 的时间不消耗
 * 看门狗预算(限流 acquire 在本 advisor 之外);②工具循环每轮递归都重新穿过本 advisor,
 * 每轮模型调用各自享有独立的首帧窗口;③{@code adviseStream} 在链遍历期被调用一次,
 * 超时后由重试 advisor 经 {@code original.copy(this)} 重建 fresh 链重订——本 advisor
 * 无 per-subscription 可变状态泄漏(seen 标记在 {@code guard} 返回的 Flux 闭包内,
 * 每次订阅全新)。
 *
 * <p>首帧超时的错误信息替换为可读中文(便于重试事件与任务 error 直读);首帧后
 * 的间隔超时保留 reactor 原始异常(message 含源名,排障更有用)。
 */
public class FirstChunkWatchdogAdvisor implements StreamAdvisor {

    private final long firstChunkMs;
    private final long nextChunkMs;

    /**
     * @param firstChunkMs 首帧(第一个 chunk)超时 ms;≤0 关闭看门狗(直通)
     * @param nextChunkMs  首帧后 chunk 间隔上限 ms(对齐 {@code worker.model-timeout-ms});
     *                     实际生效取 {@code max(nextChunkMs, firstChunkMs)},防配置倒挂误杀
     */
    public FirstChunkWatchdogAdvisor(long firstChunkMs, long nextChunkMs) {
        this.firstChunkMs = firstChunkMs;
        this.nextChunkMs = Math.max(nextChunkMs, firstChunkMs);
    }

    @Override
    public String getName() {
        return "First Chunk Watchdog Advisor";
    }

    @Override
    public int getOrder() {
        // 最内层:RateLimitAdvisor(+500) 之内、紧贴模型 HTTP 调用(理由见类 javadoc)
        return ToolCallingAdvisor.DEFAULT_ORDER + 550;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return guard(chain.nextStream(request), firstChunkMs, nextChunkMs);
    }

    /**
     * 看门狗包裹(静态、与 advisor 上下文无关,便于单测直测时序语义):
     * 无首 chunk 静默 → 超时中断(错误信息可读);有首 chunk 后长静默 → 超时中断
     * (保留 reactor 原始异常);≤0 直通原 flux(关闭开关)。
     *
     * <p>本 reactor 版本无 {@code timeout(Duration, Duration)} 重载,用
     * {@code timeout(Publisher first, Function next)} 变体等价表达:首帧指示器
     * {@code interval(first).take(1)} 在 deadline 发一枚触发超时;首帧到达后指示器
     * 被框架 dispose,改挂 per-item 的 {@code interval(next).take(1)}(上一 chunk 后
     * next 内无下一 chunk 即超时;正常到达则旧指示器被 dispose、挂新指示器)。
     */
    static <T> Flux<T> guard(Flux<T> source, long firstMs, long nextMs) {
        if (firstMs <= 0) {
            return source;
        }
        long next = Math.max(nextMs, firstMs);
        AtomicBoolean seen = new AtomicBoolean(false);
        return source
                .doOnNext(t -> seen.set(true))
                .timeout(Flux.interval(Duration.ofMillis(firstMs)).take(1),
                        t -> Flux.interval(Duration.ofMillis(next)).take(1))
                .onErrorMap(TimeoutException.class, e -> {
                    if (seen.get()) {
                        return e; // 首帧后间隔超时:保留原始异常与堆栈
                    }
                    TimeoutException friendly = new TimeoutException(
                            "模型流首帧超时: " + firstMs + "ms 内未收到任何输出(连接静默,看门狗中断并交退避重试)");
                    friendly.initCause(e);
                    return friendly;
                });
    }
}
