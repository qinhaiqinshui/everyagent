package dev.everyagent.worker.task.lifecycle;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.task.TaskChain;
import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.plugin.api.task.TaskLifecycleNode;
import dev.everyagent.worker.hub.EventSink;
import dev.everyagent.worker.task.TaskEventWire;
import dev.everyagent.plugin.api.event.Events;
import dev.everyagent.worker.rpc.RpcContext;
import dev.everyagent.worker.task.TaskEntry;

/**
 * RPC 阶段节点(order=70)：TASK_CREATED 广播 + ctx.ok 应答。
 * <p>新建任务广播 task.created，再运行/运行中入队直接 ctx.ok。
 * 短路场景（幂等命中、认领失败）taskEntry 为 null → 空转。
 */
public final class ResponseAckNode implements TaskLifecycleNode {

    private final EventSink eventSink;

    public ResponseAckNode(EventSink eventSink) {
        this.eventSink = eventSink;
    }

    @Override
    public String id() { return "response.ack"; }

    @Override
    public float order() { return 70; }

    @Override
    public Object invoke(TaskLifecycleContext ctx, TaskChain next) throws Exception {
        var impl = (TaskLifecycleContextImpl) ctx;
        var t = impl.taskEntryImpl();
        if (t == null) {
            // 短路场景（幂等命中已 ok、认领失败已 err），不应到此
            return next.proceed(ctx);
        }
        var rpcCtx = ctx.rpcContext();
        if (rpcCtx instanceof RpcContext rc) {
            // 新建任务：广播 TASK_CREATED
            if (t.status != null && "created".equals(t.status.wire())) {
                TaskEventWire.fanoutTasks(eventSink, Events.TASK_CREATED, t.runtimeSummaryJson());
            }
            rc.ok(Json.obj()
                    .put("taskId", t.taskId)
                    .put("status", t.status != null ? t.status.wire() : "created"));
        }
        return next.proceed(ctx);
    }
}
