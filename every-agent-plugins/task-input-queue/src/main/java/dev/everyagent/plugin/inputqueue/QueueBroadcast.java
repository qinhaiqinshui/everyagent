package dev.everyagent.plugin.inputqueue;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.event.Channels;
import dev.everyagent.plugin.api.event.Events;
import dev.everyagent.plugin.api.event.StreamEmitter;
import dev.everyagent.plugin.api.task.TaskRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 队列状态广播（task.updated 携带 pendingInputs 快照）——queue.dispatch（入队）、
 * queue.loop（轮间消费）、task.queueRemove / task.queueMove（增删改）后的统一出口。
 * <p>前端队列面板自持数据源（task.queueSnapshot RPC 拉取），以本广播为刷新信号：
 * 队列专有数据不进核心前端类型（ComposerPanelCtx / TaskListEntry 均不携带 pendingInputs）。
 */
final class QueueBroadcast {

    private static final Logger log = LoggerFactory.getLogger(QueueBroadcast.class);

    private QueueBroadcast() {
    }

    /**
     * 广播运行中任务的队列快照。
     * @param t 内存 TaskRuntime（summary 同形 meta.json，touch 更新活跃时间）
     * @param queue 队列（null 视为空队列）
     */
    static void pendingInputs(StreamEmitter eventSink, TaskRuntime t, InputQueue queue) {
        try {
            t.touch();
            ObjectNode summary = t.summaryJson();
            summary.set("pendingInputs", toArray(queue == null ? null : queue.snapshot()));
            // 广播到本 worker 的 tasks 频道(带 worker 段),并标明归属 worker(架构 §5.2);
            // 扇出入口自带 workerId,插件无需知道自身身份以外的信息。
            summary.put("workerId", eventSink.workerId());
            eventSink.fanout(k -> Channels.tasks(k, eventSink.workerId()), Events.TASK_UPDATED, null,
                    summary, null);
        } catch (Exception e) {
            log.warn("[queue] 广播队列更新失败 task={}", t.taskId(), e);
        }
    }

    /** 字符串列表 → ArrayNode（null/空均产出空数组）。 */
    static ArrayNode toArray(java.util.List<String> items) {
        ArrayNode arr = Json.arr();
        if (items != null) {
            for (String text : items) {
                arr.add(text);
            }
        }
        return arr;
    }
}
