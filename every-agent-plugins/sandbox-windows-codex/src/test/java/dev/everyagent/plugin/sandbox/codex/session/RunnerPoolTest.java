package dev.everyagent.plugin.sandbox.codex.session;

import dev.everyagent.plugin.sandbox.codex.runner.FrameCodec;
import dev.everyagent.plugin.sandbox.codex.runner.IpcMessage;
import dev.everyagent.plugin.sandbox.codex.session.RunnerPools.RunnerLease;
import dev.everyagent.plugin.sandbox.codex.session.RunnerPools.Settings;
import dev.everyagent.plugin.sandbox.codex.session.RunnerPools.Spawner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RunnerPools} 连接管理语义：复用/独占/健康剔除/TTL 回收/池上限/溢出/凭据轮换
 * 代际作废/按账户分池。全部走 fake 通道与 fake spawner（纯逻辑，不 CreateProcessWithLogonW）。
 */
class RunnerPoolTest {

    @TempDir
    Path codexHome;

    /** fake 通道：可控 alive/buffered，记录 shutdown/terminate/close。 */
    static final class FakeChannel implements RunnerChannel {
        static final AtomicInteger PIDS = new AtomicInteger();

        final int pid = PIDS.incrementAndGet();
        volatile boolean alive = true;
        volatile int buffered;
        final List<IpcMessage> sent = new ArrayList<>();
        volatile boolean terminated;
        volatile boolean closed;

        @Override
        public void send(IpcMessage message) {
            sent.add(message);
        }

        @Override
        public FrameCodec.FramedMessage readFrame() {
            return null;
        }

        @Override
        public FrameCodec.FramedMessage readFrame(long timeoutMs) {
            return null;
        }

        @Override
        public void terminateRunner() {
            terminated = true;
            alive = false;
        }

        @Override
        public boolean runnerAlive() {
            return alive && !closed;
        }

        @Override
        public int bufferedOutputBytes() {
            return closed ? -1 : buffered;
        }

        @Override
        public void close() {
            closed = true;
        }

        boolean shutdownSent() {
            return sent.stream().anyMatch(m -> m instanceof IpcMessage.Shutdown);
        }
    }

    /** fake spawner：计数 + 可选首 N 次失败注入。 */
    static final class FakeSpawner implements Spawner {
        final AtomicInteger spawns = new AtomicInteger();
        final AtomicInteger failuresLeft = new AtomicInteger();

        @Override
        public RunnerChannel start(RunnerClient.RunnerConfig cfg) throws IOException {
            if (failuresLeft.getAndDecrement() > 0) {
                throw new IOException("injected spawn failure");
            }
            spawns.incrementAndGet();
            return new FakeChannel();
        }
    }

    private FakeSpawner spawner;

    @BeforeEach
    void reset() {
        RunnerPools.resetForTest();
        spawner = new FakeSpawner();
        RunnerPools.spawnerForTest(spawner);
    }

    @AfterEach
    void tearDown() {
        RunnerPools.resetForTest();
    }

    private RunnerClient.RunnerConfig cfg(String username) {
        return new RunnerClient.RunnerConfig(codexHome, username, "pw", "cp", "java.home", "cwd");
    }

    private Settings settings(int maxSize, long idleTtlMs, long acquireTimeoutMs) {
        return new Settings(true, maxSize, idleTtlMs, acquireTimeoutMs, 60_000);
    }

    @Test
    void sequentialAcquireReleaseReusesOneRunner() throws Exception {
        Settings s = settings(2, 600_000, 5_000);
        RunnerLease first = RunnerPools.acquire(cfg("u"), s);
        assertFalse(first.reused(), "首借必为新建");
        FakeChannel ch = (FakeChannel) first.channel();
        first.close();
        RunnerLease second = RunnerPools.acquire(cfg("u"), s);
        assertSame(ch, second.channel(), "空闲 runner 必须原通道复用（一个登录会话多命令）");
        assertTrue(second.reused());
        second.close();
        RunnerLease third = RunnerPools.acquire(cfg("u"), s);
        assertSame(ch, third.channel());
        third.close();
        assertEquals(1, spawner.spawns.get(), "三连命令只产生 1 个 runner（泄漏面=1 个登录会话）");
    }

    @Test
    void concurrentAcquiresStayWithinPoolCap() throws Exception {
        Settings s = settings(2, 600_000, 10_000);
        int tasks = 6;
        ExecutorService pool = Executors.newFixedThreadPool(tasks);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<RunnerLease>> futures = new ArrayList<>();
            for (int i = 0; i < tasks; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    RunnerLease lease = RunnerPools.acquire(cfg("u"), s);
                    Thread.sleep(50); // 模拟命令执行期独占
                    lease.close();
                    return lease;
                }));
            }
            start.countDown();
            for (Future<RunnerLease> f : futures) {
                f.get(30, TimeUnit.SECONDS); // 任务内已 acquire→占→close
            }
            assertTrue(spawner.spawns.get() <= 2,
                    "并发命令的 runner 新建数不得超过池上限: " + spawner.spawns.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void poolFullWithNoReleaseOverflowsAfterTimeout() throws Exception {
        Settings s = settings(1, 600_000, 80);
        RunnerLease first = RunnerPools.acquire(cfg("u"), s); // 占满
        long t0 = System.nanoTime();
        RunnerLease second = RunnerPools.acquire(cfg("u"), s); // 等待 80ms 后溢出新建
        long waitedMs = (System.nanoTime() - t0) / 1_000_000L;
        assertNotSame(first.channel(), second.channel(), "溢出路径必须新建（不共享独占语义）");
        assertFalse(second.reused());
        assertEquals(2, spawner.spawns.get());
        assertTrue(waitedMs >= 70, "溢出前必须先限时等待空闲: " + waitedMs + "ms");
        first.close();
        second.close();
    }

    @Test
    void acquireBlocksUntilReleaseThenReuses() throws Exception {
        Settings s = settings(1, 600_000, 10_000);
        RunnerLease first = RunnerPools.acquire(cfg("u"), s);
        FakeChannel ch = (FakeChannel) first.channel();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<RunnerLease> waiter = pool.submit(() -> RunnerPools.acquire(cfg("u"), s));
            Thread.sleep(200); // 让等待者阻塞在池满
            assertFalse(waiter.isDone(), "池满时 acquire 必须等待而非立刻超建");
            first.close(); // 归还 → 唤醒等待者
            RunnerLease second = waiter.get(10, TimeUnit.SECONDS);
            assertSame(ch, second.channel(), "等待者应接手归还的 runner（复用而非新建）");
            assertTrue(second.reused());
            assertEquals(1, spawner.spawns.get());
            second.close();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void deadIdleRunnerIsDiscardedAndRecreated() throws Exception {
        Settings s = settings(2, 600_000, 5_000);
        RunnerLease first = RunnerPools.acquire(cfg("u"), s);
        FakeChannel ch = (FakeChannel) first.channel();
        first.close(); // 健康归还
        ch.alive = false; // 归还后 runner 进程死亡(崩溃/被杀)
        RunnerLease second = RunnerPools.acquire(cfg("u"), s);
        assertNotSame(ch, second.channel(), "死 runner 必须剔除重建");
        assertTrue(ch.terminated || ch.closed, "剔除路径须收尸(terminate/close)");
        assertEquals(2, spawner.spawns.get());
        second.close();
    }

    @Test
    void bufferedStaleBytesPreventReuse() throws Exception {
        Settings s = settings(2, 600_000, 5_000);
        RunnerLease first = RunnerPools.acquire(cfg("u"), s);
        FakeChannel ch = (FakeChannel) first.channel();
        first.close();
        ch.buffered = 42; // 模拟 -out 管道残留未读帧(上一命令的陈旧输出)
        RunnerLease second = RunnerPools.acquire(cfg("u"), s);
        assertNotSame(ch, second.channel(), "残留字节=陈旧帧错位风险,必须剔除不复用");
        second.close();
    }

    @Test
    void idleTtlEvictionSendsShutdownAndCloses() throws Exception {
        Settings s = settings(2, 100, 5_000);
        RunnerLease lease = RunnerPools.acquire(cfg("u"), s);
        FakeChannel ch = (FakeChannel) lease.channel();
        lease.close();
        Thread.sleep(180); // 越过 TTL
        RunnerPools.evictExpiredForTest(codexHome);
        assertTrue(ch.shutdownSent(), "TTL 回收必须发 shutdown 帧(优雅退出信号)");
        assertTrue(ch.closed, "TTL 回收必须关管道/句柄(登录会话回收)");
        RunnerLease next = RunnerPools.acquire(cfg("u"), s);
        assertNotSame(ch, next.channel(), "已回收 runner 不得再被借出");
        assertEquals(2, spawner.spawns.get());
        next.close();
    }

    @Test
    void evictionBeforeTtlKeepsRunnerIdle() throws Exception {
        Settings s = settings(2, 600_000, 5_000);
        RunnerLease lease = RunnerPools.acquire(cfg("u"), s);
        FakeChannel ch = (FakeChannel) lease.channel();
        lease.close();
        RunnerPools.evictExpiredForTest(codexHome);
        assertFalse(ch.shutdownSent(), "未到 TTL 不得回收");
        RunnerLease next = RunnerPools.acquire(cfg("u"), s);
        assertSame(ch, next.channel(), "TTL 内空闲 runner 应被复用");
        next.close();
    }

    @Test
    void leaseDiscardOnSessionFailureRemovesFromPool() throws Exception {
        Settings s = settings(2, 600_000, 5_000);
        RunnerLease lease = RunnerPools.acquire(cfg("u"), s);
        FakeChannel ch = (FakeChannel) lease.channel();
        lease.discard(); // 会话异常(EOF/帧错):显式作废
        assertTrue(ch.terminated, "作废必须 terminate 收尸");
        RunnerLease next = RunnerPools.acquire(cfg("u"), s);
        assertNotSame(ch, next.channel());
        assertEquals(2, spawner.spawns.get());
        next.close();
    }

    @Test
    void generationInvalidationDiscardsIdleAndBorrowed() throws Exception {
        Settings s = settings(2, 600_000, 5_000);
        RunnerLease borrowed = RunnerPools.acquire(cfg("u"), s); // 借出中
        RunnerLease idleOne = RunnerPools.acquire(cfg("u"), s); // 第二个(池未满,新建)
        FakeChannel borrowedCh = (FakeChannel) borrowed.channel();
        FakeChannel idleCh = (FakeChannel) idleOne.channel();
        idleOne.close(); // 进空闲队列(borrowed 仍借出)
        RunnerPools.invalidateAll(codexHome); // 凭据轮换:全池作废
        assertTrue(idleCh.shutdownSent() || idleCh.closed, "空闲 runner 立即 shutdown");
        borrowed.close(); // 旧代际租约归还
        assertTrue(borrowedCh.terminated || borrowedCh.closed, "借出中的按代际作废,不得回池");
        RunnerLease next = RunnerPools.acquire(cfg("u"), s);
        assertNotSame(idleCh, next.channel());
        assertNotSame(borrowedCh, next.channel());
        assertEquals(3, spawner.spawns.get(), "作废后全部重建");
        next.close();
    }

    @Test
    void offlineAndOnlineAccountsAreSeparatePools() throws Exception {
        Settings s = settings(2, 600_000, 5_000);
        RunnerLease offline = RunnerPools.acquire(cfg("EACodexOffline"), s);
        RunnerLease online = RunnerPools.acquire(cfg("EACodexOnline"), s);
        assertNotSame(offline.channel(), online.channel(),
                "offline/online 是不同登录账户,绝不可跨身份复用 runner");
        assertEquals(2, spawner.spawns.get());
        offline.close();
        online.close();
    }

    @Test
    void spawnFailureIsNotCountedAsLive() throws Exception {
        Settings s = settings(1, 600_000, 5_000);
        spawner.failuresLeft.set(2);
        try {
            RunnerPools.acquire(cfg("u"), s);
            throw new AssertionError("注入的 spawn 失败必须向上抛");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("injected"));
        }
        // 失败不占池计数:重试仍能走新建路径成功
        spawner.failuresLeft.set(0);
        RunnerLease lease = RunnerPools.acquire(cfg("u"), s);
        assertEquals(1, spawner.spawns.get());
        lease.close();
    }
}
