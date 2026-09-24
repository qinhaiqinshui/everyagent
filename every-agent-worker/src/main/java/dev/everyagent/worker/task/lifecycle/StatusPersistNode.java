package dev.everyagent.worker.task.lifecycle;

import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.plugin.api.task.TaskOutcome;
import dev.everyagent.worker.task.TaskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 上行节点(order=650)：store.updateMeta（状态收口写磁盘）。临界段内。
 */
public final class StatusPersistNode extends UpstreamNode {

    private static final Logger log = LoggerFactory.getLogger(StatusPersistNode.class);

    private final TaskStore store;

    public StatusPersistNode(TaskStore store) {
        this.store = store;
    }

    @Override
    public String id() { return "status.persist"; }

    @Override
    public float order() { return 650; }

    @Override
    protected TaskOutcome up(TaskLifecycleContext ctx, TaskOutcome result) {
        var t = ((TaskLifecycleContextImpl) ctx).taskEntry();
        try {
            store.updateMeta(t.taskId);
        } catch (RuntimeException e) {
            log.warn("updateMeta 异常 task={}", t.taskId, e);
        }
        return result;
    }
}
