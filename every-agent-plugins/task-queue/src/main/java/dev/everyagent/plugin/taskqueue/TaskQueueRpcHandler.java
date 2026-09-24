package dev.everyagent.plugin.taskqueue;

import dev.everyagent.contract.json.Json;
import dev.everyagent.worker.proto.RpcMethods;
import dev.everyagent.worker.rpc.RpcContext;
import dev.everyagent.worker.rpc.RpcDispatcher;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ObjectNode;

/**
 * task.queueList RPC 处理器：返回当前排队中的任务列表。
 */
@Component
public class TaskQueueRpcHandler {

    private final TaskQueue taskQueue;

    public TaskQueueRpcHandler(RpcDispatcher dispatcher, TaskQueue taskQueue) {
        this.taskQueue = taskQueue;
        dispatcher.register(RpcMethods.TASK_QUEUE_LIST, this::rpcTaskQueueList);
    }

    private void rpcTaskQueueList(RpcContext ctx) {
        ObjectNode resp = Json.obj();
        resp.put("availablePermits", taskQueue.availablePermits());
        resp.put("queueLength", taskQueue.queueLength());
        var arr = Json.arr();
        for (String tid : taskQueue.waitingTaskIds()) {
            arr.add(tid);
        }
        resp.set("queue", arr);
        ctx.ok(resp);
    }
}
