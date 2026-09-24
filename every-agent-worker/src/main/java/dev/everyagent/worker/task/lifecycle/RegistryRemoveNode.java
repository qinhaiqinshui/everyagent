package dev.everyagent.worker.task.lifecycle;

import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.plugin.api.task.TaskOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 上行节点(order=420)：tasks.remove（两参原子，再运行认据此判输赢）。
 * 临界段终点：850..420 连续 UpstreamNode 段在此结束。
 */
public final class RegistryRemoveNode extends UpstreamNode {

    private static final Logger log = LoggerFactory.getLogger(RegistryRemoveNode.class);

    @Override
    public String id() { return "registry.remove"; }

    @Override
    public float order() { return 420; }

    @Override
    protected TaskOutcome up(TaskLifecycleContext ctx, TaskOutcome result) {
        try {
            ((TaskLifecycleContextImpl) ctx).registryRemover().run();
        } catch (RuntimeException e) {
            log.warn("tasks.remove 异常 task={}", ctx.taskId(), e);
        }
        return result;
    }
}
