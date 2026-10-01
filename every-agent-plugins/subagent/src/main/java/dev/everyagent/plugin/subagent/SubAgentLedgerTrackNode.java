package dev.everyagent.plugin.subagent;

import dev.everyagent.plugin.api.task.TaskChain;
import dev.everyagent.plugin.api.event.EventLogReader;
import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.plugin.api.task.TaskLifecycleNode;
import dev.everyagent.plugin.api.task.TaskRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 下行节点（order=150）：任务 track 时注册 EventLogReader.Listener 到 SubAgentLedger。
 * 在 persistence.track(100) 之后、task.wires(200) 之前执行。
 */
public final class SubAgentLedgerTrackNode implements TaskLifecycleNode {

    private static final Logger log = LoggerFactory.getLogger(SubAgentLedgerTrackNode.class);

    private final SubAgentLedger ledger;

    public SubAgentLedgerTrackNode(SubAgentLedger ledger) {
        this.ledger = ledger;
    }

    @Override
    public String id() { return "subagent.ledger.track"; }

    @Override
    public float order() { return 150; }

    @Override
    public Object invoke(TaskLifecycleContext ctx, TaskChain next) throws Exception {
        TaskRuntime t = ctx.taskRuntime();
        if (t != null) {
            try {
                // 核心取数面中性化(§8.2 壳/核分离):subjectId/dataDir 走 ExecContext 槽位
                EventLogReader eventLog = t.log();
                // 读取 meta 用于冷启动恢复（null 安全：新任务无 meta）
                ledger.onTrack(t.subjectId(), t.dataDir(), eventLog, null);
            } catch (Exception e) {
                log.warn("subagent 台账 track 失败 task={}", ctx.taskId(), e);
            }
        }
        return next.proceed(ctx);
    }
}
