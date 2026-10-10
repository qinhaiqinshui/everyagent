package dev.everyagent.worker.task.lifecycle;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.task.TaskChain;
import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.plugin.api.task.TaskLifecycleNode;
import dev.everyagent.worker.rpc.RpcContext;
import dev.everyagent.worker.task.TaskEntry;
import dev.everyagent.worker.task.TaskManager;

import java.util.Map;

/**
 * RPC 阶段节点(order=10)：幂等键去重。
 * 从 RPC params 读 idempotencyKey，命中且任务仍在内存 → 直接 ok 返回（短路）。
 */
public final class IdempotencyCheckNode implements TaskLifecycleNode {

    private static final long IDEM_WINDOW_MS = 600_000;

    private final Map<String, TaskManager.IdemEntry> idem;
    private final Map<String, TaskEntry> tasks;

    public IdempotencyCheckNode(Map<String, TaskManager.IdemEntry> idem, Map<String, TaskEntry> tasks) {
        this.idem = idem;
        this.tasks = tasks;
    }

    @Override
    public String id() { return "idempotency.check"; }

    @Override
    public float order() { return 10; }

    @Override
    public Object invoke(TaskLifecycleContext ctx, TaskChain next) throws Exception {
        var rpcCtx = ctx.rpcContext();
        if (!(rpcCtx instanceof RpcContext rc)) return next.proceed(ctx);
        String idemKey = rc.optStrParam("idempotencyKey", null);
        if (idemKey == null || idemKey.isEmpty()) return next.proceed(ctx);
        // purge stale
        if (idem.size() >= 100) {
            long now = System.currentTimeMillis();
            idem.entrySet().removeIf(e -> now - e.getValue().ts() >= IDEM_WINDOW_MS);
        }
        TaskManager.IdemEntry e = idem.get(idemKey);
        if (e != null && System.currentTimeMillis() - e.ts() < IDEM_WINDOW_MS && tasks.containsKey(e.taskId())) {
            rc.ok(Json.obj().put("taskId", e.taskId()).put("deduplicated", true));
            return null; // short-circuit
        }
        return next.proceed(ctx);
    }
}
