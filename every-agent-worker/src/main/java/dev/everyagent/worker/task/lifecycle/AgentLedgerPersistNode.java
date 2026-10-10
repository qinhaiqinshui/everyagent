package dev.everyagent.worker.task.lifecycle;

import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.plugin.api.task.TaskRuntime;
import dev.everyagent.worker.agent.AgentLedger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 上行节点（order=860）：agent 台账终态快照。
 * 在 StatusNode(840) 之后、CascadeStopNode(900) 之前执行上行段。
 * <p>收编自 subagent 插件 SubAgentLedgerPersistNode。
 */
public final class AgentLedgerPersistNode extends UpstreamNode {

    private static final Logger log = LoggerFactory.getLogger(AgentLedgerPersistNode.class);

    private final AgentLedger ledger;

    public AgentLedgerPersistNode(AgentLedger ledger) {
        this.ledger = ledger;
    }

    @Override
    public String id() { return "agent.ledger.persist"; }

    @Override
    public float order() { return 860; }

    @Override
    protected Object up(TaskLifecycleContext ctx, Object result) {
        try {
            TaskRuntime t = ctx.taskRuntime();
            if (t != null) {
                ledger.persistFinal(t.subjectId());
            }
        } catch (RuntimeException e) {
            log.warn("agent 台账终态持久化异常 task={}", ctx.taskId(), e);
        }
        return result;
    }
}
