package dev.everyagent.worker.task.lifecycle;

import dev.everyagent.plugin.api.task.TaskChain;
import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.plugin.api.task.TaskLifecycleNode;
import dev.everyagent.plugin.api.task.TaskOutcome;
import dev.everyagent.worker.hub.HubPool;
import dev.everyagent.worker.proto.Events;
import dev.everyagent.worker.task.TaskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 下行节点(order=200)：注入 onUsageBroadcast / persistHook 钩子。
 */
public final class TaskWiresNode implements TaskLifecycleNode {

    private static final Logger log = LoggerFactory.getLogger(TaskWiresNode.class);

    private final TaskStore store;
    private final HubPool pool;

    public TaskWiresNode(TaskStore store, HubPool pool) {
        this.store = store;
        this.pool = pool;
    }

    @Override
    public String id() { return "task.wires"; }

    @Override
    public float order() { return 200; }

    @Override
    public TaskOutcome invoke(TaskLifecycleContext ctx, TaskChain next) throws Exception {
        var t = ((TaskLifecycleContextImpl) ctx).taskEntry();
        // wireUsageBroadcast
        ctx.onUsageBroadcast(() -> {
            if (t.status.terminal()) return;
            try {
                pool.pubAllTasks(Events.TASK_UPDATED, null, t.runtimeSummaryJson(), null);
            } catch (RuntimeException e) {
                log.debug("任务用量广播失败 task={}", t.taskId, e);
            }
        });
        // wireAgentPersist
        ctx.onPersistHook(() -> {
            if (t.status.terminal()) return;
            store.writeAgents(t.taskId, t.agentLedger.values());
        });
        return next.proceed(ctx);
    }
}
