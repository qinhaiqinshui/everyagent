package dev.everyagent.plugin.inputqueue;

import dev.everyagent.plugin.api.task.TaskChain;
import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.plugin.api.task.TaskLifecycleNode;
import dev.everyagent.plugin.api.task.TaskOutcome;
import dev.everyagent.worker.task.TaskStore;
import dev.everyagent.worker.task.UserInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 队列循环节点（紧贴内核，order=395）。
 * <p>下行段：注册 per-task InputQueue + 恢复悬空队列（readQueue → offer）。
 * <p>invoke 体内：next.proceed(ctx)（= 内核，agent.run 一次）→ queue.poll() → 有就 consumeInput + 再跑，空就返回。
 * <p>上行段（return 后）：落盘悬空队列 queue.jsonl / 空则删除。
 * <p>finally：从注册表注销队列。
 * <p>非临界段节点（order=395 < 420），不经临界段共享锁。queue.jsonl 读写失败仅 warn。
 */
public final class QueueLoopNode implements TaskLifecycleNode {

    private static final Logger log = LoggerFactory.getLogger(QueueLoopNode.class);

    private final TaskQueueRegistry registry;
    private final TaskStore store;

    public QueueLoopNode(TaskQueueRegistry registry, TaskStore store) {
        this.registry = registry;
        this.store = store;
    }

    @Override
    public String id() { return "queue.loop"; }

    @Override
    public float order() { return 395; }

    @Override
    public Object invoke(TaskLifecycleContext ctx, TaskChain next) throws Exception {
        String taskId = ctx.taskId();
        // 注册 per-task 队列（queue.dispatch 节点在 RPC 线程入队用）
        // 用 getOrCreateInputQueue：若 queue.dispatch 已先行创建队列（极窄竞态窗口），复用之
        InputQueue queue = registry.getOrCreateInputQueue(taskId);

        // 下行：恢复悬空队列（新建任务 readQueue 返回空）
        try {
            for (UserInput q : store.readQueue(store.dirOf(taskId))) {
                queue.offer(q.text(), q.rawContent());
            }
        } catch (Exception e) {
            log.warn("恢复悬空队列失败 task={}", taskId, e);
        }

        try {
            // 内核循环：跑一轮 → 队列取下一条 → 有就再跑
            Object result = next.proceed(ctx);
            while (result instanceof TaskOutcome to && to.status() == TaskOutcome.TaskEndStatus.DONE) {
                UserInput nextInput = queue.poll();
                if (nextInput == null) {
                    break;
                }
                // consumeInput 经上下文（授权门/user.message/开轮/入会话内存）
                var impl = (dev.everyagent.worker.task.lifecycle.TaskLifecycleContextImpl) ctx;
                impl.consumeInput(impl.taskEntry().main, nextInput);
                result = next.proceed(ctx);
            }

            // 上行：落盘悬空队列
            var pending = queue.snapshotItems();
            try {
                if (pending.isEmpty()) {
                    store.deleteQueue(store.dirOf(taskId));
                } else {
                    store.writeQueue(store.dirOf(taskId), pending);
                }
            } catch (Exception e) {
                log.warn("终态队列落盘失败 task={}(悬空队列丢弃)", taskId, e);
            }

            return result;
        } finally {
            registry.unregister(taskId);
        }
    }
}
