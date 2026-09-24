package dev.everyagent.worker.task.lifecycle;

import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.plugin.api.task.TaskOutcome;
import dev.everyagent.worker.task.TaskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 上行节点(order=600)：store.writeAgents（agent 台账终态快照）。临界段内。
 * Phase 4 改由 subagent 插件贡献。
 */
public final class LedgerPersistNode extends UpstreamNode {

    private static final Logger log = LoggerFactory.getLogger(LedgerPersistNode.class);

    private final TaskStore store;

    public LedgerPersistNode(TaskStore store) {
        this.store = store;
    }

    @Override
    public String id() { return "ledger.persist"; }

    @Override
    public float order() { return 600; }

    @Override
    protected TaskOutcome up(TaskLifecycleContext ctx, TaskOutcome result) {
        var t = ((TaskLifecycleContextImpl) ctx).taskEntry();
        try {
            store.writeAgents(t.taskId, t.agentLedger.values());
        } catch (RuntimeException e) {
            log.warn("writeAgents 异常 task={}", t.taskId, e);
        }
        return result;
    }
}
