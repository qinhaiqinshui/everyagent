package dev.everyagent.plugin.taskqueue;

import dev.everyagent.plugin.api.task.TaskChain;
import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.plugin.api.task.TaskLifecycleNode;
import dev.everyagent.plugin.api.task.TaskOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 任务队列准入节点（形态三：try/finally 成对节点）。
 * <p>order=250，落在洋葱下行空隙 100~400 之间（persistence.track=100 之后，status.start=300 之前）。
 * <p>下行段 acquire 阻塞等待运行许可（虚拟线程 park，零线程开销）；
 * finally 段 release 释放许可并唤醒下一个等待者。
 * <p>下行抛异常时 release 不执行（未进入不收口语义：下行动作在 try 外）。
 */
public final class QueueAdmissionNode implements TaskLifecycleNode {

    private static final Logger log = LoggerFactory.getLogger(QueueAdmissionNode.class);

    private final TaskQueue taskQueue;

    public QueueAdmissionNode(TaskQueue taskQueue) {
        this.taskQueue = taskQueue;
    }

    @Override
    public String id() { return "queue.admission"; }

    @Override
    public float order() { return 250; }

    @Override
    public TaskOutcome invoke(TaskLifecycleContext ctx, TaskChain next) throws Exception {
        // 下行：获取运行许可（可能阻塞）
        taskQueue.acquire(ctx.taskId());
        try {
            // 内层执行（status.start + main.agent + 内核 + 上行收口）
            return next.proceed(ctx);
        } finally {
            // 上行：释放许可（必达）
            taskQueue.release(ctx.taskId());
        }
    }
}
