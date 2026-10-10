package dev.everyagent.worker.task.lifecycle;

import dev.everyagent.plugin.api.event.EventLogReader;
import dev.everyagent.plugin.api.task.TaskChain;
import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.plugin.api.task.TaskLifecycleNode;
import dev.everyagent.plugin.api.task.TaskRuntime;
import dev.everyagent.worker.agent.AgentLedger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 下行节点（order=150）：任务 track 时注册 EventLogReader.Listener 到 AgentLedger。
 * 在 persistence.track(100) 之后、task.wires(200) 之前执行。
 * <p>收编自 subagent 插件 SubAgentLedgerTrackNode。
 */
public final class AgentLedgerTrackNode implements TaskLifecycleNode {

    private static final Logger log = LoggerFactory.getLogger(AgentLedgerTrackNode.class);

    private final AgentLedger ledger;

    public AgentLedgerTrackNode(AgentLedger ledger) {
        this.ledger = ledger;
    }

    @Override
    public String id() { return "agent.ledger.track"; }

    @Override
    public float order() { return 150; }

    @Override
    public Object invoke(TaskLifecycleContext ctx, TaskChain next) throws Exception {
        TaskRuntime t = ctx.taskRuntime();
        if (t != null) {
            try {
                EventLogReader eventLog = t.log();
                ledger.onTrack(t.subjectId(), t.dataDir(), eventLog, null);
            } catch (Exception e) {
                log.warn("agent 台账 track 失败 task={}", ctx.taskId(), e);
            }
        }
        return next.proceed(ctx);
    }
}
