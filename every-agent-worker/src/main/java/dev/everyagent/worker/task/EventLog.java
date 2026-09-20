package dev.everyagent.worker.task;

import dev.everyagent.worker.proto.SnowflakeId;
import tools.jackson.databind.JsonNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 单任务事件日志(架构 §5.2 / §13.5):
 * 纯内存 append-only;seq 在 append 时分配(瞬态事件也占号,§3.4)。
 * seq 为 Snowflake ID(long,进程内严格单调递增、不重复),不再连续自增、有空洞;
 * 同一轮 AI 回复的流式 delta/thinking 与定型 message 共享同一 seq(由 TaskEvents 预分配传入)。
 * 任务终态后随 TaskEntry 整体销毁(D8/D18,无修剪无 retention——磁盘 jsonl 是全量真相);
 * 超过 maxEvents 抛 LogOverflowException(RAM 护栏;磁盘不受影响)。
 * <p>计数口径:只对<b>持久</b>(落盘)事件计数。流式瞬态事件(delta/thinking 及
 * {@code ext.persist=false} 的 trace)虽仍进内存缓冲供实时推送,但不占用 maxEvents 护栏——
 * 护栏只保护落盘事件;瞬态风暴由流护栏({@code ModelLengthGuardAdvisor})治理(§13.5)。
 */
public final class EventLog {

    /** 事件日志超过上限(§13.5)。 */
    public static final class LogOverflowException extends RuntimeException {
        public LogOverflowException(String message) {
            super(message);
        }
    }

    public interface Listener {
        void onAppend();
    }

    private final long maxEvents;
    private final ArrayDeque<EventRecord> records = new ArrayDeque<>();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    /** 已追加的持久(落盘)事件数——maxEvents 护栏只按此计数,瞬态事件不计入。 */
    private long persistentSize = 0;

    /** 已分配的最大 seq 水位(seed 可预置,append 只增不减)。 */
    private long lastSeq = 0;

    public EventLog(long maxEvents) {
        this.maxEvents = maxEvents;
    }

    /**
     * 预置 seq 基线(终态任务再运行前调用):空日志以磁盘 lastSeq 起步,
     * 新事件从磁盘末尾之后续号。只增不减,须在 append/track 之前调用。
     */
    public synchronized void seed(long seq) {
        if (seq > lastSeq) {
            lastSeq = seq;
        }
    }

    /** 自动分配 Snowflake ID 追加(各事件类型的独立 seq;进程内严格单调递增)。 */
    public EventRecord append(String event, JsonNode payload, String agentId, JsonNode ext) {
        return append(event, payload, agentId, ext, false);
    }

    /**
     * 带瞬态标记的自动分配 seq 追加:{@code transientEvent=true} 的事件(流式
     * delta/thinking、瞬态 trace 等)只进内存缓冲供实时推送,<b>不占用</b>
     * {@code maxEvents} 护栏计数——护栏只保护落盘(持久)事件,瞬态风暴由
     * {@code ModelLengthGuardAdvisor} 等流护栏治理。仍占 seq(磁盘 seq 有洞合法)。
     */
    public EventRecord append(String event, JsonNode payload, String agentId, JsonNode ext,
            boolean transientEvent) {
        EventRecord record;
        synchronized (this) {
            if (!transientEvent && persistentSize >= maxEvents) {
                throw new LogOverflowException("事件数已达上限 " + maxEvents);
            }
            long seq = SnowflakeId.next();
            if (seq <= lastSeq) {
                // 防御:跨进程时钟回拨导致雪花 ID 小于已分配水位(含 seed)时抬升,
                // 保证独立事件续号;正常路径不触发(雪花 ID 同进程单调递增 + 时间戳向前)。
                seq = lastSeq + 1;
            }
            lastSeq = seq;
            record = new EventRecord(seq, System.currentTimeMillis(), event, agentId, payload, ext);
            records.addLast(record);
            if (!transientEvent) {
                persistentSize++;
            }
        }
        for (Listener l : listeners) {
            l.onAppend(); // 仅信号,微秒级,不阻塞任务线程
        }
        return record;
    }

    /**
     * 以调用方预分配 seq 追加:同一轮流式 delta/thinking 与定型 message 共享同一 seq 时,
     * 由 TaskEvents 按 agentId 维护轮内 seq 传入。显式 seq <b>不抬升、不强制单调</b>:
     * 同轮多帧共用同一 seq(等价),跨 agent 并发交错时该 seq 可能小于并发事件水位,
     * 抬升会破坏共享不变量。水位仍只增不减(取 max),供 readRange/lastSeq 使用。
     */
    public EventRecord append(long seq, String event, JsonNode payload, String agentId, JsonNode ext) {
        return append(seq, event, payload, agentId, ext, false);
    }

    /**
     * 带瞬态标记的显式 seq 追加:同轮 delta/thinking/message 共享同一 seq 时,由 TaskEvents
     * 按 agentId 维护轮内 seq 传入;瞬态标记语义与 {@link #append(String, JsonNode, String,
     * JsonNode, boolean)} 相同——不占 maxEvents 护栏计数。显式 seq <b>不抬升、不强制单调</b>:
     * 同轮多帧共用同一 seq(等价),跨 agent 并发交错时该 seq 可能小于并发事件水位,
     * 抬升会破坏共享不变量。水位仍只增不减(取 max),供 readRange/lastSeq 使用。
     */
    public EventRecord append(long seq, String event, JsonNode payload, String agentId, JsonNode ext,
            boolean transientEvent) {
        EventRecord record;
        synchronized (this) {
            if (!transientEvent && persistentSize >= maxEvents) {
                throw new LogOverflowException("事件数已达上限 " + maxEvents);
            }
            if (seq > lastSeq) {
                lastSeq = seq;
            }
            record = new EventRecord(seq, System.currentTimeMillis(), event, agentId, payload, ext);
            records.addLast(record);
            if (!transientEvent) {
                persistentSize++;
            }
        }
        for (Listener l : listeners) {
            l.onAppend(); // 仅信号,微秒级,不阻塞任务线程
        }
        return record;
    }

    /**
     * 读取 seq &gt; afterSeq 的记录(按追加顺序,≥ max 条)。共享 seq 轮组<b>不拆批</b>:
     * 批量边界切在轮组中间时,把该组剩余同 seq 记录一并吞下,否则读者 cursor 落到组中
     * 会让同组后续帧(如 message,seq 与 delta 相同)被下一次 {@code seq > cursor} 过滤掉。
     * 返回条数可略超 max(整组吞下),调用方以之为批大小软上限。
     */
    public synchronized List<EventRecord> readRange(long afterSeq, int max) {
        List<EventRecord> out = new ArrayList<>();
        if (records.isEmpty() || afterSeq >= lastSeq) {
            return out;
        }
        boolean absorb = false;
        long groupSeq = 0;
        for (EventRecord r : records) {
            if (r.seq() <= afterSeq) {
                continue;
            }
            if (absorb) {
                if (r.seq() == groupSeq) {
                    out.add(r);
                    continue;
                }
                break; // 已出组,余下交给下一批
            }
            out.add(r);
            if (out.size() >= max) {
                absorb = true;
                groupSeq = r.seq();
            }
        }
        return out;
    }

    /**
     * 只读尾部增量:seq &gt; afterSeq 的记录(追加顺序,最多 limit 条;供 task.poll 内存侧
     * 与磁盘窗口按 seq 归并)。同轮共享 seq 的组<b>不拆批</b>:批尾落在组中间时整组吞下,
     * 复用 readRange 的 absorb 纪律。只读、不消费、不删除,记录留在缓冲供前端凭 seq 去重。
     */
    public synchronized List<EventRecord> readAfterSeq(long afterSeq, int limit) {
        List<EventRecord> out = new ArrayList<>();
        if (records.isEmpty() || afterSeq >= lastSeq || limit <= 0) {
            return out;
        }
        boolean absorb = false;
        long groupSeq = 0;
        for (EventRecord r : records) {
            if (r.seq() <= afterSeq) {
                continue;
            }
            if (absorb) {
                if (r.seq() == groupSeq) {
                    out.add(r);
                    continue;
                }
                break; // 已出组,余下交给下一批
            }
            out.add(r);
            if (out.size() >= limit) {
                absorb = true;
                groupSeq = r.seq();
            }
        }
        return out;
    }

    /**
     * 只读尾部:最后 count 条记录(含瞬态;追加顺序)。最多返回现有记录数;只读、不消费、不删除。
     */
    public synchronized List<EventRecord> readLastRecords(int count) {
        List<EventRecord> out = new ArrayList<>();
        if (records.isEmpty() || count <= 0) {
            return out;
        }
        int skip = records.size() - count;
        if (skip < 0) {
            skip = 0;
        }
        int idx = 0;
        for (EventRecord r : records) {
            if (idx++ < skip) {
                continue;
            }
            out.add(r);
        }
        return out;
    }

    /**
     * 按追加位置读取(0-based,返回 [from, from+max) 条):内部读者(Shipper/TaskStore 落盘
     * 游标)用记录数而非 seq 作游标——共享 seq 轮组多帧同 seq,seq 游标无法区分
     * 「组内已消费到第几帧」,按位置则天然无歧义、不丢组尾帧。
     */
    public synchronized List<EventRecord> readFrom(int from, int max) {
        List<EventRecord> out = new ArrayList<>();
        if (from >= records.size() || max <= 0) {
            return out;
        }
        int idx = 0;
        for (EventRecord r : records) {
            if (idx++ < from) {
                continue;
            }
            out.add(r);
            if (out.size() >= max) {
                break;
            }
        }
        return out;
    }

    public synchronized long lastSeq() {
        return lastSeq;
    }

    /** 首条记录 seq(空日志 = 下一条将分配的 seq,即 lastSeq+1)。 */
    public synchronized long firstSeq() {
        EventRecord first = records.peekFirst();
        return first == null ? lastSeq + 1 : first.seq();
    }

    public synchronized int size() {
        return records.size();
    }

    /** 持久(落盘)事件数——maxEvents 护栏按此计数(瞬态事件不计入)。 */
    public synchronized long persistentSize() {
        return persistentSize;
    }

    /**
     * 截断:移除所有 seq &gt; targetSeq 的记录,并将 lastSeq 回退到 targetSeq。
     * 用于编辑重发热路径——磁盘已由 TaskStore.truncateAfterSeq 截断,内存日志同步截断,
     * 防止 task.poll 从内存尾部返回已截断的旧事件。
     * persistentSize 不递减(保守策略:EventRecord 无 transient 标记,无法精确区分
     * 持久/瞬态;偏紧的护栏只会让极端长会话提早触顶,不会导致内存溢出)。
     */
    public synchronized void truncateAfter(long targetSeq) {
        records.removeIf(r -> r.seq() > targetSeq);
        if (lastSeq > targetSeq) {
            lastSeq = targetSeq;
        }
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }
}
