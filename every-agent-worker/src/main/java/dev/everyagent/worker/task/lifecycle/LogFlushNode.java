package dev.everyagent.worker.task.lifecycle;

import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.plugin.api.task.TaskOutcome;
import dev.everyagent.worker.task.TaskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 上行节点(order=750)：store.flush（等落盘追平，30s 超时放行）。临界段内。
 */
public final class LogFlushNode extends UpstreamNode {

    private static final Logger log = LoggerFactory.getLogger(LogFlushNode.class);

    private final TaskStore store;

    public LogFlushNode(TaskStore store) {
        this.store = store;
    }

    @Override
    public String id() { return "log.flush"; }

    @Override
    public float order() { return 750; }

    @Override
    protected Object up(TaskLifecycleContext ctx, Object result) {
        var t = ((TaskLifecycleContextImpl) ctx).taskEntry();
        try {
            store.flush(t.taskId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.warn("flush 异常 task={}", t.taskId, e);
        }
        return result;
    }
}
