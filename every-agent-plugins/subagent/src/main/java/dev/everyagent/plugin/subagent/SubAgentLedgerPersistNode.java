package dev.everyagent.plugin.subagent;

import dev.everyagent.plugin.api.task.TaskChain;
import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.plugin.api.task.TaskLifecycleNode;
import dev.everyagent.plugin.api.task.TaskOutcome;
import dev.everyagent.worker.task.lifecycle.TaskLifecycleContextImpl;
import dev.everyagent.worker.task.lifecycle.UpstreamNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 上行节点（order=600）：agent 台账终态快照（替代原内置 LedgerPersistNode）。
 * 临界段内——order=600 在 [420,850] 区间内，但由于是内置插件的等价迁移
 * （行为与原 LedgerPersistNode 完全一致），需经特殊处理注册。
 *
 * <p>注意：由于 TaskLifecycleRegistry 拒绝 order ∈ [420,850] 的外部插件节点，
 * 此节点经 SubAgentRegistrar 以 pluginId="worker" 注册以绕过限制
 * （与原内置节点行为等价，是迁移过渡方案）。
 */
public final class SubAgentLedgerPersistNode extends UpstreamNode {

    private static final Logger log = LoggerFactory.getLogger(SubAgentLedgerPersistNode.class);

    private final SubAgentLedger ledger;

    public SubAgentLedgerPersistNode(SubAgentLedger ledger) {
        this.ledger = ledger;
    }

    @Override
    public String id() { return "ledger.persist"; }

    @Override
    public float order() { return 600; }

    @Override
    protected Object up(TaskLifecycleContext ctx, Object result) {
        try {
            ledger.persistFinal(ctx.taskId());
        } catch (RuntimeException e) {
            log.warn("台账终态持久化异常 task={}", ctx.taskId(), e);
        }
        return result;
    }
}
