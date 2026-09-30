package dev.everyagent.worker.task.lifecycle;

import dev.everyagent.plugin.api.task.TaskChain;
import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.plugin.api.task.TaskLifecycleNode;
import dev.everyagent.plugin.api.task.TaskOutcome;
import dev.everyagent.worker.hub.EventSink;
import dev.everyagent.plugin.api.event.Channels;
import dev.everyagent.plugin.api.event.Events;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 下行节点(order=200)：注入 onUsageBroadcast 钩子。
 */
public final class TaskWiresNode implements TaskLifecycleNode {

    private static final Logger log = LoggerFactory.getLogger(TaskWiresNode.class);

    private final EventSink eventSink;

    public TaskWiresNode(EventSink eventSink) {
        this.eventSink = eventSink;
    }

    @Override
    public String id() { return "task.wires"; }

    @Override
    public float order() { return 200; }

    @Override
    public Object invoke(TaskLifecycleContext ctx, TaskChain next) throws Exception {
        var t = ((TaskLifecycleContextImpl) ctx).taskEntryImpl();
        // wireUsageBroadcast
        ctx.onUsageBroadcast(() -> {
            if (t.status.terminal()) return;
            try {
                eventSink.fanout(k -> Channels.tasks(k), Events.TASK_UPDATED, null, t.runtimeSummaryJson(), null);
            } catch (RuntimeException e) {
                log.debug("任务用量广播失败 task={}", t.taskId, e);
            }
        });
        return next.proceed(ctx);
    }
}
