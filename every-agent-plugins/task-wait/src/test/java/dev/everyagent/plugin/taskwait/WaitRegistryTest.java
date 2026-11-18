package dev.everyagent.plugin.taskwait;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WaitRegistry 单测：bind 覆盖（最新胶囊胜）/ take 原子迁移在席 /
 * 在席可见性（并发互等竞态）/ finish 收口退场 / clear。
 */
class WaitRegistryTest {

    @Test
    void bindOverwritesLatestWins() {
        WaitRegistry r = new WaitRegistry();
        r.bind("t-1", "t-A", "任务A");
        r.bind("t-1", "t-B", "任务B");

        WaitRegistry.WaitBinding b = r.activeOf("t-1");
        assertNotNull(b, "覆盖后仍有绑定");
        assertEquals("t-B", b.targetTaskId(), "同任务最新胶囊胜");
        assertEquals("任务B", b.targetTitle());
    }

    @Test
    void takeMigratesToInFlightUntilFinish() {
        WaitRegistry r = new WaitRegistry();
        r.bind("t-1", "t-A", "任务A");

        WaitRegistry.WaitBinding b = r.take("t-1");
        assertNotNull(b);
        assertEquals("t-A", b.targetTaskId());
        // take 后迁移到在席等待（activeOf 可见——循环检测依赖），而非移除
        assertNotNull(r.activeOf("t-1"), "在席等待应对 activeOf 可见");
        assertEquals("t-A", r.activeOf("t-1").targetTaskId());
        assertNull(r.take("t-1"), "二次 take 返回 null（take-once 语义不变）");
        // finish 收口后退场
        r.finish("t-1");
        assertNull(r.activeOf("t-1"), "收口后在席等待退场");
    }

    /**
     * 回归（2026-11 生产互等挂起）：并发互等竞态下在席等待必须可见。
     * 旧实现 take 即移除——A 消费绑定（A→B）后 B 再消费（B→A）时，
     * 双方 detectCycle 查 activeOf 全程落空，互等检测失效、双方永久轮询。
     */
    @Test
    void takenBindingVisibleToConcurrentCycleDetection() {
        WaitRegistry r = new WaitRegistry();
        // A 已消费绑定（A→B），处于在席等待
        r.bind("A", "B", "任务B");
        assertNotNull(r.take("A"));
        // B 的绑定回指 A（尚未消费）
        r.bind("B", "A", "任务A");
        // B 侧循环检测视角：沿 B→A 逐跳，activeOf(A) 必须看到 A→B（在席）→ 回指 B
        WaitRegistry.WaitBinding aWait = r.activeOf("A");
        assertNotNull(aWait, "在席等待必须对并发方的循环检测可见");
        assertEquals("B", aWait.targetTaskId());
    }

    @Test
    void takeIsAtomicUnderConcurrency() throws InterruptedException {
        WaitRegistry r = new WaitRegistry();
        r.bind("t-1", "t-A", "任务A");

        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger winners = new AtomicInteger();
        List<WaitRegistry.WaitBinding> taken = new CopyOnWriteArrayList<>();

        for (int i = 0; i < threads; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    WaitRegistry.WaitBinding b = r.take("t-1");
                    if (b != null) {
                        winners.incrementAndGet();
                        taken.add(b);
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS), "并发线程应全部完成");

        assertEquals(1, winners.get(), "并发 take 恰好一个成功（原子）");
        assertEquals("t-A", taken.get(0).targetTaskId());
        // 并发竞态后仍在席可见（唯一 winner 的等待未收口）
        assertEquals("t-A", r.activeOf("t-1").targetTaskId());
    }

    @Test
    void clearRemovesBinding() {
        WaitRegistry r = new WaitRegistry();
        r.bind("t-1", "t-A", "任务A");
        r.clear("t-1");
        assertNull(r.activeOf("t-1"), "clear 后无绑定");
        assertNull(r.take("t-1"));
    }

    @Test
    void clearAlsoRemovesInFlightWait() {
        WaitRegistry r = new WaitRegistry();
        r.bind("t-1", "t-A", "任务A");
        assertNotNull(r.take("t-1"));
        r.clear("t-1");
        assertNull(r.activeOf("t-1"), "clear 一并清除在席等待");
    }

    @Test
    void clearOnAbsentIsNoop() {
        WaitRegistry r = new WaitRegistry();
        r.clear("t-nope"); // 不抛
        assertNull(r.activeOf("t-nope"));
    }

    @Test
    void activeOfAbsentReturnsNull() {
        assertNull(new WaitRegistry().activeOf("nope"));
    }

    @Test
    void bindingsAreIsolatedPerTask() {
        WaitRegistry r = new WaitRegistry();
        r.bind("t-1", "t-A", "任务A");
        r.bind("t-2", "t-B", "任务B");
        assertEquals("t-A", r.activeOf("t-1").targetTaskId());
        assertEquals("t-B", r.activeOf("t-2").targetTaskId());
    }
}
