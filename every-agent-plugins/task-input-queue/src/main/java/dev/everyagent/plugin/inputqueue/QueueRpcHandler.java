package dev.everyagent.plugin.inputqueue;

import dev.everyagent.contract.json.Json;
import dev.everyagent.contract.rpc.Rpc;
import dev.everyagent.worker.task.TaskManager;
import dev.everyagent.worker.task.TaskStore;
import dev.everyagent.worker.task.UserInput;
import dev.everyagent.worker.rpc.RpcContext;
import dev.everyagent.worker.rpc.RpcDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * task.queueRemove / task.queueMove RPC 处理器（从 TaskManager 迁入插件）。
 * 运行中热任务直接改内存 InputQueue；终态任务改磁盘悬空队列 queue.jsonl。
 */
@Component
public class QueueRpcHandler {

    private static final Logger log = LoggerFactory.getLogger(QueueRpcHandler.class);

    private final TaskQueueRegistry registry;
    private final TaskManager taskManager;
    private final TaskStore store;

    public QueueRpcHandler(RpcDispatcher dispatcher, TaskQueueRegistry registry,
            TaskManager taskManager, TaskStore store) {
        this.registry = registry;
        this.taskManager = taskManager;
        this.store = store;
        dispatcher.register("task.queueRemove", this::rpcQueueRemove);
        dispatcher.register("task.queueMove", this::rpcQueueMove);
    }

    private void rpcQueueRemove(RpcContext ctx) {
        String taskId = ctx.strParam("taskId");
        int index = ctx.params().path("index").asInt(-1);
        mutateQueue(ctx, taskId, "remove", index, -1, -1);
    }

    private void rpcQueueMove(RpcContext ctx) {
        String taskId = ctx.strParam("taskId");
        int fromIndex = ctx.params().path("fromIndex").asInt(-1);
        int toIndex = ctx.params().path("toIndex").asInt(-1);
        mutateQueue(ctx, taskId, "move", fromIndex, fromIndex, toIndex);
    }

    private void mutateQueue(RpcContext ctx, String taskId, String op, int index, int fromIndex, int toIndex) {
        // 热任务：内存队列操作
        InputQueue queue = registry.getInputQueue(taskId);
        if (queue != null) {
            try {
                if ("remove".equals(op)) {
                    queue.removeAt(index);
                } else {
                    if (fromIndex == toIndex) {
                        ctx.ok(Json.obj().put("taskId", taskId).put("ok", true));
                        return;
                    }
                    queue.move(fromIndex, toIndex);
                }
            } catch (IndexOutOfBoundsException e) {
                ctx.err(Rpc.ERR_BAD_PARAMS, "队列索引越界");
                return;
            }
            broadcastQueueUpdate(taskId, queue);
            ctx.ok(Json.obj().put("taskId", taskId).put("ok", true));
            return;
        }

        // 冷路径：终态任务的磁盘悬空队列
        TaskStore.StoredTask st = taskManager.diskEntry(taskId);
        if (st == null) {
            ctx.err(Rpc.ERR_NOT_FOUND, "任务不存在");
            return;
        }
        List<UserInput> list = new ArrayList<>(store.readQueue(st.dir()));
        if ("remove".equals(op)) {
            if (index < 0 || index >= list.size()) {
                ctx.err(Rpc.ERR_BAD_PARAMS, "队列索引越界");
                return;
            }
            list.remove(index);
        } else {
            if (fromIndex == toIndex) {
                ctx.ok(Json.obj().put("taskId", taskId).put("ok", true));
                return;
            }
            if (fromIndex < 0 || fromIndex >= list.size() || toIndex < 0 || toIndex >= list.size()) {
                ctx.err(Rpc.ERR_BAD_PARAMS, "队列索引越界");
                return;
            }
            UserInput item = list.remove(fromIndex);
            list.add(toIndex, item);
        }
        try {
            store.writeQueue(st.dir(), list);
        } catch (java.io.IOException e) {
            ctx.err(Rpc.ERR_INTERNAL, "队列写入失败");
            return;
        }
        ObjectNode summary = st.summary().deepCopy();
        ArrayNode arr = Json.arr();
        for (UserInput item : list) {
            arr.add(item.text());
        }
        summary.set("pendingInputs", arr);
        taskManager.publishTaskUpdated(taskId);
        ctx.ok(Json.obj().put("taskId", taskId).put("ok", true));
    }

    private void broadcastQueueUpdate(String taskId, InputQueue queue) {
        // 复用 QueueInputInterceptor 的广播逻辑（经 task.updated 携带 pendingInputs）
        // 简化：直接调 publishTaskUpdated（核心广播 summary，不含 pendingInputs）
        // 完整实现需要拼装 pendingInputs —— 由 QueueInputInterceptor 统一处理
        taskManager.publishTaskUpdated(taskId);
    }
}
