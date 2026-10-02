package dev.everyagent.worker.task.lifecycle;

import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.plugin.api.task.TaskRuntime;
import dev.everyagent.worker.agent.AgentLedger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 上行节点（order=340）：任务 untrack 时移除 EventLogReader.Listener，清理内存台账。
 * order=340 确保在 workspace.activity(350) 之前、registry.remove(420) 之后。
 * <p>收编自 subagent 插件 SubAgentLedgerUntrackNode。
 * AgentLedger.onUntrack 签名只收 subjectId（不需要 EventLogReader）。
 */
public final class AgentLedgerUntrackNode extends UpstreamNode {

    private static final Logger log = LoggerFactory.getLogger(AgentLedgerUntrackNode.class);

    private final AgentLedger ledger;

    public AgentLedgerUntrackNode(AgentLedger ledger) {
        this.ledger = ledger;
    }

    @Override
    public String id() { return "agent.ledger.untrack"; }

    @Override
    public float order() { return 340; }

    @Override
    protected Object up(TaskLifecycleContext ctx, Object result) {
        try {
            TaskRuntime t = ctx.taskRuntime();
            if (t == null) {
                return result; // 任务运行时不存在(早期失败路径):无台账可 untrack
            }
            ledger.onUntrack(t.subjectId());
        } catch (Exception e) {
            log.warn("agent 台账 untrack 失败 task={}", ctx.taskId(), e);
        }
        return result;
    }
}
