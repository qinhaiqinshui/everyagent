package dev.everyagent.plugin.editresend;

import dev.everyagent.plugin.api.EveryAgentPlugin;
import dev.everyagent.plugin.api.WorkerPluginContext;
import dev.everyagent.worker.task.TaskStore;
import dev.everyagent.worker.hub.EventSink;
import dev.everyagent.worker.task.TaskManager;

/**
 * task-edit-resend 插件入口。
 * <p>activate() 中注册 EditResendNode（虚拟线程阶段 order=395.5）。
 */
public class TaskEditResendPlugin implements EveryAgentPlugin {

    @Override
    public String id() { return "task-edit-resend"; }

    @Override
    public void activate(WorkerPluginContext ctx) {
        TaskStore store = ctx.getService(TaskStore.class);
        EventSink eventSink = ctx.getService(EventSink.class);
        TaskManager taskManager = ctx.getService(TaskManager.class);

        EditTruncateProcessor truncateProcessor = new EditTruncateProcessor(store, eventSink, taskManager);
        ctx.registerTaskLifecycleNode(new EditResendNode(truncateProcessor));
    }
}
