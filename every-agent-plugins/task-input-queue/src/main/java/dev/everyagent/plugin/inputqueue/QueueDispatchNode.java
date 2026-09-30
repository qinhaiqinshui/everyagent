package dev.everyagent.plugin.inputqueue;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.task.TaskChain;
import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.plugin.api.task.TaskLifecycleNode;
import dev.everyagent.plugin.api.task.TaskService;
import dev.everyagent.plugin.api.task.TaskRuntime;
import dev.everyagent.plugin.api.rpc.RpcContext;

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
    private final TaskService taskService;

    public QueueDispatchNode(TaskQueueRegistry registry, TaskService taskService) {
        this.registry = registry;
        this.taskService = taskService;
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

        TaskRuntime t = taskService.get(taskId);
        if (t != null && !t.terminal()) {
            // 运行中
            // insert/task.run 插件参数走 runParams(一次性容器,不落盘),非任务级持久化 metadata
            Map<String, Object> runParams = ctx.runParams();
            if (runParams != null && Boolean.TRUE.equals(runParams.get("insert"))) {
                // 插入到当前对话：入队整个 ctx（DialogInsertAdvisor 从 ctx 取 input/rawContent drain）
                registry.getOrCreateDialogInsertQueue(taskId).offer(ctx);
            } else {
                // 入队整个 ctx（含 metadata，后续节点自行消费）
                registry.getOrCreateInputQueue(taskId).offer(ctx);
            }

            // 短路：ctx.ok + 返回 null
            var rpcCtx = ctx.rpcContext();
            if (rpcCtx instanceof RpcContext rc) {
                rc.ok(Json.obj()
                        .put("taskId", taskId)
                        .put("status", t.status())
                        .put("queued", true));
            }
            return null;  // 短路，后续节点不执行
        }

        return next.proceed(ctx);  // 终态或不存在，继续往下
    }
}
