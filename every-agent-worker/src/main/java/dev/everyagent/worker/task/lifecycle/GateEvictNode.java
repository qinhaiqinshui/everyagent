package dev.everyagent.worker.task.lifecycle;

import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.plugin.api.task.TaskOutcome;
import dev.everyagent.worker.tools.PermissionGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 上行节点(order=450)：gate.untrack（授权内存驱逐）。临界段内。
 */
public final class GateEvictNode extends UpstreamNode {

    private static final Logger log = LoggerFactory.getLogger(GateEvictNode.class);

    private final PermissionGate gate;

    public GateEvictNode(PermissionGate gate) {
        this.gate = gate;
    }

    @Override
    public String id() { return "gate.evict"; }

    @Override
    public float order() { return 450; }

    @Override
    protected TaskOutcome up(TaskLifecycleContext ctx, TaskOutcome result) {
        try {
            gate.untrack(ctx.taskId());
        } catch (RuntimeException e) {
            log.warn("gate.untrack 异常 task={}", ctx.taskId(), e);
        }
        return result;
    }
}
