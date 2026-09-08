package dev.everyagent.worker.proto;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 64 位 long 型 Snowflake ID 生成器(用于替代自增整数 seq)。
 *
 * <p>位布局(高位在前):
 * <pre>
 *   1 位符号位(恒 0) | 41 位毫秒时间戳 | 10 位机位(workerId 派生) | 12 位序列号
 * </pre>
 *
 * <p>特性:
 * <ul>
 *   <li>线程安全:static synchronized 串行化临界区,虚拟线程并发调用下 ID 严格单调递增、不重复;</li>
 *   <li>序列号溢出:同毫秒内 12 位序列(0..4095)耗尽时自旋等待下一毫秒再产出;</li>
 *   <li>时钟回拨:小回拨(≤ {@link #CLOCK_BACKWARD_TOLERANCE_MS})自旋等待时钟追平,
 *       大回拨抛 {@link IllegalStateException},任何情况下不产生回退/重复 ID;</li>
 *   <li>机位:启动时经 {@link #setWorkerId(String)} 注入(取 hashCode 低 10 位),
 *       未注入时用进程内随机值兜底(同进程内恒定,跨进程随机去相关)。</li>
 * </ul>
 */
public final class SnowflakeId {

    /** 自定义纪元:2024-01-01T00:00:00Z。41 位毫秒可覆盖约 69 年。 */
    private static final long EPOCH = 1704067200000L;

    private static final long SEQUENCE_BITS = 12L;
    private static final long WORKER_ID_BITS = 10L;

    private static final long SEQUENCE_MASK = (1L << SEQUENCE_BITS) - 1L;       // 4095
    private static final long WORKER_ID_MASK = (1L << WORKER_ID_BITS) - 1L;     // 1023

    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;                  // 12
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS; // 22

    /** 时钟小回拨容忍上限(ms):超过直接抛异常,不做无限等待。 */
    private static final long CLOCK_BACKWARD_TOLERANCE_MS = 5_000L;
    /** 回拨/跨毫秒等待的硬上限(ms):超过抛异常,防止进程无限挂起。 */
    private static final long CLOCK_WAIT_HARD_LIMIT_MS = 5_000L;

    /** 10 位机位;-1 表示尚未初始化。 */
    private static volatile long workerId = -1L;
    /** 最近一次产出的毫秒时间戳(原始值,含纪元偏移)。 */
    private static long lastTimestamp = -1L;
    /** 当前毫秒内的序列号(0..4095)。 */
    private static long sequence = 0L;

    private SnowflakeId() {
    }

    /**
     * 注入机位标识(任意非空字符串),取其 hashCode 低 10 位作为机位。
     * 可重复调用,后调覆盖生效;调用后重置时间/序列状态(不影响并发正确性,
     * 与 {@link #next()} 同锁互斥)。
     */
    public static synchronized void setWorkerId(String workerId) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId must be non-blank");
        }
        SnowflakeId.workerId = workerId.hashCode() & WORKER_ID_MASK;
        lastTimestamp = -1L;
        sequence = 0L;
    }

    /** 当前生效的 10 位机位(未注入时首次调用会生成随机兜底值)。 */
    public static long workerId() {
        return ensureWorkerId();
    }

    /**
     * 生成下一个 64 位 Snowflake ID。
     *
     * @return 严格单调递增、进程内不重复的 long ID(恒为正数)
     * @throws IllegalStateException 时钟回拨超过容忍上限或等待超时时抛出
     */
    public static long next() {
        long w = ensureWorkerId();
        synchronized (SnowflakeId.class) {
            long ts = currentTimeMillis();
            long last = lastTimestamp;

            if (ts < last) {
                long backward = last - ts;
                if (backward > CLOCK_BACKWARD_TOLERANCE_MS) {
                    throw new IllegalStateException(
                            "clock moved backwards by " + backward + "ms (> tolerance "
                                    + CLOCK_BACKWARD_TOLERANCE_MS + "ms)");
                }
                // 小回拨:等待时钟追平,期间不产出任何 ID
                spinUntil(last);
                ts = last;
            }

            if (ts == last) {
                long nextSeq = (sequence + 1) & SEQUENCE_MASK;
                if (nextSeq == 0) {
                    // 同毫秒序列号耗尽:自旋等待下一毫秒
                    ts = spinUntilNextMillis(last);
                    sequence = 0L;
                } else {
                    sequence = nextSeq;
                }
            } else {
                // 进入新的一毫秒,序列号复位
                sequence = 0L;
            }
            lastTimestamp = ts;
            return build(ts, w, sequence);
        }
    }

    /** 从 ID 中还原毫秒时间戳(UTC,含纪元偏移),可用于诊断/排序。 */
    public static long timestampOf(long id) {
        return (id >>> TIMESTAMP_SHIFT) + EPOCH;
    }

    /** 从 ID 中还原 10 位机位。 */
    public static long workerIdOf(long id) {
        return (id >>> WORKER_ID_SHIFT) & WORKER_ID_MASK;
    }

    /** 从 ID 中还原 12 位序列号。 */
    public static long sequenceOf(long id) {
        return id & SEQUENCE_MASK;
    }

    private static long build(long ts, long w, long seq) {
        return ((ts - EPOCH) << TIMESTAMP_SHIFT) | (w << WORKER_ID_SHIFT) | seq;
    }

    /** 自旋等待直到当前毫秒 >= target;超过硬上限抛异常。 */
    private static void spinUntil(long target) {
        long deadline = System.nanoTime() + CLOCK_WAIT_HARD_LIMIT_MS * 1_000_000L;
        while (currentTimeMillis() < target) {
            Thread.onSpinWait();
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException(
                        "clock did not catch up within " + CLOCK_WAIT_HARD_LIMIT_MS + "ms");
            }
        }
    }

    /** 返回严格大于 last 的当前毫秒值(同毫秒序列耗尽时的跨毫秒等待)。 */
    private static long spinUntilNextMillis(long last) {
        long deadline = System.nanoTime() + CLOCK_WAIT_HARD_LIMIT_MS * 1_000_000L;
        long ts;
        do {
            Thread.onSpinWait();
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException(
                        "no millisecond advance within " + CLOCK_WAIT_HARD_LIMIT_MS + "ms");
            }
            ts = currentTimeMillis();
        } while (ts <= last);
        return ts;
    }

    private static long currentTimeMillis() {
        return System.currentTimeMillis();
    }

    /** 双检锁初始化机位:已注入用之,否则进程内随机兜底。 */
    private static long ensureWorkerId() {
        long w = workerId;
        if (w < 0) {
            synchronized (SnowflakeId.class) {
                w = workerId;
                if (w < 0) {
                    w = ThreadLocalRandom.current().nextLong() & WORKER_ID_MASK;
                    workerId = w;
                }
            }
        }
        return w;
    }
}
