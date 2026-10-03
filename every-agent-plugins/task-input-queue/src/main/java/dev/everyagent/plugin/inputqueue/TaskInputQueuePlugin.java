package dev.everyagent.plugin.inputqueue;

import dev.everyagent.plugin.api.EveryAgentPlugin;
import dev.everyagent.plugin.api.WorkerPluginContext;
import dev.everyagent.plugin.api.event.StreamEmitter;
import dev.everyagent.plugin.api.task.TaskService;
import dev.everyagent.plugin.api.task.TaskStoreService;

/**
 * task-input-queue 插件入口。
 * <p>activate() 中注册 QueueDispatchNode(15)、QueueLoopNode(870,临界段内侧轮次循环)、
 * DialogInsertAdvisorProvider 与队列 RPC（queueRemove/queueMove/queueSnapshot）。
 */
public class TaskInputQueuePlugin implements EveryAgentPlugin {

    @Override
    public String id() { return "task-input-queue"; }

    @Override
    public void activate(WorkerPluginContext ctx) {
        TaskService taskService = ctx.services().task();
        TaskStoreService store = ctx.services().store();
        StreamEmitter eventSink = ctx.services().stream();

        TaskQueueRegistry queueRegistry = new TaskQueueRegistry();

        ctx.registerTaskLifecycleNode(new QueueDispatchNode(queueRegistry, taskService, eventSink));
        ctx.registerTaskLifecycleNode(new QueueLoopNode(queueRegistry, store, eventSink));
        ctx.registerAdvisorProvider(new DialogInsertAdvisorProvider(queueRegistry));

        // 注册 task.queueRemove / task.queueMove / task.queueSnapshot RPC
        QueueRpcHandler rpcHandler = new QueueRpcHandler(queueRegistry, taskService, store, eventSink);
        ctx.registerRpcMethod("task.queueRemove", rpcHandler::rpcQueueRemove);
        ctx.registerRpcMethod("task.queueMove", rpcHandler::rpcQueueMove);
        ctx.registerRpcMethod("task.queueSnapshot", rpcHandler::rpcQueueSnapshot);
    }
}

