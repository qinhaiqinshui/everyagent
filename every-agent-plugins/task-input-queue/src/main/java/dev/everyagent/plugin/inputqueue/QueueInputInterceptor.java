package dev.everyagent.plugin.inputqueue;

import dev.everyagent.plugin.api.task.TaskInputInterceptor;
import dev.everyagent.worker.hub.EventSink;
import dev.everyagent.worker.proto.Channels;
import dev.everyagent.worker.proto.Events;
import dev.everyagent.worker.task.TaskEntry;
import dev.everyagent.worker.task.TaskManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 队列输入拦截器：拦截运行中 task.input（offer 到队列）和 task.dialogInsert（移出队列 + 加入插入队列）。
 * <p>队列变化时广播 task.updated 携带 pendingInputs 快照（前端镜像实时）。
 */
public class QueueInputInterceptor implements TaskInputInterceptor {

    private static final Logger log = LoggerFactory.getLogger(QueueInputInterceptor.class);

    private final TaskQueueRegistry registry;
    private final TaskManager taskManager;
    private final EventSink eventSink;

    public QueueInputInterceptor(TaskQueueRegistry registry, TaskManager taskManager, EventSink eventSink) {
        this.registry = registry;
        this.taskManager = taskManager;
        this.eventSink = eventSink;
    }

    @Override
    public boolean onRunningTaskInput(String taskId, String text, String rawContent) {
        InputQueue queue = registry.getInputQueue(taskId);
        if (queue == null) {
            return false; // 任务不在队列插件的管辖范围（不应发生）
        }
        queue.offer(text, rawContent);
        broadcastQueueUpdate(taskId, queue);
        return true;
    }

    @Override
    public void onDialogInsert(String taskId, int index, String text) {
        InputQueue queue = registry.getInputQueue(taskId);
        if (queue == null) {
            return;
        }
        // 从输入队列移除（插入即消费）
        dev.everyagent.worker.task.UserInput removed = null;
        if (index >= 0) {
            try {
                removed = queue.removeAt(index);
            } catch (IndexOutOfBoundsException e) {
                log.warn("task.dialogInsert 队列下标越界 task={} index={}", taskId, index);
            }
        }
        if (removed == null) {
            removed = queue.removeFirst(text);
        }
        // 加入插入对话队列（DialogInsertAdvisor 在工具循环下行 drain）
        registry.getOrCreateDialogInsertQueue(taskId)
                .offer(removed != null ? removed : dev.everyagent.worker.task.UserInput.of(text));
        broadcastQueueUpdate(taskId, queue);
    }

    /** 广播 task.updated 携带 pendingInputs 快照。 */
    private void broadcastQueueUpdate(String taskId, InputQueue queue) {
        try {
            TaskEntry t = taskManager.get(taskId);
            if (t == null) return;
            t.touch();
            // 广播 summary + pendingInputs（插件拼装）
            var summary = t.runtimeSummaryJson();
            var arr = tools.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
            for (String text : queue.snapshot()) {
                arr.add(text);
            }
            summary.set("pendingInputs", arr);
            eventSink.fanout(k -> Channels.tasks(k), Events.TASK_UPDATED, null, summary, null);
        } catch (RuntimeException e) {
            log.debug("队列广播失败 task={}", taskId, e);
        }
    }
}
