package dev.everyagent.plugin.subagent;

import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.plugin.api.task.TaskOutcome;
import dev.everyagent.worker.task.SubAgentManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 上行节点(order=950)：awaitAllBeforeFinish（等全部子 agent，超时级联停）。
 * 中断检测：await 后若线程被中断，改写 result 为 CANCELLED。
 */
public final class SubAgentSpawnedAwaitNode extends UpstreamNode {

    private static final Logger log = LoggerFactory.getLogger(SubAgentSpawnedAwaitNode.class);

    private final SubAgentManager subs;

    public SubAgentSpawnedAwaitNode(SubAgentManager subs) {
        this.subs = subs;
    }

    @Override
    public String id() { return "spawned.await"; }

    @Override
    public float order() { return 950; }

    @Override
    protected Object up(TaskLifecycleContext ctx, Object result) {
        var t = ((TaskLifecycleContextImpl) ctx).taskEntry();
        try {
            subs.awaitAllBeforeFinish(t);
        } catch (RuntimeException e) {
            log.warn("awaitAllBeforeFinish 异常 task={}", t.taskId, e);
        }
        if (Thread.currentThread().isInterrupted()) {
            TaskOutcome to = (TaskOutcome) result;
            return new TaskOutcome(
                TaskOutcome.TaskEndStatus.CANCELLED, null,
                to.startedAt(), System.currentTimeMillis());
        }
        return result;
    }
}
