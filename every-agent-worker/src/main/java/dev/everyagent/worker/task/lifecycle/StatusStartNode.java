package dev.everyagent.worker.task.lifecycle;

import dev.everyagent.plugin.api.task.TaskChain;
import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.plugin.api.task.TaskLifecycleNode;
import dev.everyagent.plugin.api.task.TaskOutcome;
import dev.everyagent.worker.hub.HubPool;
import dev.everyagent.worker.proto.Events;
import dev.everyagent.worker.proto.TaskDtos.TaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 下行节点(order=300)：startedAt + setStatus(RUNNING) + task.updated 广播 + agentStatus("running")。
 */
public final class StatusStartNode implements TaskLifecycleNode {

    private static final Logger log = LoggerFactory.getLogger(StatusStartNode.class);

    private final HubPool pool;

    public StatusStartNode(HubPool pool) {
        this.pool = pool;
    }

    @Override
    public String id() { return "status.start"; }

    @Override
    public float order() { return 300; }

    @Override
    public TaskOutcome invoke(TaskLifecycleContext ctx, TaskChain next) throws Exception {
        var t = ((TaskLifecycleContextImpl) ctx).taskEntry();
        ctx.startedAt(System.currentTimeMillis());
        synchronized (t) {
            t.status = TaskStatus.RUNNING;
        }
        pool.pubAllTasks(Events.TASK_UPDATED, null, t.runtimeSummaryJson(), null);
        t.events.agentStatus(t.mainAgentId, "running");
        return next.proceed(ctx);
    }
}
