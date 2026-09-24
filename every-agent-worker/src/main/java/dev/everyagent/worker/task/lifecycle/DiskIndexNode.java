package dev.everyagent.worker.task.lifecycle;

import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.plugin.api.task.TaskOutcome;
import dev.everyagent.worker.task.TaskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 上行节点(order=550)：diskTasks.put（终态任务转磁盘索引）。临界段内。
 */
public final class DiskIndexNode extends UpstreamNode {

    private static final Logger log = LoggerFactory.getLogger(DiskIndexNode.class);

    private final TaskStore store;

    public DiskIndexNode(TaskStore store) {
        this.store = store;
    }

    @Override
    public String id() { return "disk.index"; }

    @Override
    public float order() { return 550; }

    @Override
    protected TaskOutcome up(TaskLifecycleContext ctx, TaskOutcome result) {
        var t = ((TaskLifecycleContextImpl) ctx).taskEntry();
        try {
            ((TaskLifecycleContextImpl) ctx).diskIndexer().accept(new TaskStore.StoredTask(
                    t.taskId, store.dirOf(t.taskId), t.summaryJson(), t.workspaceId));
        } catch (RuntimeException e) {
            log.warn("diskTasks.put 异常 task={}", t.taskId, e);
        }
        return result;
    }
}
