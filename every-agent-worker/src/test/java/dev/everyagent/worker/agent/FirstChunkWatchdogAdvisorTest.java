package dev.everyagent.worker.agent;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 首帧看门狗时序语义(架构 §7.4.2,t_6342 静默挂起修复)。
 * 直测 {@link FirstChunkWatchdogAdvisor#guard} 静态包裹——advisor 只是它的薄壳
 * (链位置语义由 javadoc/order 约束,不在单测范围)。
 */
class FirstChunkWatchdogAdvisorTest {

    /** 订阅成功但永不发数(零首 chunk 静默):超时中断,错误信息可读(供重试事件/任务 error 直读)。 */
    @Test
    void firstChunkSilenceTimesOutWithReadableError() throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> err = new AtomicReference<>();

        FirstChunkWatchdogAdvisor.guard(Flux.never(), 150, 60_000)
                .subscribe(s -> { }, e -> {
                    err.set(e);
                    done.countDown();
                }, done::countDown);

        assertTrue(done.await(5, TimeUnit.SECONDS), "5s 内应触发首帧超时");
        Throwable e = err.get();
        assertTrue(e instanceof TimeoutException, "应为 TimeoutException(瞬时错误分类,交既有退避重试),实际: " + e);
        assertTrue(e.getMessage().contains("首帧超时"), "错误信息应为可读中文,实际: " + e.getMessage());
    }

    /** 正常流(多 chunk 即时到达)不受影响,元素原样透传。 */
    @Test
    void normalStreamPassesThrough() {
        List<String> out = FirstChunkWatchdogAdvisor.guard(Flux.just("a", "b", "c"), 2_000, 60_000)
                .collectList()
                .block(Duration.ofSeconds(5));
        assertEquals(List.of("a", "b", "c"), out);
    }

    /** 首帧到达后的长静默(chunk 间隔超时):同样中断,但保留 reactor 原始异常(含源名,排障用)。 */
    @Test
    void interChunkSilenceAfterFirstChunkTimesOutWithOriginalError() throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> err = new AtomicReference<>();

        FirstChunkWatchdogAdvisor.guard(Flux.just("a").concatWith(Flux.never()), 2_000, 150)
                .subscribe(s -> { }, e -> {
                    err.set(e);
                    done.countDown();
                }, done::countDown);

        assertTrue(done.await(5, TimeUnit.SECONDS), "5s 内应触发 chunk 间隔超时");
        Throwable e = err.get();
        assertTrue(e instanceof TimeoutException, "应为 TimeoutException,实际: " + e);
        assertFalse(e.getMessage().contains("首帧超时"), "首帧已到,不应误报为首帧超时: " + e.getMessage());
    }

    /** 首帧窗口耗尽前源正常完成(empty 流):看门狗不干预,正常 complete。 */
    @Test
    void emptyCompletionIsNotWatchdogged() throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> err = new AtomicReference<>();

        FirstChunkWatchdogAdvisor.guard(Flux.empty(), 5_000, 5_000)
                .subscribe(s -> { }, e -> {
                    err.set(e);
                    done.countDown();
                }, done::countDown);

        assertTrue(done.await(5, TimeUnit.SECONDS), "empty 流应立即正常完成");
        assertEquals(null, err.get(), "不应有错误(empty 完成交由 EmptyResponseRetryAdvisor 处理)");
    }

    /** ≤0 关闭:直通原 flux 实例,零开销。 */
    @Test
    void disabledIsPassthrough() {
        Flux<String> src = Flux.just("x");
        assertSame(src, FirstChunkWatchdogAdvisor.guard(src, 0, 0));
    }
}
