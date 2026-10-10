package dev.everyagent.plugin.editresend;

import dev.everyagent.plugin.api.EveryAgentPlugin;
import dev.everyagent.plugin.api.WorkerPluginContext;
import dev.everyagent.plugin.api.event.StreamEmitter;
import dev.everyagent.plugin.api.task.TaskService;
import dev.everyagent.plugin.api.task.TaskStoreService;

/**
 * task-edit-resend 插件入口。
 * <p>activate() 中注册 EditResendNode（虚拟线程阶段 order=877，轮次循环段）。
 */
public class TaskEditResendPlugin implements EveryAgentPlugin {

    @Override
    public String id() { return "task-edit-resend"; }

    @Override
    public void activate(WorkerPluginContext ctx) {
        TaskStoreService store = ctx.services().store();
        StreamEmitter eventSink = ctx.services().stream();
        TaskService taskService = ctx.services().task();

        EditTruncateProcessor truncateProcessor = new EditTruncateProcessor(store, eventSink, taskService);
        ctx.registerTaskLifecycleNode(new EditResendNode(truncateProcessor));
    }
}
