package dev.everyagent.worker.task.lifecycle;

import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.plugin.api.task.TaskOutcome;
import dev.everyagent.worker.ship.StreamSourceRegistry;
import dev.everyagent.worker.task.TaskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 上行节点(order=500)：store.untrack（关闭全部 jsonl writer）。临界段内。
 */
public final class PersistenceUntrackNode extends UpstreamNode {

    private static final Logger log = LoggerFactory.getLogger(PersistenceUntrackNode.class);

    private final TaskStore store;
    private final StreamSourceRegistry streamSources;

    public PersistenceUntrackNode(TaskStore store, StreamSourceRegistry streamSources) {
        this.store = store;
        this.streamSources = streamSources;
    }

    @Override
    public String id() { return "persistence.untrack"; }

    @Override
    public float order() { return 500; }

    @Override
    protected Object up(TaskLifecycleContext ctx, Object result) {
        var impl = (TaskLifecycleContextImpl) ctx;
        var t = impl.taskEntry();
        try {
            store.untrack(t.taskId);
        } catch (RuntimeException e) {
            log.warn("untrack 异常 task={}", t.taskId, e);
        }
        // 摘除流源：StreamSourceRegistry.detach（推送器据此感知任务已驱逐，排水后 detach）
        try {
            streamSources.detach(t.taskId);
        } catch (RuntimeException e) {
            log.warn("流源摘除失败 task={}", t.taskId, e);
        }
        return result;
    }
}
