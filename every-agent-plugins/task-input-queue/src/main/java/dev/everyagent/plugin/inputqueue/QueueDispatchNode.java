package dev.everyagent.plugin.inputqueue;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.task.TaskChain;
import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.plugin.api.task.TaskLifecycleNode;
import dev.everyagent.worker.task.TaskEntry;
import dev.everyagent.worker.task.TaskManager;
import dev.everyagent.worker.task.UserInput;
import dev.everyagent.worker.rpc.RpcContext;

import java.util.Map;

/**
 * 任务内队列·RPC 线程阶段节点(order=65)。
 * 运行中任务收到 task.run 时：
 * <ul>
 *   <li>metadata.insert=true → offer 到插入对话队列 → ctx.ok → 短路(null)</li>
 *   <li>无 insert 标记 → offer 到输入队列 → ctx.ok → 短路(null)</li>
 *   <li>无 taskId 或终态 → 继续往下(next.proceed)</li>
 * </ul>
 */
public final class QueueDispatchNode implements TaskLifecycleNode {

    private final TaskQueueRegistry registry;
    private final TaskManager taskManager;

    public QueueDispatchNode(TaskQueueRegistry registry, TaskManager taskManager) {
        this.registry = registry;
        this.taskManager = taskManager;
    }

    @Override
    public String id() { return "queue.dispatch"; }

    @Override
    public float order() { return 65; }

    @Override
    public Object invoke(TaskLifecycleContext ctx, TaskChain next) throws Exception {
        String taskId = ctx.taskId();
        if (taskId == null || taskId.isEmpty()) {
            return next.proceed(ctx);  // 新建任务，继续往下
        }

        TaskEntry t = taskManager.get(taskId);
        if (t != null && !t.status.terminal()) {
            // 运行中
            Map<String, Object> metadata = ctx.metadata();
            if (metadata != null && Boolean.TRUE.equals(metadata.get("insert"))) {
                // 插入到当前对话：加入插入队列
                registry.getOrCreateDialogInsertQueue(taskId)
                        .offer(UserInput.of(ctx.input(), ctx.rawContent()));
            } else {
                // 入队
                registry.getOrCreateInputQueue(taskId)
                        .offer(ctx.input(), ctx.rawContent());
            }

            // 短路：ctx.ok + 返回 null
            var rpcCtx = ctx.rpcContext();
            if (rpcCtx instanceof RpcContext rc) {
                rc.ok(Json.obj()
                        .put("taskId", taskId)
                        .put("status", t.status.wire())
                        .put("queued", true));
            }
            return null;  // 短路，后续节点不执行
        }

        return next.proceed(ctx);  // 终态或不存在，继续往下
    }
}
