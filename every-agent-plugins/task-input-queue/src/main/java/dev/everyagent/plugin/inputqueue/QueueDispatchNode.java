package dev.everyagent.plugin.inputqueue;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.event.StreamEmitter;
import dev.everyagent.plugin.api.task.TaskChain;
import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.plugin.api.task.TaskLifecycleNode;
import dev.everyagent.plugin.api.task.TaskService;
import dev.everyagent.plugin.api.task.TaskRuntime;
import dev.everyagent.plugin.api.rpc.RpcContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 任务内队列·RPC 线程阶段节点(order=15,idempotency.check 之后、workspace.resolve 之前)。
 * <p><b>位置约束:必须在 taskid.generate(30) 之前。</b>本节点的"新建任务"判定依据是
 * taskId 尚未生成(RPC 未携带 taskId 且 taskid.generate 未运行);若放在 taskentry.create(50)
 * 之后,新建任务的 entry 已入 tasks 表(status=created 非终态),会被误判为"运行中任务收到
 * 新输入"而入队短路——任务永远等不到虚拟线程,不落盘、不运行(2026-09-30 新任务失败事故)。
 * <ul>
 *   <li>新建(RPC 无 taskId)→ 继续往下(next.proceed),由后续节点生成 id/创建 entry</li>
 *   <li>RPC 带 taskId 且内存任务运行中 → offer 到队列(insert=true 先从输入队列摘掉该项、再入插入对话队列)→ ctx.ok → 短路(null)</li>
 *   <li>RPC 带 taskId 且终态/不存在(冷续跑)→ 继续往下(taskentry.create 从磁盘认领)</li>
 * </ul>
 */
public final class QueueDispatchNode implements TaskLifecycleNode {

    private static final Logger log = LoggerFactory.getLogger(QueueDispatchNode.class);

    private final TaskQueueRegistry registry;
    private final TaskService taskService;
    private final StreamEmitter eventSink;

    public QueueDispatchNode(TaskQueueRegistry registry, TaskService taskService, StreamEmitter eventSink) {
        this.registry = registry;
        this.taskService = taskService;
        this.eventSink = eventSink;
    }

    @Override
    public String id() { return "queue.dispatch"; }

    @Override
    public float order() { return 15; }

    @Override
    public Object invoke(TaskLifecycleContext ctx, TaskChain next) throws Exception {
        String taskId = ctx.taskId();
        if (taskId == null || taskId.isEmpty()) {
            log.debug("[queue] dispatch 新建路径继续下行(RPC 无 taskId)");
            return next.proceed(ctx);  // 新建任务，继续往下
        }

        TaskRuntime t = taskService.get(taskId);
        if (t != null && !t.terminal()) {
            // 运行中
            // insert/task.run 插件参数走 runParams(一次性容器,不落盘),非任务级持久化 metadata
            Map<String, Object> runParams = ctx.runParams();
            if (runParams != null && Boolean.TRUE.equals(runParams.get("insert"))) {
                // 插入到当前对话：先从输入队列**摘掉该项**（“插入即消费”——不摘则面板上该项原地不动，
                // 且本轮跑完后 queue.loop 还会把它当新一轮输入再跑一遍，形成重复提交）；
                // 再把摘到的 ctx（携 rawContent/metadata，非前端回传的裸文本）offer 进插入对话队列，
                // 由 DialogInsertAdvisor 在本轮工具循环下行 drain。
                registry.getOrCreateDialogInsertQueue(taskId)
                        .offer(takeQueueItem(taskId, ctx, runParams.get("index")));
                log.debug("[queue] dispatch 运行中输入走插入对话队列 task={}", taskId);
            } else {
                // 入队整个 ctx（含 metadata，后续节点自行消费）
                registry.getOrCreateInputQueue(taskId).offer(ctx);
                log.debug("[queue] dispatch 运行中输入入队 task={}", taskId);
            }

            // 短路：ctx.ok + 返回 null
            var rpcCtx = ctx.rpcContext();
            if (rpcCtx instanceof RpcContext rc) {
                rc.ok(Json.obj()
                        .put("taskId", taskId)
                        .put("status", t.status())
                        .put("queued", true));
            }

            // 广播 task.updated 携带 pendingInputs，前端队列面板据此刷新
            broadcastQueueUpdate(taskId, t);

            return null;  // 短路，后续节点不执行
        }

        log.debug("[queue] dispatch 终态/不存在继续下行 task={} terminal={}",
                taskId, t != null && t.terminal());
        return next.proceed(ctx);  // 终态或不存在，继续往下
    }

    /**
     * 插入即消费：按下标从输入队列摘掉该项（下标缺失/越界时回退按正文匹配删除首项），
     * 摘不到（如 queue.loop 尚未注册队列的极窄竞态窗口）就用本次 RPC 的 ctx 兜底——
     * 宁可丢下标语义也不让插入请求落空。
     * <p>优先用摘到的队列项：它带 {@code rawContent}（含 opaque token 原串）与 metadata，
     * 前端队列面板回传的裸文本没有这些（快照 {@code pendingInputs} 只有纯文本）。
     */
    private TaskLifecycleContext takeQueueItem(String taskId, TaskLifecycleContext incoming, Object indexParam) {
        InputQueue queue = registry.getInputQueue(taskId);
        if (queue == null) {
            return incoming;
        }
        TaskLifecycleContext removed = null;
        if (indexParam instanceof Number n) {
            int index = n.intValue();
            try {
                removed = queue.removeAt(index);
            } catch (IndexOutOfBoundsException e) {
                log.warn("[queue] 插入下标越界 task={} index={}", taskId, index);
            }
        }
        if (removed == null) {
            removed = queue.removeFirst(incoming.input());
        }
        return removed != null ? removed : incoming;
    }

    /**
     * 广播 task.updated 携带 pendingInputs（统一走 {@link QueueBroadcast}）。
     * 前端队列面板自持数据源（task.queueSnapshot），收到广播信号后拉取最新快照。
     */
    private void broadcastQueueUpdate(String taskId, TaskRuntime t) {
        QueueBroadcast.pendingInputs(eventSink, t, registry.getInputQueue(taskId));
    }
}
