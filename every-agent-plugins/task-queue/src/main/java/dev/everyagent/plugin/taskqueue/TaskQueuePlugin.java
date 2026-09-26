package dev.everyagent.plugin.taskqueue;

import dev.everyagent.plugin.api.EveryAgentPlugin;
import dev.everyagent.plugin.api.WorkerPluginContext;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.hub.EventSink;
import dev.everyagent.worker.proto.RpcMethods;

/**
 * task-queue 插件入口。
 * <p>activate() 中注册 QueueAdmissionNode、TaskQueueAdmissionPolicy、task.queueList RPC。
 */
public class TaskQueuePlugin implements EveryAgentPlugin {

    @Override
    public String id() { return "task-queue"; }

    @Override
    public void activate(WorkerPluginContext ctx) {
        WorkerProperties props = ctx.getService(WorkerProperties.class);
        EventSink eventSink = ctx.getService(EventSink.class);

        TaskQueue taskQueue = new TaskQueue(props, eventSink);

        ctx.registerTaskLifecycleNode(new QueueAdmissionNode(taskQueue));
        ctx.registerTaskAdmissionPolicy(new TaskQueueAdmissionPolicy());

        // 注册 task.queueList RPC
        TaskQueueRpcHandler rpcHandler = new TaskQueueRpcHandler(taskQueue);
        ctx.registerRpcMethod(RpcMethods.TASK_QUEUE_LIST, rpcCtx -> rpcHandler.rpcTaskQueueList(
                (dev.everyagent.worker.rpc.RpcContext) rpcCtx));
    }
}
