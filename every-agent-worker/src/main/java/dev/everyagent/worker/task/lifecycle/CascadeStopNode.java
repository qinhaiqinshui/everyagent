package dev.everyagent.worker.task.lifecycle;

import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.plugin.api.task.TaskOutcome;
import dev.everyagent.worker.task.PendingAsks;
import dev.everyagent.worker.task.SubAgentManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 上行节点(order=900)：result≠DONE 时按 status 分支级联停。
 * CANCELLED → stopRequested + stopAll + asks.cancelTask("user") + events.cancelled
 * FAILED → stopAll + asks.cancelTask("worker") + events.error（LOG_OVERFLOW 不发 error 逐字保留）
 */
public final class CascadeStopNode extends UpstreamNode {

    private static final Logger log = LoggerFactory.getLogger(CascadeStopNode.class);

    private final SubAgentManager subs;
    private final PendingAsks asks;

    public CascadeStopNode(SubAgentManager subs, PendingAsks asks) {
        this.subs = subs;
        this.asks = asks;
    }

    @Override
    public String id() { return "cascade.stop"; }

    @Override
    public float order() { return 900; }

    @Override
    protected TaskOutcome up(TaskLifecycleContext ctx, TaskOutcome result) {
        var t = ((TaskLifecycleContextImpl) ctx).taskEntry();
        if (result.status() == TaskOutcome.TaskEndStatus.DONE) {
            return result;
        }
        if (result.status() == TaskOutcome.TaskEndStatus.CANCELLED) {
            t.stopRequested = true;
            try { subs.stopAll(t); } catch (RuntimeException e) { log.warn("stopAll 异常 task={}", t.taskId, e); }
            try { asks.cancelTask(t.taskId, "user"); } catch (RuntimeException e) { log.warn("cancelTask 异常 task={}", t.taskId, e); }
            try { t.events.cancelled("user"); } catch (RuntimeException e) { log.debug("终态事件写入失败(日志可能已满)", e); }
        } else { // FAILED
            try { subs.stopAll(t); } catch (RuntimeException e) { log.warn("stopAll 异常 task={}", t.taskId, e); }
            try { asks.cancelTask(t.taskId, "worker"); } catch (RuntimeException e) { log.warn("cancelTask 异常 task={}", t.taskId, e); }
            // LOG_OVERFLOW 不发 error 事件（现状该分支即无）
            String err = result.error();
            if (err == null || !err.startsWith("LOG_OVERFLOW")) {
                String msg = err != null ? err : "unknown error";
                try { t.events.error(t.mainAgentId, msg); } catch (RuntimeException e) { log.debug("终态事件写入失败(日志可能已满)", e); }
            }
        }
        return result;
    }
}
