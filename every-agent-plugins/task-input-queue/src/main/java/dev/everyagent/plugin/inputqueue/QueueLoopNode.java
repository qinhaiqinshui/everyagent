package dev.everyagent.plugin.inputqueue;

import dev.everyagent.plugin.api.task.TaskChain;
import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.plugin.api.task.TaskLifecycleNode;
import dev.everyagent.plugin.api.task.TaskOutcome;
import dev.everyagent.plugin.api.task.TaskStoreService;
import dev.everyagent.plugin.api.task.UserInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 队列循环节点（紧贴内核，order=395）。
 * <p>下行段：注册 per-task InputQueue + 恢复悬空队列（readQueue → 包装为 ctx → offer）。
 * <p>invoke 体内：next.proceed(ctx)（= 后续节点直至内核，agent.run 一次）→ queue.poll()
 * → 有则把 polledCtx 的 input/rawContent/metadata 设到当前 ctx 再 next.proceed，空就返回。
 * consumeInput 不由本节点执行——交给后续的 consume.input(396) 核心节点。
 * <p>上行段（return 后）：落盘悬空队列 queue.jsonl / 空则删除。
 * <p>finally：从注册表注销队列。
 * <p>非临界段节点（order=395 < 420），不经临界段共享锁。queue.jsonl 读写失败仅 warn。
 */
public final class QueueLoopNode implements TaskLifecycleNode {

    private static final Logger log = LoggerFactory.getLogger(QueueLoopNode.class);

    private final TaskQueueRegistry registry;
    private final TaskStoreService store;

    public QueueLoopNode(TaskQueueRegistry registry, TaskStoreService store) {
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
        // 磁盘项只含 text/rawContent，包装为轻量 ctx 入队（metadata 不落盘，恢复为 null）
        try {
            for (UserInput q : store.readQueue(store.dirOf(taskId))) {
                queue.offer(new RestoredQueueContext(q.text(), q.rawContent()));
            }
        } catch (Exception e) {
            log.warn("恢复悬空队列失败 task={}", taskId, e);
        }

        try {
            // 内核循环：跑一轮 → 队列取下一条 → 有就再跑
            log.warn("[DEDUP] QueueLoopNode 首轮 proceed taskId={} queueSize={}", taskId, queue.size());
            Object result = next.proceed(ctx);
            int round = 1;
            while (result instanceof TaskOutcome to && to.status() == TaskOutcome.TaskEndStatus.DONE) {
                TaskLifecycleContext polledCtx = queue.poll();
                if (polledCtx == null) {
                    break;
                }
                // 把 polledCtx 的数据设到当前 ctx，后续节点（edit.resend / consume.input）能读到
                ctx.input(polledCtx.input());
                ctx.rawContent(polledCtx.rawContent());
                ctx.runParams(polledCtx.runParams());
                ctx.metadata(polledCtx.metadata());
                round++;
                log.warn("[DEDUP] QueueLoopNode 第{}轮 proceed(队列 poll) taskId={} text='{}'",
                        round, taskId, polledCtx.input());
                result = next.proceed(ctx);
            }

            // 上行：落盘悬空队列（queue.jsonl 只持久化 text/rawContent，metadata 不落盘）
            var pending = queue.snapshotItems();
            try {
                if (pending.isEmpty()) {
                    store.deleteQueue(store.dirOf(taskId));
                } else {
                    store.writeQueue(store.dirOf(taskId), pending.stream()
                            .map(c -> UserInput.of(c.input(), c.rawContent()))
                            .toList());
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
