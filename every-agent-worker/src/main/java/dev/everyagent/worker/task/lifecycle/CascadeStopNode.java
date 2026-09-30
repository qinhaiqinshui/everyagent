package dev.everyagent.worker.task.lifecycle;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.model.EmitEvent;
import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.plugin.api.task.TaskOutcome;
import dev.everyagent.plugin.api.proto.SnowflakeId;
import dev.everyagent.worker.interaction.InteractionServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * 上行节点(order=900)：result≠DONE 时按 status 分支级联停。
 * CANCELLED → stopRequested + stopAll + asks.cancelTask("user") + events.cancelled
 * FAILED → stopAll + asks.cancelTask("worker") + events.error（LOG_OVERFLOW 不发 error 逐字保留）
 */
public final class CascadeStopNode extends UpstreamNode {

    private static final Logger log = LoggerFactory.getLogger(CascadeStopNode.class);

    private final InteractionServiceImpl asks;

    public CascadeStopNode(InteractionServiceImpl asks) {
        this.asks = asks;
    }

    @Override
    public String id() { return "cascade.stop"; }

    @Override
    public float order() { return 900; }

    @Override
    protected Object up(TaskLifecycleContext ctx, Object result) {
        var t = ((TaskLifecycleContextImpl) ctx).taskEntry();
        TaskOutcome to = (TaskOutcome) result;
        if (to.status() == TaskOutcome.TaskEndStatus.DONE) {
            return result;
        }
        if (to.status() == TaskOutcome.TaskEndStatus.CANCELLED) {
            try { asks.cancelTask(t.taskId, "user"); } catch (RuntimeException e) { log.warn("cancelTask 异常 task={}", t.taskId, e); }
            try {
                ObjectNode cancelledData = Json.obj().put("by", "user");
                t.events.emit(EmitEvent.of(SnowflakeId.next(), "cancelled", t.mainAgentId,
                        null, null, null, null, cancelledData, EmitEvent.Mode.REPLACE));
            } catch (RuntimeException e) { log.debug("终态事件写入失败(日志可能已满)", e); }
        } else { // FAILED
            try { asks.cancelTask(t.taskId, "worker"); } catch (RuntimeException e) { log.warn("cancelTask 异常 task={}", t.taskId, e); }
            // LOG_OVERFLOW 不发 error 事件（现状该分支即无）
            String err = to.error();
            if (err == null || !err.startsWith("LOG_OVERFLOW")) {
                String msg = err != null ? err : "unknown error";
                try {
                    t.events.emit(EmitEvent.of(SnowflakeId.next(), "error", t.mainAgentId,
                            null, null, msg, null, null, EmitEvent.Mode.REPLACE));
                } catch (RuntimeException e) { log.debug("终态事件写入失败(日志可能已满)", e); }
            }
        }
        return result;
    }
}
