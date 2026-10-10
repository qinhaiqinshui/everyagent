package dev.everyagent.worker.task.lifecycle;

import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.plugin.api.task.TaskOutcome;
import dev.everyagent.worker.hub.EventSink;
import dev.everyagent.worker.task.TaskEventWire;
import dev.everyagent.plugin.api.event.Events;
import dev.everyagent.worker.proto.TaskDtos.TaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 成对节点(order=840)：任务状态（一事）。
 * <p>下行段：startedAt + setStatus(RUNNING) + task.updated 广播
 * ——在 main.agent(390) 之后、内核之前执行（临界段节点下行段在段边界外运行）。
 * <p>上行段（临界段首环，共享临界区）：终态 CAS（幂等门）+ endedAt/error/status +
 * 终态 task.updated 广播。
 * <p>**agent 级状态不在本节点**：{@code agent.status} 的 running 与终态由 advisor 链
 * 按 per-run 生命周期发射（{@code AgentStatusAdvisor} → {@code AgentEntity}，§7.20.1）；
 * 本节点只管任务级 {@code task.updated}。
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
        var t = ((TaskLifecycleContextImpl) ctx).taskEntryImpl();
        ctx.startedAt(System.currentTimeMillis());
        synchronized (t) {
            t.status = TaskStatus.RUNNING;
        }
        // agent.status{running} 已退役：由 AgentStatusAdvisor 每轮 run() 首帧自动发。
        TaskEventWire.fanoutTasks(eventSink, Events.TASK_UPDATED, t.runtimeSummaryJson());
    }

    @Override
    protected Object up(TaskLifecycleContext ctx, Object result) {
        var t = ((TaskLifecycleContextImpl) ctx).taskEntryImpl();
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
        // agent.status 终态已退役：由 AgentStatusAdvisor 的 doOnComplete/doOnError/doOnCancel
        // 自动发（任务级终态与本节点的 task.updated 广播是分属两个维度的状态，§7.20.1）。
        try {
            TaskEventWire.fanoutTasks(eventSink, Events.TASK_UPDATED, t.runtimeSummaryJson());
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
}
