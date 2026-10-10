package dev.everyagent.plugin.api.event;

import java.util.List;

/**
 * 事件日志只读接口(供插件读取任务事件流)。
 *
 * <p>worker 的 {@code EventLog} 实现此接口,插件(subagent 台账等)只依赖此接口,
 * 不直接依赖 worker 的 EventLog 具体类。
 */
public interface EventLogReader {

    /**
     * 按追加位置读取(0-based,返回 [from, from+max) 条)。
     *
     * @param from 起始位置(0-based)
     * @param max  最多返回条数
     * @return 事件记录列表(按追加顺序)
     */
    List<EventRecord> readFrom(int from, int max);

    /**
     * 只读尾部增量:seq &gt; afterSeq 的记录(追加顺序,最多 limit 条)。
     *
     * @param afterSeq 返回 seq 大于此值的事件
     * @param max      最多返回条数
     * @return 事件记录列表(按追加顺序)
     */
    List<EventRecord> readAfterSeq(long afterSeq, int max);

    /** 当前内存记录数(含瞬态;0-based 位置游标上界)。 */
    int size();

    /** 注册追加监听器(新事件追加时回调,仅信号不阻塞)。 */
    void addListener(Listener listener);

    /** 移除追加监听器。 */
    void removeListener(Listener listener);

    /** 事件追加监听器(仅信号 onAppend,微秒级,不阻塞任务线程)。 */
    @FunctionalInterface
    interface Listener {
        void onAppend();
    }
}
