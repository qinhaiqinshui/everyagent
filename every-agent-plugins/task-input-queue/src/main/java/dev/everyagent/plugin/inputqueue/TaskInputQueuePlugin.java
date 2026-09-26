package dev.everyagent.plugin.inputqueue;

import dev.everyagent.plugin.api.EveryAgentPlugin;
import dev.everyagent.plugin.api.WorkerPluginContext;
import dev.everyagent.worker.hub.EventSink;
import dev.everyagent.worker.task.TaskManager;
import dev.everyagent.worker.task.TaskStore;

/**
 * task-input-queue 插件入口。
 * <p>activate() 中注册 QueueDispatchNode、QueueLoopNode、DialogInsertAdvisorProvider。
 */
public class TaskInputQueuePlugin implements EveryAgentPlugin {

    @Override
    public String id() { return "task-input-queue"; }

    @Override
    public void activate(WorkerPluginContext ctx) {
        TaskManager taskManager = ctx.getService(TaskManager.class);
        TaskStore store = ctx.getService(TaskStore.class);
        EventSink eventSink = ctx.getService(EventSink.class);

        TaskQueueRegistry queueRegistry = new TaskQueueRegistry();

        ctx.registerTaskLifecycleNode(new QueueDispatchNode(queueRegistry, taskManager));
        ctx.registerTaskLifecycleNode(new QueueLoopNode(queueRegistry, store));
        ctx.registerAdvisorProvider(new DialogInsertAdvisorProvider(queueRegistry));

        // 注册 task.queueRemove / task.queueMove RPC
        QueueRpcHandler rpcHandler = new QueueRpcHandler(queueRegistry, taskManager, store, eventSink);
        ctx.registerRpcMethod("task.queueRemove", rpcCtx -> rpcHandler.rpcQueueRemove(
                (dev.everyagent.worker.rpc.RpcContext) rpcCtx));
        ctx.registerRpcMethod("task.queueMove", rpcCtx -> rpcHandler.rpcQueueMove(
                (dev.everyagent.worker.rpc.RpcContext) rpcCtx));
    }
}

