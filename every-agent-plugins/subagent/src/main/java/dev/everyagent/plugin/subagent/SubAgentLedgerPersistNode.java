package dev.everyagent.plugin.subagent;

import dev.everyagent.plugin.api.task.TaskChain;
import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.plugin.api.task.TaskLifecycleNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 上行节点（order=860）：agent 台账终态快照（替代原内置 LedgerPersistNode）。
 * 原为 order=600（临界段内），迁移为独立插件节点后移至 860（临界段外），
 * 在 StatusNode(840) 之后、CascadeStopNode(900) 之前执行上行段。
 */
public final class SubAgentLedgerPersistNode implements TaskLifecycleNode {

    private static final Logger log = LoggerFactory.getLogger(SubAgentLedgerPersistNode.class);

    private final SubAgentLedger ledger;

    public SubAgentLedgerPersistNode(SubAgentLedger ledger) {
        this.ledger = ledger;
    }

    @Override
    public String id() { return "ledger.persist"; }

    @Override
    public float order() { return 860; }

    @Override
    public Object invoke(TaskLifecycleContext ctx, TaskChain next) throws Exception {
        Object result = next.proceed(ctx);
        try {
            ledger.persistFinal(ctx.taskId());
        } catch (RuntimeException e) {
            log.warn("台账终态持久化异常 task={}", ctx.taskId(), e);
        }
        return result;
    }
}
