package dev.everyagent.plugin.taskqueue;

import dev.everyagent.plugin.api.EveryAgentPlugin;
import dev.everyagent.plugin.api.WorkerPluginContext;
import dev.everyagent.plugin.api.config.WorkerConfig;
import dev.everyagent.plugin.api.event.StreamEmitter;

/**
 * task-queue 插件入口。
 * <p>activate() 中注册 QueueAdmissionNode、TaskQueueAdmissionPolicy、task.queueList RPC。
 */
public class TaskQueuePlugin implements EveryAgentPlugin {

    @Override
    public String id() { return "task-queue"; }

    @Override
    public void activate(WorkerPluginContext ctx) {
        WorkerConfig config = ctx.services().config();
        StreamEmitter stream = ctx.services().stream();

        TaskQueue taskQueue = new TaskQueue(config, stream);

        ctx.registerTaskLifecycleNode(new QueueAdmissionNode(taskQueue));
        ctx.registerTaskAdmissionPolicy(new TaskQueueAdmissionPolicy());

        // 注册 task.queueList RPC
        TaskQueueRpcHandler rpcHandler = new TaskQueueRpcHandler(taskQueue);
        ctx.registerRpcMethod("task.queueList", rpcHandler::rpcTaskQueueList);
    }
}
