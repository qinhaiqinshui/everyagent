package dev.everyagent.plugin.modelratelimit;

import dev.everyagent.plugin.api.config.WorkerConfig;
import dev.everyagent.plugin.api.spi.TokenEstimator;
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

    private static WorkerConfig.Limits.ModelRate defaults(long waitMs) {
        return defaults(waitMs, 10);
    }

    private static WorkerConfig.Limits.ModelRate defaults(long waitMs, int queueCap) {
        return new WorkerConfig.Limits.ModelRate() {
            @Override public int queueCapacity() { return queueCap; }
            @Override public long waitTimeoutMs() { return waitMs; }
            @Override public long estWindowSec() { return 60; }
            @Override public double estSafetyRatio() { return 1.1; }
            @Override public double estEmaAlpha() { return 0.1; }
            @Override public double estFactorMin() { return 0.3; }
            @Override public double estFactorMax() { return 3.0; }
            @Override public int defaultRpm() { return 0; }
            @Override public int defaultMaxConcurrency() { return 0; }
            @Override public long defaultTpm() { return 0; }
        };
    }

    /**
     * 测试用 TokenEstimator 桩:factor 恒 1.0 的粗估桩 + 简单 EMA 校准(α=0.1,无收敛/漂移逻辑)。
     * 作为 SPI 实现只暴露 {@code estimate};{@code calibrate/factor/samples} 是桩自身的公开状态
     * 方法,供断言读取(限流器已不再自行校准,校准入口是 TokenCalibrationAdvisor)。
     */
    private static final class StubEstimator implements TokenEstimator {
        private final java.util.Map<String, double[]> states = new java.util.concurrent.ConcurrentHashMap<>();
        @Override public long estimate(String text, String configId) {
            if (text == null || text.isEmpty()) { return 0; }
            return Math.max(0, Math.round(rawTokens(text) * factor(configId)));
        }
        void calibrate(String configId, long estimatedTokens, long actualTokens) {
            if (actualTokens <= 0 || estimatedTokens <= 0) { return; }
            states.compute(configId, (k, v) -> {
                double oldFactor = (v == null) ? 1.0 : v[0];
                long samples = (v == null) ? 0 : (long) v[1];
                // 入参 estimatedTokens 是「已乘系数」的估算值 → 除回 raw 后算 ratio（消除自反馈）。
                double raw = estimatedTokens / oldFactor;
                double ratio = (double) actualTokens / raw;
                double nextFactor = 0.9 * oldFactor + 0.1 * ratio;
                return new double[]{nextFactor, samples + 1};
            });
        }
        double factor(String configId) {
            double[] v = states.get(configId);
            return (v == null) ? 1.0 : v[0];
        }
        long samples(String configId) {
            double[] v = states.get(configId);
            return (v == null) ? 0 : (long) v[1];
        }
        private static long rawTokens(String s) {
            if (s == null || s.isEmpty()) { return 0; }
            long cjk = 0, other = 0;
            for (int i = 0; i < s.length(); ) {
                int cp = s.codePointAt(i);
                i += Character.charCount(cp);
                Character.UnicodeScript sc = Character.UnicodeScript.of(cp);
                boolean isCjk = sc == Character.UnicodeScript.HAN
                        || sc == Character.UnicodeScript.HIRAGANA
                        || sc == Character.UnicodeScript.KATAKANA
                        || sc == Character.UnicodeScript.HANGUL;
                if (isCjk) { cjk++; }
                else if (!Character.isWhitespace(cp) && !Character.isISOControl(cp)) { other++; }
            }
            return cjk + (other + 3) / 4;
        }
    }

    private static final StubEstimator STUB_ESTIMATOR = new StubEstimator();

    @Test
    void estimateTokensCjkAndOther() {
        assertEquals(4, STUB_ESTIMATOR.estimate("你好世界", "est"), "4 个 CJK ≈ 4 token");
        assertEquals(1, STUB_ESTIMATOR.estimate("abcd", "est"), "4 个非 CJK ≈ 1 token");
        assertEquals(0, STUB_ESTIMATOR.estimate("   ", "est"), "空白不计");
        assertEquals(0, STUB_ESTIMATOR.estimate(null, "est"), "null 为 0");
        assertTrue(STUB_ESTIMATOR.estimate("你好 abcd", "est") >= 2, "混合估算下限");
        assertEquals(3, STUB_ESTIMATOR.estimate("你好 abcd", "est"), "2 CJK + 4 字符非 CJK → 2+1=3");
    }

    @Test
    void rpmBurstTimesOut() throws Exception {
        ModelRateLimitConfig cfg = new ModelRateLimitConfig(2, 0, 0, 1.0);
        ModelRateLimiter limiter = new ModelRateLimiter("m", cfg, defaults(200), STUB_ESTIMATOR);
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
        ModelRateLimiter limiter = new ModelRateLimiter("m", cfg, defaults(3000), STUB_ESTIMATOR);
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
        WorkerConfig.Limits.ModelRate d = defaults(5000, 1);
        ModelRateLimitConfig cfg = new ModelRateLimitConfig(0, 1, 0, 1.0);
        ModelRateLimiter limiter = new ModelRateLimiter("m", cfg, d, STUB_ESTIMATOR);
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
        ModelRateLimiter limiter = new ModelRateLimiter("m", cfg, defaults(1000), STUB_ESTIMATOR);
        ModelRateLimiter.Permit p1 = limiter.acquire();
        p1.onChunk(new org.springframework.ai.chat.messages.AssistantMessage("你好世界")); // 流中估算 est=round(4*1.0)=4
        p1.complete(8); // 限流器只做 tpm 记账;校准入口是 TokenCalibrationAdvisor(此处直接喂桩)
        STUB_ESTIMATOR.calibrate("m", STUB_ESTIMATOR.estimate("你好世界", "m"), 8); // raw=4/1.0=4, ratio=8/4=2.0 → factor=0.9*1.0+0.1*2.0=1.1
        assertEquals(1.1, STUB_ESTIMATOR.factor("m"), 1e-9);
        assertEquals(1L, STUB_ESTIMATOR.samples("m"));

        ModelRateLimiter.Permit p2 = limiter.acquire();
        p2.onChunk(new org.springframework.ai.chat.messages.AssistantMessage("你好世界")); // est=round(4*1.1)=4
        p2.complete(8);
        STUB_ESTIMATOR.calibrate("m", STUB_ESTIMATOR.estimate("你好世界", "m"), 8); // raw=4/1.1≈3.636, ratio≈2.2 → factor=0.9*1.1+0.1*2.2=1.21
        assertEquals(1.21, STUB_ESTIMATOR.factor("m"), 1e-9);
        assertEquals(2L, STUB_ESTIMATOR.samples("m"));
        p2.complete(0); // 幂等
    }

    @Test
    void timeoutMessageContainsAdvice() {
        ModelRateLimitConfig cfg = new ModelRateLimitConfig(1, 1, 100_000, 1.0);
        ModelRateLimiter limiter = new ModelRateLimiter("m", cfg, defaults(200), STUB_ESTIMATOR);
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

    @Test
    void waitObserverNotifiedWhileQueued() throws Exception {
        ModelRateLimitConfig cfg = new ModelRateLimitConfig(0, 1, 0, 1.0);
        ModelRateLimiter limiter = new ModelRateLimiter("m", cfg, defaults(5000), STUB_ESTIMATOR);
        ModelRateLimiter.Permit p1 = limiter.acquire();
        AtomicReference<ModelRateLimiter.WaitInfo> observed = new AtomicReference<>();
        AtomicReference<Long> observedWaitMs = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                limiter.acquire((info, waitMs) -> {
                    observed.set(info);
                    observedWaitMs.set(waitMs);
                });
            } catch (Exception ignored) {
            }
        });
        t.setDaemon(true);
        t.start();
        Thread.sleep(300);
        p1.complete(0);
        t.join(2000);
        assertNotNull(observed.get(), "排队时观察者应被回调");
        assertEquals("m", observed.get().configId());
        assertEquals(1, observed.get().inFlight(), "在飞 1 个");
        assertTrue(observed.get().waiters() >= 0);
        assertNotNull(observedWaitMs.get());
    }

    @Test
    void snapshotReflectsRuntimeState() throws Exception {
        ModelRateLimitConfig cfg = new ModelRateLimitConfig(2, 1, 100_000, 1.0);
        ModelRateLimiter limiter = new ModelRateLimiter("m", cfg, defaults(5000), STUB_ESTIMATOR);
        ModelRateLimiter.Permit p1 = limiter.acquire();
        ModelRateLimiter.Snapshot s = limiter.snapshot();
        assertTrue(s.enabled());
        assertEquals(2, s.rpm());
        assertEquals(1, s.maxConcurrency());
        assertEquals(1, s.inFlight());
        assertEquals(0, s.waiters());
        p1.complete(0);
        assertEquals(0, limiter.snapshot().inFlight(), "释放后在飞归零");
    }
}
