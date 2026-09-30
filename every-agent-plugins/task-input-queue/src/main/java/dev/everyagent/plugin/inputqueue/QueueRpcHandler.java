package dev.everyagent.plugin.inputqueue;

import dev.everyagent.contract.json.Json;
import dev.everyagent.contract.rpc.Rpc;
import dev.everyagent.worker.hub.EventSink;
import dev.everyagent.plugin.api.event.Channels;
import dev.everyagent.plugin.api.event.Events;
import dev.everyagent.worker.task.TaskEntry;
import dev.everyagent.worker.task.TaskManager;
import dev.everyagent.worker.task.TaskStore;
import dev.everyagent.plugin.api.task.UserInput;
import dev.everyagent.plugin.api.rpc.RpcContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * task.queueRemove / task.queueMove RPC 处理器（从 TaskManager 迁入插件）。
 * 运行中热任务直接改内存 InputQueue；终态任务改磁盘悬空队列 queue.jsonl。
 */
public class QueueRpcHandler {

    private static final Logger log = LoggerFactory.getLogger(QueueRpcHandler.class);

    private final TaskQueueRegistry registry;
    private final TaskManager taskManager;
    private final TaskStore store;
    private final EventSink eventSink;

    public QueueRpcHandler(TaskQueueRegistry registry, TaskManager taskManager,
            TaskStore store, EventSink eventSink) {
        this.registry = registry;
        this.taskManager = taskManager;
        this.store = store;
        this.eventSink = eventSink;
    }

    void rpcQueueRemove(RpcContext ctx) {
        String taskId = ctx.strParam("taskId");
        int index = ctx.params().path("index").asInt(-1);
        mutateQueue(ctx, taskId, "remove", index, -1, -1);
    }

    void rpcQueueMove(RpcContext ctx) {
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
        eventSink.fanout(k -> Channels.tasks(k), Events.TASK_UPDATED, null, summary, null);
        ctx.ok(Json.obj().put("taskId", taskId).put("ok", true));
    }

    private void broadcastQueueUpdate(String taskId, InputQueue queue) {
        // 广播 task.updated 携带 pendingInputs（直接拼装 summary + pendingInputs）
        TaskEntry t = taskManager.get(taskId);
        if (t == null) return;
        t.touch();
        var summary = t.runtimeSummaryJson();
        var arr = tools.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        for (String text : queue.snapshot()) {
            arr.add(text);
        }
        summary.set("pendingInputs", arr);
        eventSink.fanout(k -> Channels.tasks(k), Events.TASK_UPDATED, null, summary, null);
    }
}
