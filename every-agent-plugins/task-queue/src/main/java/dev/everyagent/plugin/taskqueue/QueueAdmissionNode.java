package dev.everyagent.plugin.taskqueue;

import dev.everyagent.plugin.api.task.TaskChain;
import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.plugin.api.task.TaskLifecycleNode;
import dev.everyagent.plugin.api.task.TaskOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 任务队列准入节点（形态三：try/finally 成对节点）。
 * <p>order=40，落在洋葱 RPC 线程段内：queue.dispatch(15) 之后不远处，介于 taskid.generate(30) 与
 * taskentry.create(50) 之间（全量 31 节点 order 表见插件指南 docs/plugin-guide/backend/task-and-rpc.md §2.3）。
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
    public float order() { return 40; }

    @Override
    public Object invoke(TaskLifecycleContext ctx, TaskChain next) throws Exception {
        // 下行：获取运行许可（可能阻塞）
        taskQueue.acquire(ctx.taskId());
        try {
            // 内层执行（taskentry.create + main.agent + 内核 + 上行收口）
            return next.proceed(ctx);
        } finally {
            // 上行：释放许可（必达）
            taskQueue.release(ctx.taskId());
        }
    }
}
