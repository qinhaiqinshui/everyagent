package dev.everyagent.worker.task.lifecycle;

import dev.everyagent.plugin.api.model.EmitEvent;
import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.plugin.api.task.TaskOutcome;
import dev.everyagent.worker.hub.EventSink;
import dev.everyagent.plugin.api.event.Channels;
import dev.everyagent.plugin.api.event.Events;
import dev.everyagent.plugin.api.proto.SnowflakeId;
import dev.everyagent.worker.proto.TaskDtos.TaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 成对节点(order=840)：任务状态（一事）。
 * <p>下行段：startedAt + setStatus(RUNNING) + task.updated 广播 + agentStatus("running")
 * ——在 main.agent(390) 之后、内核之前执行（临界段节点下行段在段边界外运行）。
 * <p>上行段（临界段首环，共享临界区）：终态 CAS（幂等门）+ endedAt/error/status +
 * agentStatus 终态事件 + 终态广播。
 */
public final class StatusNode extends SectionNode {

    private static final Logger log = LoggerFactory.getLogger(StatusNode.class);

    private final EventSink eventSink;

    public StatusNode(EventSink eventSink) {
        this.eventSink = eventSink;
    }

    @Override
    public String id() { return "status"; }

    @Override
    public float order() { return 840; }

    @Override
    protected void down(TaskLifecycleContext ctx) {
        var t = ((TaskLifecycleContextImpl) ctx).taskEntry();
        ctx.startedAt(System.currentTimeMillis());
        synchronized (t) {
            t.status = TaskStatus.RUNNING;
        }
        eventSink.fanout(k -> Channels.tasks(k), Events.TASK_UPDATED, null, t.runtimeSummaryJson(), null);
        t.events.emit(EmitEvent.of(SnowflakeId.next(), "agent.status", t.mainAgentId,
                null, null, null, "running", null, EmitEvent.Mode.REPLACE));
    }

    @Override
    protected Object up(TaskLifecycleContext ctx, Object result) {
        var t = ((TaskLifecycleContextImpl) ctx).taskEntry();
        // 终态 CAS（幂等门）
        if (t.status.terminal()) {
            log.debug("[finalize] 已是终态,跳过 taskId={} thread={}", t.taskId, Thread.currentThread().getName());
            return result;
        }
        TaskOutcome to = (TaskOutcome) result;
        log.debug("[finalize] 进入终态收口 taskId={} status={} thread={}", t.taskId, to.status(), Thread.currentThread().getName());
        t.endedAt = System.currentTimeMillis();
        t.error = to.error();
        t.status = mapStatus(to.status());
        try {
            t.events.emit(EmitEvent.of(SnowflakeId.next(), "agent.status", t.mainAgentId,
                    null, null, null, agentStatusOf(t.status), null, EmitEvent.Mode.REPLACE));
        } catch (RuntimeException e) {
            log.debug("终态事件写入失败(日志可能已满)", e);
        }
        try {
            eventSink.fanout(k -> Channels.tasks(k), Events.TASK_UPDATED, null, t.runtimeSummaryJson(), null);
        } catch (RuntimeException e) {
            log.debug("终态广播失败 task={}", t.taskId, e);
        }
        return result;
    }

    private static TaskStatus mapStatus(TaskOutcome.TaskEndStatus s) {
        return switch (s) {
            case DONE -> TaskStatus.DONE;
            case FAILED -> TaskStatus.FAILED;
            case CANCELLED -> TaskStatus.CANCELLED;
        };
    }

    private static String agentStatusOf(TaskStatus s) {
        return switch (s) {
            case DONE -> "done";
            case FAILED -> "failed";
            case CANCELLED -> "stopped";
            default -> "running";
        };
    }
}
