package dev.everyagent.plugin.editresend;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.model.EmitEvent;
import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.plugin.api.proto.SnowflakeId;
import dev.everyagent.worker.hub.EventSink;
import dev.everyagent.plugin.api.event.Channels;
import dev.everyagent.plugin.api.event.Events;
import dev.everyagent.worker.agent.AgentEntity;
import dev.everyagent.worker.task.ConversationLoader;
import dev.everyagent.worker.task.TaskEntry;
import dev.everyagent.worker.task.TaskManager;
import dev.everyagent.worker.task.TaskStore;
import dev.everyagent.worker.task.lifecycle.TaskLifecycleContextImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.List;

/**
 * 编辑重发截断处理器（从 TaskManager.truncateForEdit / truncateForColdEdit 原样迁入）。
 * <p>运行中热路径：截断内存 EventLog + 磁盘 + 重建会话内存 + 清理子 agent + 更新 meta + 广播；
 * 终态冷路径：截断磁盘 + 更新 meta + 广播。
 * <p>EditResendNode 在 VT 阶段执行（queue.loop 内层），此时任务一定运行中（ctx.taskEntry()
 * 非 null 且非终态），实际只走热路径；冷路径保留以承载方法完整语义。
 */
public final class EditTruncateProcessor {

    private static final Logger log = LoggerFactory.getLogger(EditTruncateProcessor.class);

    private final TaskStore store;
    private final EventSink eventSink;
    private final TaskManager taskManager;

    public EditTruncateProcessor(TaskStore store, EventSink eventSink,
            TaskManager taskManager) {
        this.store = store;
        this.eventSink = eventSink;
        this.taskManager = taskManager;
    }

    /**
     * 截断：运行中热路径 / 终态冷路径。
     * 从 ctx 判断任务状态，选择热路径或冷路径。
     */
    public void truncate(String taskId, String editSeq, String text, String rawContent,
            TaskLifecycleContext ctx) {
        TaskEntry t = ((TaskLifecycleContextImpl) ctx).taskEntry();
        if (t != null && !t.status.terminal()) {
            truncateForEdit(taskId, t, editSeq, text, rawContent);
            return;
        }
        // 兜底冷路径（edit.resend 在 queue.loop 内层执行，理论上不会走到）
        TaskStore.StoredTask st = taskManager.diskEntry(taskId);
        if (st == null) {
            log.warn("编辑截断：任务不存在 task={} editSeq={}", taskId, editSeq);
            return;
        }
        try {
            truncateForColdEdit(taskId, st, editSeq, text, rawContent);
        } catch (Exception e) {
            log.error("编辑截断失败（冷路径）task={}", taskId, e);
        }
    }

    /**
     * 编辑重发·运行中热路径：截断磁盘+内存中 seq > editSeq 的事件，
     * 从磁盘重建主 agent 会话内存，广播 message.edited 同步事件，更新 meta。
     * 不清除输入框内容（正常入队由 consumeInput 消费）。
     */
    public void truncateForEdit(String taskId, TaskEntry t, String editSeq, String text, String rawContent) {
        long seq = Long.parseLong(editSeq);
        Path dir = store.dirOf(taskId);
        // 截断内存事件日志(先截断,再截断磁盘:truncateAndReset 用截断后的 EventLog 重建 cursor)
        t.log.truncateAfter(seq);
        try {
            boolean found = store.truncateAndReset(taskId, seq);
            if (!found) {
                log.warn("编辑截断：未找到 seq={} 的用户消息 task={}", editSeq, taskId);
                return;
            }
        } catch (Exception e) {
            log.error("编辑截断失败 task={}", taskId, e);
            return;
        }
        // 从磁盘重建主 agent 会话内存(磁盘已截断,ConversationLoader 载入截断后的历史)
        // 先停止子 agent(防止截断后旧子 agent 仍写事件/改文件)
        // SubAgent stopAll 由 subagent 插件负责
        AgentEntity main = t.main;
        if (main != null) {
            List<Message> rebuilt = ConversationLoader.load(store, dir, t.mainAgentId);
            main.conversation.clear();
            main.conversation.addAll(rebuilt);
        }
        // 清理子 agent 运行态(截断后旧轮的子 agent 已无效)
        t.agents.clear();
        // 子 agent Future 清理由 subagent 插件负责
        // 清理本轮文件改动收集器(随截断失效,新轮重建)
        t.fileChanges = null;
        t.fileChangesLight = null;
        t.fileChangesFull = null;
        // 更新 meta
        ObjectNode meta = store.readMeta(dir);
        if (meta != null) {
            meta.put("status", "running");
            meta.remove("endedAt");
            meta.remove("error");
            meta.put("seqLast", seq);
            try {
                TaskStore.writeMeta(dir, meta);
            } catch (Exception e) {
                log.warn("meta 更新失败 task={}", taskId, e);
            }
        }
        // 发射 message.edited 同步事件(经 TaskEvents emit,统一形状)
        ObjectNode editData = Json.obj()
                .put("seq", String.valueOf(seq));
        if (rawContent != null && !rawContent.isEmpty()) {
            editData.put("rawContent", rawContent);
        }
        t.events.emit(EmitEvent.of(SnowflakeId.next(), "message.edited", null, null, null,
                text, null, editData, EmitEvent.Mode.REPLACE));
    }

    /**
     * 编辑重发·冷启动路径：截断磁盘、广播 message.edited、更新 meta。
     * 截断后正常走 startRerun 冷启动（ConversationLoader 载入截断后的历史）。
     */
    public void truncateForColdEdit(String taskId, TaskStore.StoredTask st, String editSeq, String text, String rawContent) throws Exception {
        long seq = Long.parseLong(editSeq);
        Path dir = st.dir();
        boolean found = store.truncateAfterSeq(dir, seq);
        if (!found) {
            throw new IllegalArgumentException("未找到 seq=" + editSeq + " 的用户消息");
        }
        // 更新 meta
        ObjectNode meta = st.summary().deepCopy();
        meta.put("status", "created");
        meta.remove("endedAt");
        meta.remove("error");
        meta.put("seqLast", seq);
        TaskStore.writeMeta(dir, meta);
        // 广播 message.edited 同步事件(冷路径无 TaskEvents,payload 用新形状)
        ObjectNode editData = Json.obj()
                .put("seq", String.valueOf(seq));
        if (rawContent != null && !rawContent.isEmpty()) {
            editData.put("rawContent", rawContent);
        }
        ObjectNode editPayload = Json.obj()
                .put("content", text);
        editPayload.set("data", editData);
        eventSink.fanout(k -> Channels.taskStream(k, taskId), Events.MESSAGE_EDITED, null, editPayload, null);
    }
}
