package dev.everyagent.plugin.sandbox.codex.session;

import dev.everyagent.plugin.sandbox.codex.runner.IpcMessage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 常驻 runner 池（进程级注册表，按 {@code codexHome + username} 键——username 必须进键：
 * offline/online 是两个沙箱账户，runner 的受限令牌从<b>自身登录账户</b>派生，网络身份
 * 随账户落定，跨账户复用会把 offline 命令跑进 online 会话）。
 *
 * <p>解决的问题：旧形态每条命令经 seclogon 新建一个 runner JVM（即一个登录会话），
 * runner 从不注销 → 登录会话一天泄漏 1700+。池化后泄漏面 = 每键池大小上限（有界），
 * 空闲 TTL 到期发 {@code shutdown} 帧让 runner 退出、登录会话随进程引用归零被系统回收。
 *
 * <p>语义：
 * <ul>
 *   <li><b>每命令独占</b>：同一 runner 同时只服务一条命令（acquire 借出即离池，
 *       release 才回空闲队列；runner 侧命令队列容量 1，双重保险）；</li>
 *   <li><b>复用前健康探测</b>：runner 进程存活 + {@code -out} 管道连通且零残留字节
 *       （残留 = 上一条命令的陈旧帧，复用会错位握手，按不健康剔除重建）；</li>
 *   <li><b>失败剔除</b>：会话异常（EOF/管道断/帧错）的 runner 由调用方
 *       {@link RunnerLease#discard()} 终止并出池，下一条命令按现流程新建；</li>
 *   <li><b>池大小上限</b>：live 数（空闲+借出）达上限时 acquire 限时等待空闲，
 *       超时仍无则溢出新建并打 WARN——登录会话有界是目标，但不以命令失败为代价；</li>
 *   <li><b>空闲 TTL 回收</b>：后台清扫线程定期把空闲超过 TTL 的 runner 发 shutdown
 *       关会话；acquire 时也做机会式回收（不依赖后台线程的兜底路径，测试可独立驱动）；</li>
 *   <li><b>单飞去重</b>：runner 物化经 {@link RunnerMaterializer} 进程内缓存 + per-key
 *       互斥（见其 ensureRunnerClasspath），并发首 acquire 不会重复物化/spawn 超界。</li>
 * </ul>
 *
 * <p>关闭开关：{@link Settings#enabled()}=false 时调用方直接走每命令 spawn 旧路径，
 * 本注册表不参与（见 CodexCommandExecutor 的会话 opener 组装）。
 */
public final class RunnerPools {

    /** 池化行为配置（来自 CodexSandboxOptions；每键池在首次创建时定格）。 */
    public record Settings(boolean enabled, int maxSize, long idleTtlMs,
            long acquireTimeoutMs, long sweepIntervalMs) {

        /** 空闲 TTL 默认 10 分钟（无命令则 shutdown 关会话）。 */
        public static final long DEFAULT_IDLE_TTL_MS = 10 * 60 * 1000;
        /** 池大小上限默认 2（每 worker 并发命令通常 1-2；多任务并行由溢出兜底）。 */
        public static final int DEFAULT_MAX_SIZE = 2;
        /** 池满等待空闲的默认上限。 */
        public static final long DEFAULT_ACQUIRE_TIMEOUT_MS = 60_000;
        /** 后台清扫默认节奏（≈TTL/4）。 */
        public static final long DEFAULT_SWEEP_INTERVAL_MS = 150_000;

        public Settings {
            maxSize = Math.max(1, maxSize);
            idleTtlMs = Math.max(1, idleTtlMs);
            acquireTimeoutMs = Math.max(1, acquireTimeoutMs);
            sweepIntervalMs = Math.max(100, sweepIntervalMs);
        }

        public static Settings defaults() {
            return new Settings(true, DEFAULT_MAX_SIZE, DEFAULT_IDLE_TTL_MS,
                    DEFAULT_ACQUIRE_TIMEOUT_MS, DEFAULT_SWEEP_INTERVAL_MS);
        }
    }

    /** runner 拉起 seam（生产 = RunnerClient.start；测试注入 fake）。 */
    @FunctionalInterface
    public interface Spawner {
        RunnerChannel start(RunnerClient.RunnerConfig cfg) throws IOException;
    }

    /** 一次命令对 runner 的独占租约：close()=归还（健康才回池），discard()=作废出池。 */
    public interface RunnerLease extends AutoCloseable {
        /** 被租借的通道（复用与新建共用同一形态）。 */
        RunnerChannel channel();

        /** 本租约是否复自空闲池（false = 本次新建）。 */
        boolean reused();

        /** 归还：通道健康则回空闲队列等 TTL 回收，不健康直接作废（绝不把坏 runner 留给下一条命令）。 */
        @Override
        void close();

        /** 作废：terminate + 关管道（runner 退出、登录会话回收），并出池。 */
        void discard();
    }

    private static final System.Logger LOG = System.getLogger(RunnerPools.class.getName());

    /** codexHome|username → 池。进程级：executor 随工具创建而新建，池必须跨 executor 存活。 */
    private static final ConcurrentHashMap<String, RunnerPool> POOLS = new ConcurrentHashMap<>();
    /** 生产 spawner（CreateProcessWithLogonW 全链路）。 */
    private static volatile Spawner spawner = RunnerClient::start;
    /** 后台清扫线程（首个池创建时惰性启动；守护虚拟线程，不阻 JVM 退出）。 */
    private static volatile Thread sweeper;

    private RunnerPools() {
    }

    /** 获取（或新建）目标键的 runner 独占租约。 */
    public static RunnerLease acquire(RunnerClient.RunnerConfig cfg, Settings settings)
            throws IOException {
        String key = poolKey(cfg.codexHome(), cfg.username());
        RunnerPool pool = POOLS.computeIfAbsent(key, k -> {
            ensureSweeper(settings.sweepIntervalMs());
            LOG.log(System.Logger.Level.INFO,
                    "[runner-pool] 池已创建 key={0} max={1} idleTtl={2}ms",
                    new Object[] { k, settings.maxSize(), settings.idleTtlMs() });
            return new RunnerPool(k, settings, spawner);
        });
        return pool.acquire(cfg);
    }

    /**
     * 作废某 codexHome 下全部池的 runner（凭据失配自愈链路：重 setup 轮换密码/重建账户后，
     * 池中既有 runner 全部作废——它们与旧凭据/旧账户代际绑定，宁可重建不冒错配风险）。
     * 空闲 runner 立即 shutdown 关会话；借出中的在归还时按代际校验作废。
     */
    public static void invalidateAll(Path codexHome) {
        if (codexHome == null) {
            return;
        }
        String prefix = poolKeyPrefix(codexHome);
        for (Map.Entry<String, RunnerPool> e : POOLS.entrySet()) {
            if (e.getKey().startsWith(prefix)) {
                e.getValue().invalidate("credential-rotation");
            }
        }
    }

    /** 测试隔离：清空注册表并停清扫线程（借出中的租约归还到已作废的池=直接作废）。 */
    public static void resetForTest() {
        Thread s = sweeper;
        sweeper = null;
        if (s != null) {
            s.interrupt();
        }
        List<RunnerPool> pools = new ArrayList<>(POOLS.values());
        POOLS.clear();
        for (RunnerPool p : pools) {
            p.invalidate("test-reset");
        }
        spawner = RunnerClient::start;
    }

    /** 测试注入 fake spawner（须在 acquire 前调用）。 */
    static void spawnerForTest(Spawner fake) {
        spawner = fake;
    }

    /** 测试驱动：立即对某 codexHome 下全部池执行一轮 TTL 清扫（不等后台节奏）。 */
    static void evictExpiredForTest(Path codexHome) {
        String prefix = poolKeyPrefix(codexHome);
        for (Map.Entry<String, RunnerPool> e : POOLS.entrySet()) {
            if (e.getKey().startsWith(prefix)) {
                e.getValue().evictExpired();
            }
        }
    }

    static String poolKey(Path codexHome, String username) {
        return poolKeyPrefix(codexHome) + username;
    }

    private static String poolKeyPrefix(Path codexHome) {
        return codexHome.toAbsolutePath().normalize() + "|";
    }

    private static synchronized void ensureSweeper(long intervalMs) {
        if (sweeper != null && sweeper.isAlive()) {
            return;
        }
        sweeper = Thread.ofVirtual().name("codex-runner-pool-sweeper").start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(intervalMs);
                } catch (InterruptedException e) {
                    return;
                }
                for (RunnerPool p : POOLS.values()) {
                    try {
                        p.evictExpired();
                    } catch (RuntimeException e) {
                        LOG.log(System.Logger.Level.WARNING,
                                "[runner-pool] TTL 清扫异常 key={0}: {1}",
                                new Object[] { p.key(), e.toString() });
                    }
                }
            }
        });
    }

    // ---- 单键池 ----

    static final class RunnerPool {
        private final String key;
        private final Settings settings;
        private final Spawner poolSpawner;
        private final Object lock = new Object();
        private final ArrayDeque<IdleRunner> idle = new ArrayDeque<>();
        /** live = 空闲 + 借出（池大小上限的计数口径）。 */
        private int live;
        /** 代际：invalidate() 自增——旧代际租约归还时一律作废。 */
        private int generation;
        // 打点计数（复用/新建/回收/作废/溢出）
        private final AtomicLong reused = new AtomicLong();
        private final AtomicLong created = new AtomicLong();
        private final AtomicLong evicted = new AtomicLong();
        private final AtomicLong discarded = new AtomicLong();
        private final AtomicLong overflowed = new AtomicLong();

        RunnerPool(String key, Settings settings, Spawner poolSpawner) {
            this.key = key;
            this.settings = settings;
            this.poolSpawner = poolSpawner;
        }

        String key() {
            return key;
        }

        RunnerLease acquire(RunnerClient.RunnerConfig cfg) throws IOException {
            long deadline = System.nanoTime() + settings.acquireTimeoutMs() * 1_000_000L;
            while (true) {
                boolean overflow = false;
                boolean create = false;
                synchronized (lock) {
                    evictExpiredLocked(); // 机会式回收（无后台线程也保证 TTL 语义）
                    // 1) 复用空闲 runner：健康探测通过才出借
                    while (!idle.isEmpty()) {
                        IdleRunner cand = idle.pollFirst();
                        if (healthy(cand.channel())) {
                            reused.incrementAndGet();
                            logPool("复用空闲 runner pid=" + cand.pid
                                    + " (复用=" + reused.get() + " 新建=" + created.get()
                                    + " 回收=" + evicted.get() + " 作废=" + discarded.get() + ")");
                            return new LeaseImpl(cand.channel(), generation, true);
                        }
                        discardLocked(cand.channel(), "复用前健康探测未过");
                    }
                    // 2) 池未满 → 新建（spawn 在锁外执行，不阻塞其他 acquire 的健康探测）
                    if (live < settings.maxSize()) {
                        live++;
                        create = true;
                    } else {
                        long remainMs = (deadline - System.nanoTime()) / 1_000_000L;
                        if (remainMs <= 0) {
                            // 3) 池满且等不到空闲：溢出新建（WARN）——命令可用性优先
                            live++;
                            overflow = true;
                            create = true;
                        } else {
                            try {
                                lock.wait(Math.min(remainMs, 1000));
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new IOException("interrupted while acquiring runner", e);
                            }
                            continue;
                        }
                    }
                }
                // 锁外新建：CreateProcessWithLogonW 耗秒级，绝不能握着池锁
                if (overflow) {
                    overflowed.incrementAndGet();
                    LOG.log(System.Logger.Level.WARNING,
                            "[runner-pool] 池满溢出新建 key={0} (live>{1}, 溢出={2})"
                                    + " —— 并发命令数超过池上限,登录会话临时超界",
                            new Object[] { key, settings.maxSize(), overflowed.get() });
                }
                RunnerChannel channel;
                try {
                    channel = poolSpawner.start(cfg);
                } catch (IOException | RuntimeException e) {
                    synchronized (lock) {
                        live--;
                        lock.notifyAll();
                    }
                    throw e;
                }
                created.incrementAndGet();
                logPool("新建 runner pid=" + pidOf(channel) + " (live=" + liveSnapshot()
                        + "/" + settings.maxSize() + " 新建=" + created.get() + ")");
                return new LeaseImpl(channel, generation, false);
            }
        }

        /** 空闲 TTL 过期即 shutdown 关会话（登录会话回收的主动路径）。 */
        void evictExpired() {
            synchronized (lock) {
                evictExpiredLocked();
            }
        }

        private void evictExpiredLocked() {
            long now = System.currentTimeMillis();
            Iterator<IdleRunner> it = idle.iterator();
            while (it.hasNext()) {
                IdleRunner r = it.next();
                if (now - r.idleSince >= settings.idleTtlMs()) {
                    it.remove();
                    live--;
                    evicted.incrementAndGet();
                    shutdownQuietly(r.channel());
                    LOG.log(System.Logger.Level.INFO,
                            "[runner-pool] 空闲 TTL 到期回收 runner key={0} pid={1}"
                                    + " (回收={2} 作废={3})",
                            new Object[] { key, r.pid, evicted.get(), discarded.get() });
                }
            }
        }

        /** 全池作废：空闲立即 shutdown；借出中的按代际在归还时作废。 */
        void invalidate(String reason) {
            synchronized (lock) {
                generation++;
                while (!idle.isEmpty()) {
                    IdleRunner r = idle.pollFirst();
                    live--;
                    discarded.incrementAndGet();
                    shutdownQuietly(r.channel());
                }
                LOG.log(System.Logger.Level.INFO,
                        "[runner-pool] 全池作废 key={0} 原因={1} (作废={2})",
                        new Object[] { key, reason, discarded.get() });
                lock.notifyAll();
            }
        }

        /** 健康探测：进程存活 + -out 管道连通且零残留字节。 */
        private boolean healthy(RunnerChannel channel) {
            try {
                return channel.runnerAlive() && channel.bufferedOutputBytes() == 0;
            } catch (RuntimeException e) {
                return false;
            }
        }

        /** 须持 lock 调用：终止并出池（fail-closed，绝不把坏 runner 留在计数里）。 */
        private void discardLocked(RunnerChannel channel, String reason) {
            live--;
            discarded.incrementAndGet();
            try {
                channel.terminateRunner();
            } catch (RuntimeException ignored) {
                // 尽力收尸
            }
            channel.close();
            LOG.log(System.Logger.Level.INFO,
                    "[runner-pool] 剔除不健康 runner key={0} 原因={1} (作废={2})",
                    new Object[] { key, reason, discarded.get() });
        }

        /** 优雅关停：先发 shutdown 帧（显式信号），关管道（EOF 兜底，两者任一生效）。 */
        private void shutdownQuietly(RunnerChannel channel) {
            try {
                channel.send(new IpcMessage.Shutdown());
            } catch (RuntimeException ignored) {
                // 管道已断:runner 已死或即将死,关句柄即可
            }
            try {
                channel.close();
            } catch (RuntimeException ignored) {
                // 尽力清理
            }
        }

        private void logPool(String msg) {
            LOG.log(System.Logger.Level.INFO, "[runner-pool] {0} key={1}",
                    new Object[] { msg, key });
        }

        private int liveSnapshot() {
            synchronized (lock) {
                return live;
            }
        }

        private static int pidOf(RunnerChannel channel) {
            return channel instanceof RunnerClient.Channel ch ? ch.runnerPid() : -1;
        }

        private record IdleRunner(RunnerChannel channel, long idleSince, int pid) {
        }

        private final class LeaseImpl implements RunnerLease {
            private final RunnerChannel channel;
            private final int leaseGeneration;
            private final boolean reusedLease;
            private boolean settled;

            LeaseImpl(RunnerChannel channel, int leaseGeneration, boolean reusedLease) {
                this.channel = channel;
                this.leaseGeneration = leaseGeneration;
                this.reusedLease = reusedLease;
            }

            @Override
            public RunnerChannel channel() {
                return channel;
            }

            @Override
            public boolean reused() {
                return reusedLease;
            }

            @Override
            public void close() {
                synchronized (lock) {
                    if (settled) {
                        return;
                    }
                    settled = true;
                    if (leaseGeneration != generation) {
                        // 凭据轮换代际之后归还:一律作废(重建由下一次 acquire 按新凭据做)
                        discardLocked(channel, "代际失效(凭据轮换后归还)");
                    } else if (healthy(channel)) {
                        idle.addLast(new IdleRunner(channel, System.currentTimeMillis(),
                                pidOf(channel)));
                        logPool("runner 归还空闲队列 pid=" + pidOf(channel)
                                + " (空闲=" + idle.size() + ")");
                    } else {
                        discardLocked(channel, "归还时健康探测未过");
                    }
                    lock.notifyAll(); // 唤醒池满等待者
                }
            }

            @Override
            public void discard() {
                synchronized (lock) {
                    if (settled) {
                        return;
                    }
                    settled = true;
                    discardLocked(channel, "会话异常(调用方显式作废)");
                    lock.notifyAll();
                }
            }
        }
    }
}
