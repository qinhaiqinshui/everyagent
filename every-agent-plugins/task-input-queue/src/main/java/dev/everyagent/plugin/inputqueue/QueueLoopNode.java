package dev.everyagent.plugin.inputqueue;

import dev.everyagent.plugin.api.event.StreamEmitter;
import dev.everyagent.plugin.api.task.TaskChain;
import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.plugin.api.task.TaskLifecycleNode;
import dev.everyagent.plugin.api.task.TaskOutcome;
import dev.everyagent.plugin.api.task.TaskStoreService;
import dev.everyagent.plugin.api.task.UserInput;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 队列循环节点（order=870，临界段 [420,850] 内侧、ledger.persist(860) 之后）。
 * <p><b>位置红线：必须在临界段内侧。</b>本节点靠重复 {@code next.proceed} 续跑队列项，
 * 其 next 链 = [file.reference.process(875) → edit.resend(877) → consume.input(880) → 内核
 * → cascade.stop(900)↑ → spawned.await(950)↑]，全部是「每轮」语义的节点（逐轮输入预处理、
 * 逐轮失败级联停、逐轮子 agent 等待）。一旦本节点落到临界段之外（如曾经的 395），每轮
 * next.proceed 的上行段会把 status 终态 / registry.remove / persistence.untrack /
 * concurrency.release 等<b>一次性收口节点逐轮执行</b>——任务中途被移出注册表
 * （DataPusher 流源挂不回、cancel/task.poll 失效、ask 门失效）、事件 writer 提前关闭
 * （后续轮次事件不落盘，重启即丢）、并发计数重复扣减（2026-10 队列续跑第二轮
 * 无实时推送事故根因）。
 * <p>下行段：注册 per-task InputQueue + 恢复悬空队列（readQueue → 包装为 ctx → offer）。
 * <p>invoke 体内：next.proceed(ctx)（首轮，输入由后续 consume.input 消费）→ queue.poll()
 * → 有则把 polledCtx 的 input/rawContent/runParams/metadata 设到当前 ctx、广播消费后的
 * pendingInputs 快照（前端队列面板据此收敛），再 next.proceed 续跑；队列空时先回收没赶上
 * 工具循环下行的「插入对话」项（{@link #recycleUndrainedInserts}），有回收就继续续跑，
 * 真空才返回。
 * <p>上行段（全部轮次跑完才到）：再次回收残留插入项（取消/失败退出循环时 advisor 已无 drain
 * 时机，直接随 unregister 丢弃会吃掉用户输入）→ 广播收敛 → 落盘悬空队列 queue.jsonl / 空则删除。
 * <p>finally：从注册表注销队列（输入队列 + 插入对话队列一并移除）。段外节点（非 SectionNode），
 * 不经临界段共享锁；queue.jsonl 读写失败仅 warn。
 */
public final class QueueLoopNode implements TaskLifecycleNode {

    private static final Logger log = LoggerFactory.getLogger(QueueLoopNode.class);

    private final TaskQueueRegistry registry;
    private final TaskStoreService store;
    private final StreamEmitter eventSink;

    public QueueLoopNode(TaskQueueRegistry registry, TaskStoreService store, StreamEmitter eventSink) {
        this.registry = registry;
        this.store = store;
        this.eventSink = eventSink;
    }

    @Override
    public String id() { return "queue.loop"; }

    @Override
    public float order() { return 870; }

    @Override
    public Object invoke(TaskLifecycleContext ctx, TaskChain next) throws Exception {
        String taskId = ctx.taskId();
        // 注册 per-task 队列（queue.dispatch 节点在 RPC 线程入队用）
        // 用 getOrCreateInputQueue：若 queue.dispatch 已先行创建队列（竞态窗口），复用之
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
            // 内核循环：跑一轮 → 队列取下一条 → 有就再跑。
            // 全程位于临界段内侧：流源/落盘 writer/注册表条目/并发计数直到全部轮次
            // 结束才由外层收口节点一次性拆除，续跑轮次与首轮同权（实时推送 + 落盘）。
            Object result = next.proceed(ctx);
            while (result instanceof TaskOutcome to && to.status() == TaskOutcome.TaskEndStatus.DONE) {
                TaskLifecycleContext polledCtx = queue.poll();
                if (polledCtx == null) {
                    // 输入队列已空：把没赶上工具循环下行的插入项回收成普通轮次输入，本轮之后照跑
                    if (!recycleUndrainedInserts(taskId, queue)) {
                        break;
                    }
                    polledCtx = queue.poll();
                    if (polledCtx == null) {
                        break;
                    }
                }
                // 把 polledCtx 的数据设到当前 ctx，后续节点（file.reference/edit.resend/consume.input）能读到
                ctx.input(polledCtx.input());
                ctx.rawContent(polledCtx.rawContent());
                ctx.runParams(polledCtx.runParams());
                ctx.metadata(polledCtx.metadata());
                // 消费一项即广播消费后的 pendingInputs 快照：前端队列列表实时收敛/消失
                QueueBroadcast.pendingInputs(eventSink, ctx.taskRuntime(), queue);
                result = next.proceed(ctx);
            }

            // 回收未消费的插入项：AI 已收尾（本轮再无工具循环下行穿过 advisor）、取消或失败时，
            // 插入队列里的项不会被任何 advisor drain；随 finally unregister 一并丢弃会吃掉用户输入。
            // 故回填输入队列尾部 → 下面的 queue.jsonl 落盘把它当悬空队列持久化，
            // 下次运行时作普通轮次消费；同时广播一次 pendingInputs，前端队列面板据此重新显形。
            if (recycleUndrainedInserts(taskId, queue)) {
                QueueBroadcast.pendingInputs(eventSink, ctx.taskRuntime(), queue);
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

    /**
     * 回收未消费的插入项：把「插入到当前对话」队列里残留的 ctx 逐项搬回输入队列尾部。
     * @return true 表示确有回收发生（调用方据此广播 pendingInputs）
     */
    private boolean recycleUndrainedInserts(String taskId, InputQueue queue) {
        ConcurrentLinkedQueue<TaskLifecycleContext> inserts = registry.getDialogInsertQueue(taskId);
        if (inserts == null || inserts.isEmpty()) {
            return false;
        }
        int recycled = 0;
        TaskLifecycleContext undrained;
        while ((undrained = inserts.poll()) != null) {
            queue.offer(undrained);
            recycled++;
        }
        log.info("[queue] 插入项未被 advisor drain，回收进输入队列续跑 task={} count={}", taskId, recycled);
        return recycled > 0;
    }
}
