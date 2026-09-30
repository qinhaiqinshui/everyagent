package dev.everyagent.plugin.inputqueue;

import dev.everyagent.plugin.api.permission.TaskInfo;
import dev.everyagent.plugin.api.task.TaskLifecycleContext;

import java.util.Map;

/**
 * 悬空队列磁盘恢复项的轻量 ctx（queue.jsonl → 内存队列）。
 * <p>queue.jsonl 只持久化 text/rawContent（metadata 不落盘），恢复时无法重建完整任务上下文；
 * QueueLoopNode poll 后只读取 input/rawContent/metadata 三字段设到当前 ctx 上，
 * 其余生命周期方法在本实现中返回空值、不应被调用。
 */
final class RestoredQueueContext implements TaskLifecycleContext {

    private final String input;
    private final String rawContent;

    RestoredQueueContext(String input, String rawContent) {
        this.input = input;
        this.rawContent = rawContent;
    }

    @Override public String input() { return input; }
    @Override public String rawContent() { return rawContent; }

    // ---- setter（恢复项不应被调用）----

    @Override public void input(String input) { }
    @Override public void rawContent(String rawContent) { }
    @Override public void runParams(Map<String, Object> runParams) { }
    @Override public void metadata(Map<String, Object> metadata) { }

    // ---- 以下方法对恢复项无意义（QueueLoopNode 不读取）----

    @Override public String taskId() { return null; }
    @Override public String title() { return null; }
    @Override public String workspaceRoot() { return null; }
    @Override public String workspaceId() { return null; }
    @Override public String mainAgentId() { return null; }
    @Override public String status() { return null; }
    @Override public TaskInfo taskInfo() { return null; }
    @Override public Object taskLock() { return null; }
    @Override public long startedAt() { return 0; }
    @Override public void startedAt(long ms) { }
    @Override public void onUsageBroadcast(Runnable hook) { }
    @Override public void agentStatus(String agentId, String status) { }
    @Override public Map<String, Object> runParams() { return Map.of(); }
    @Override public Object rpcContext() { return null; }
}
