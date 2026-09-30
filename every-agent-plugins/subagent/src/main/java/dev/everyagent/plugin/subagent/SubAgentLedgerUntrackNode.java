package dev.everyagent.plugin.subagent;

import dev.everyagent.plugin.api.task.TaskChain;
import dev.everyagent.plugin.api.event.EventLogReader;
import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.plugin.api.task.TaskLifecycleNode;
import dev.everyagent.plugin.api.task.TaskOutcome;
import dev.everyagent.worker.task.EventLog;
import dev.everyagent.worker.task.lifecycle.TaskLifecycleContextImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 上行节点（order=500）：任务 untrack 时移除 EventLogReader.Listener，清理内存台账。
 * 在 persistence.untrack(500) 同序——实际在 registry.remove(420) 之后执行。
 * order=350 确保在 workspace.activity(350) 之前、registry.remove(420) 之后。
 */
public final class SubAgentLedgerUntrackNode implements TaskLifecycleNode {

    private static final Logger log = LoggerFactory.getLogger(SubAgentLedgerUntrackNode.class);

    private final SubAgentLedger ledger;

    public SubAgentLedgerUntrackNode(SubAgentLedger ledger) {
        this.ledger = ledger;
    }

    @Override
    public String id() { return "subagent.ledger.untrack"; }

    @Override
    public float order() { return 340; }

    @Override
    public Object invoke(TaskLifecycleContext ctx, TaskChain next) throws Exception {
        Object result = next.proceed(ctx);
        try {
            var impl = (TaskLifecycleContextImpl) ctx;
            var t = impl.taskEntryImpl();
            ledger.onUntrack(t.taskId, t.log);
        } catch (Exception e) {
            log.warn("subagent 台账 untrack 失败 task={}", ctx.taskId(), e);
        }
        return result;
    }
}
