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
 * WaitRegistry 单测：bind 覆盖（最新胶囊胜）/ take 原子 / clear。
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
    void takeRemovesAtomically() {
        WaitRegistry r = new WaitRegistry();
        r.bind("t-1", "t-A", "任务A");

        WaitRegistry.WaitBinding b = r.take("t-1");
        assertNotNull(b);
        assertEquals("t-A", b.targetTaskId());
        assertNull(r.activeOf("t-1"), "take 后绑定移除");
        assertNull(r.take("t-1"), "二次 take 返回 null（一次性消费）");
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
        assertNull(r.activeOf("t-1"));
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
