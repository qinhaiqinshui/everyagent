package dev.everyagent.plugin.inputqueue;

import dev.everyagent.contract.json.Json;
import dev.everyagent.contract.rpc.Rpc;
import dev.everyagent.plugin.api.event.Channels;
import dev.everyagent.plugin.api.event.Events;
import dev.everyagent.plugin.api.event.StreamEmitter;
import dev.everyagent.plugin.api.rpc.RpcContext;
import dev.everyagent.plugin.api.task.StoredTaskInfo;
import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.plugin.api.task.TaskRuntime;
import dev.everyagent.plugin.api.task.TaskService;
import dev.everyagent.plugin.api.task.TaskStoreService;
import dev.everyagent.plugin.api.task.UserInput;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 队列 RPC 处理器（task-input-queue 插件自注册，从 TaskManager 迁入）。
 * <ul>
 *   <li>`task.queueRemove` / `task.queueMove`：运行中热任务直接改内存 InputQueue，
 *       终态任务改磁盘悬空队列 queue.jsonl；变更后广播 task.updated（pendingInputs）。</li>
 *   <li>`task.queueSnapshot`：队列快照拉取——前端队列面板的自持数据源
 *       （队列专有数据不进核心前端类型）；热任务取内存队列，运行中未注册队列视为空，
 *       终态任务读磁盘悬空队列。响应除 `pendingInputs`（纯文本，面板展示/广播同形）外
 *       另带等长对齐的 `pendingInputsRaw`（原始输入含 opaque token 串，空串=无），
 *       供面板「编辑」回填还原胶囊。</li>
 * </ul>
 */
public class QueueRpcHandler {

    private final TaskQueueRegistry registry;
    private final TaskService taskService;
    private final TaskStoreService store;
    private final StreamEmitter eventSink;

    public QueueRpcHandler(TaskQueueRegistry registry, TaskService taskService,
            TaskStoreService store, StreamEmitter eventSink) {
        this.registry = registry;
        this.taskService = taskService;
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

    /**
     * task.queueSnapshot：拉取队列快照 `{taskId, pendingInputs:[…], pendingInputsRaw:[…]}`。
     * 前端队列面板数据源（以 task.updated 广播为刷新信号，来信号即重拉）。
     * <p>{@code pendingInputsRaw} 与 {@code pendingInputs} 等长、按位对齐，每项为原始输入
     * （含 opaque token 串）或空串（无原始内容）。仅队列面板「编辑」回填用——回填走
     * {@code ui.setComposerRawContent} 才能还原 @文件胶囊，走纯文本会退化成明文。
     * 队列专有数据仍不进核心公共类型（ComposerPanelCtx/taskStore）。
     */
    void rpcQueueSnapshot(RpcContext ctx) {
        String taskId = ctx.strParam("taskId");
        // 热任务：内存队列快照
        InputQueue queue = registry.getInputQueue(taskId);
        if (queue != null) {
            List<String> texts = new ArrayList<>();
            List<String> raws = new ArrayList<>();
            for (TaskLifecycleContext item : queue.snapshotItems()) {
                texts.add(item.input());
                raws.add(item.rawContent() == null ? "" : item.rawContent());
            }
            ctx.ok(snapshotOk(taskId, texts, raws));
            return;
        }
        // 运行中但队列尚未注册（queue.loop 下行前的极窄窗口）：视为空队列
        TaskRuntime t = taskService.get(taskId);
        if (t != null && !t.terminal()) {
            ctx.ok(snapshotOk(taskId, List.of(), List.of()));
            return;
        }
        // 终态/冷任务：磁盘悬空队列
        StoredTaskInfo st = taskService.diskEntry(taskId);
        if (st == null) {
            ctx.err(Rpc.ERR_NOT_FOUND, "任务不存在");
            return;
        }
        List<String> items = new ArrayList<>();
        List<String> raws = new ArrayList<>();
        for (UserInput item : store.readQueue(st.dir())) {
            items.add(item.text());
            raws.add(item.rawContent() == null ? "" : item.rawContent());
        }
        ctx.ok(snapshotOk(taskId, items, raws));
    }

    private static ObjectNode snapshotOk(String taskId, List<String> items, List<String> rawItems) {
        return Json.obj().put("taskId", taskId)
                .set("pendingInputs", QueueBroadcast.toArray(items))
                .set("pendingInputsRaw", QueueBroadcast.toArray(rawItems));
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
            TaskRuntime t = taskService.get(taskId);
            if (t != null) {
                QueueBroadcast.pendingInputs(eventSink, t, queue);
            }
            ctx.ok(Json.obj().put("taskId", taskId).put("ok", true));
            return;
        }

        // 冷路径：终态任务的磁盘悬空队列
        StoredTaskInfo st = taskService.diskEntry(taskId);
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
        summary.set("pendingInputs", QueueBroadcast.toArray(
                list.stream().map(UserInput::text).toList()));
        // 本 worker 的 tasks 频道 + 显式 workerId(旧任务 meta 里可能没有该字段,架构 §5.2)
        summary.put("workerId", eventSink.workerId());
        eventSink.fanout(k -> Channels.tasks(k, eventSink.workerId()), Events.TASK_UPDATED, null,
                summary, null);
        ctx.ok(Json.obj().put("taskId", taskId).put("ok", true));
    }
}
