package dev.everyagent.worker.task.lifecycle;

import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.plugin.api.task.TaskOutcome;
import dev.everyagent.worker.modules.WorkspaceActivityTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 上行节点(order=350)：activityTracker.onTaskFinished。临界段外（锁外）。
 */
public final class WorkspaceActivityNode extends UpstreamNode {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceActivityNode.class);

    private final WorkspaceActivityTracker activityTracker;

    public WorkspaceActivityNode(WorkspaceActivityTracker activityTracker) {
        this.activityTracker = activityTracker;
    }

    @Override
    public String id() { return "workspace.activity"; }

    @Override
    public float order() { return 350; }

    @Override
    protected Object up(TaskLifecycleContext ctx, Object result) {
        var t = ((TaskLifecycleContextImpl) ctx).taskEntryImpl();
        try {
            activityTracker.onTaskFinished(t.workspaceRoot);
        } catch (RuntimeException e) {
            log.warn("onTaskFinished 异常 task={}", t.taskId, e);
        }
        return result;
    }
}
