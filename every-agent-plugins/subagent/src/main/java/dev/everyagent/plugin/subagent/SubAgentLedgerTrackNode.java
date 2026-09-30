package dev.everyagent.plugin.subagent;

import dev.everyagent.plugin.api.task.TaskChain;
import dev.everyagent.plugin.api.event.EventLogReader;
import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.plugin.api.task.TaskLifecycleNode;
import dev.everyagent.plugin.api.task.TaskOutcome;
import dev.everyagent.worker.task.EventLog;
import dev.everyagent.worker.task.TaskStore;
import dev.everyagent.worker.task.lifecycle.TaskLifecycleContextImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

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
        try {
            var impl = (TaskLifecycleContextImpl) ctx;
            var t = impl.taskEntryImpl();
            Path dir = t.log != null ? null : null; // 目录由 TaskStore 管理
            // 从 TaskStore 获取目录——通过 store.readMeta 读 meta（已在 track 节点完成）
            // 实际：track 已在 PersistenceTrackNode 完成，这里只需注册 listener
            EventLog eventLog = t.log;
            // 读取 meta 用于冷启动恢复（null 安全：新任务无 meta）
            ledger.onTrack(t.taskId, eventLog, null, null);
        } catch (Exception e) {
            log.warn("subagent 台账 track 失败 task={}", ctx.taskId(), e);
        }
        return next.proceed(ctx);
    }
}
