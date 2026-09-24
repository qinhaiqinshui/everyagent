package dev.everyagent.worker.task.lifecycle;

import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.plugin.api.task.TaskOutcome;
import dev.everyagent.worker.task.TaskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 上行节点(order=500)：store.untrack（关闭全部 jsonl writer）。临界段内。
 */
public final class PersistenceUntrackNode extends UpstreamNode {

    private static final Logger log = LoggerFactory.getLogger(PersistenceUntrackNode.class);

    private final TaskStore store;

    public PersistenceUntrackNode(TaskStore store) {
        this.store = store;
    }

    @Override
    public String id() { return "persistence.untrack"; }

    @Override
    public float order() { return 500; }

    @Override
    protected TaskOutcome up(TaskLifecycleContext ctx, TaskOutcome result) {
        var t = ((TaskLifecycleContextImpl) ctx).taskEntry();
        try {
            store.untrack(t.taskId);
        } catch (RuntimeException e) {
            log.warn("untrack 异常 task={}", t.taskId, e);
        }
        return result;
    }
}
