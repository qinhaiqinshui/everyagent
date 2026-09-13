package dev.everyagent.worker.task;

import dev.everyagent.worker.config.WorkerProperties;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ModelRateLimiter} 单测:rpm 滑动窗口、并发闸门、队列满/超时报错、EMA 系数校准。
 * 时间敏感用例用短 wait-timeout 控制(不真等 60s 窗口)。
 */
class ModelRateLimiterTest {

    private static WorkerProperties.ModelRate defaults(long waitMs) {
        WorkerProperties.ModelRate d = new WorkerProperties.ModelRate();
        d.setWaitTimeoutMs(waitMs);
        return d;
    }

    @Test
    void estimateTokensCjkAndOther() {
        assertEquals(4, ModelRateLimiter.estimateTokens("你好世界"), "4 个 CJK ≈ 4 token");
        assertEquals(1, ModelRateLimiter.estimateTokens("abcd"), "4 个非 CJK ≈ 1 token");
        assertEquals(0, ModelRateLimiter.estimateTokens("   "), "空白不计");
        assertEquals(0, ModelRateLimiter.estimateTokens(null), "null 为 0");
        assertTrue(ModelRateLimiter.estimateTokens("你好 abcd") >= 2, "混合估算下限");
        assertEquals(3, ModelRateLimiter.estimateTokens("你好 abcd"), "2 CJK + 4 字符非 CJK → 2+1=3");
    }

    @Test
    void rpmBurstTimesOut() throws Exception {
        ModelRateLimitConfig cfg = new ModelRateLimitConfig(2, 0, 0, 1.0);
        ModelRateLimiter limiter = new ModelRateLimiter("m", cfg, defaults(200), -1, null);
        ModelRateLimiter.Permit p1 = limiter.acquire();
        ModelRateLimiter.Permit p2 = limiter.acquire();
        try {
            // rpm=2 已满,窗口 60s 不会滚动 → 200ms 后超时报错
            assertThrows(ModelRateLimitException.class, () -> limiter.acquire());
        } finally {
            p1.cancel();
            p2.cancel();
        }
    }

    @Test
    void concurrencyGatesAndReleases() throws Exception {
        ModelRateLimitConfig cfg = new ModelRateLimitConfig(0, 1, 0, 1.0);
        ModelRateLimiter limiter = new ModelRateLimiter("m", cfg, defaults(3000), -1, null);
        ModelRateLimiter.Permit p1 = limiter.acquire();
        AtomicReference<ModelRateLimiter.Permit> p2 = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                p2.set(limiter.acquire());
            } catch (Exception ignored) {
            }
        });
        t.setDaemon(true);
        t.start();
        Thread.sleep(200);
        assertNull(p2.get(), "并发满,第 2 个应排队未放行");
        p1.complete(0); // 释放并发
        t.join(2000);
        assertNotNull(p2.get(), "释放后应放行");
        p2.get().cancel();
    }

    @Test
    void queueFullImmediatelyFails() throws Exception {
        WorkerProperties.ModelRate d = defaults(5000);
        d.setQueueCapacity(1);
        ModelRateLimitConfig cfg = new ModelRateLimitConfig(0, 1, 0, 1.0);
        ModelRateLimiter limiter = new ModelRateLimiter("m", cfg, d, -1, null);
        ModelRateLimiter.Permit p1 = limiter.acquire();
        AtomicReference<ModelRateLimiter.Permit> waiter = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                waiter.set(limiter.acquire());
            } catch (Exception ignored) {
            }
        });
        t.setDaemon(true);
        t.start();
        Thread.sleep(200);
        try {
            // 队列容量 1,已有一个 waiter → 立即抛,不等 5s
            assertThrows(ModelRateLimitException.class, () -> limiter.acquire());
        } finally {
            p1.complete(0); // 释放后 waiter 能放行并退出线程
            t.join(2000);
        }
    }

    @Test
    void emaCalibrationConvergesAndNotifies() throws Exception {
        ModelRateLimitConfig cfg = new ModelRateLimitConfig(0, 0, 1_000_000, 1.0);
        AtomicReference<Double> notifiedFactor = new AtomicReference<>();
        AtomicReference<Long> notifiedSamples = new AtomicReference<>();
        ModelRateLimiter limiter = new ModelRateLimiter("m", cfg, defaults(1000), -1,
                (f, s) -> {
                    notifiedFactor.set(f);
                    notifiedSamples.set(s);
                });
        ModelRateLimiter.Permit p1 = limiter.acquire();
        p1.onChunk("你好世界"); // est=4
        p1.complete(8);          // actual=8 → ratio=2.0 → factor=0.9*1.0+0.1*2.0=1.1
        assertEquals(1.1, limiter.currentFactor(), 1e-9);
        assertNotNull(notifiedFactor.get(), "校准后回调通知持久化");
        assertEquals(1L, notifiedSamples.get());

        ModelRateLimiter.Permit p2 = limiter.acquire();
        p2.onChunk("你好世界"); // est=4
        p2.complete(8);          // ratio=2.0 → factor=0.9*1.1+0.1*2.0=1.19
        assertEquals(1.19, limiter.currentFactor(), 1e-9);
        assertEquals(2L, notifiedSamples.get());
        p2.complete(0); // 幂等
    }

    @Test
    void timeoutMessageContainsAdvice() {
        ModelRateLimitConfig cfg = new ModelRateLimitConfig(1, 1, 100_000, 1.0);
        ModelRateLimiter limiter = new ModelRateLimiter("m", cfg, defaults(200), -1, null);
        try {
            limiter.acquire();
            limiter.acquire(); // 应超时
        } catch (ModelRateLimitException e) {
            assertTrue(e.getMessage().contains("建议"), "错误信息含建议");
            assertTrue(e.getMessage().contains("rpm"), "错误信息含 rpm 提示");
            assertTrue(e.getMessage().contains("max-concurrency"), "错误信息含并发提示");
        } catch (Exception e) {
            throw new AssertionError("期望 ModelRateLimitException,得到 " + e, e);
        }
    }
}