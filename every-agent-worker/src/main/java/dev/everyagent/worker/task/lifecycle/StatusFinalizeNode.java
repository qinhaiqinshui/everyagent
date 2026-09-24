package dev.everyagent.worker.task.lifecycle;

import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.plugin.api.task.TaskOutcome;
import dev.everyagent.worker.hub.EventSink;
import dev.everyagent.worker.proto.Channels;
import dev.everyagent.worker.proto.Events;
import dev.everyagent.worker.proto.TaskDtos.TaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 上行节点(order=850)：终态 CAS（幂等门）+ endedAt/error/status + agentStatus 终态事件 + 终态广播。
 * 临界段起点：850..420 连续 UpstreamNode 段共享一次 synchronized。
 */
public final class StatusFinalizeNode extends UpstreamNode {

    private static final Logger log = LoggerFactory.getLogger(StatusFinalizeNode.class);

    private final EventSink eventSink;

    public StatusFinalizeNode(EventSink eventSink) {
        this.eventSink = eventSink;
    }

    @Override
    public String id() { return "status.finalize"; }

    @Override
    public float order() { return 850; }

    @Override
    protected TaskOutcome up(TaskLifecycleContext ctx, TaskOutcome result) {
        var t = ((TaskLifecycleContextImpl) ctx).taskEntry();
        // 终态 CAS（幂等门）
        if (t.status.terminal()) {
            log.debug("[finalize] 已是终态,跳过 taskId={} thread={}", t.taskId, Thread.currentThread().getName());
            return result;
        }
        log.debug("[finalize] 进入终态收口 taskId={} status={} thread={}", t.taskId, result.status(), Thread.currentThread().getName());
        t.endedAt = System.currentTimeMillis();
        t.error = result.error();
        t.status = mapStatus(result.status());
        try {
            t.events.agentStatus(t.mainAgentId, agentStatusOf(t.status));
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
