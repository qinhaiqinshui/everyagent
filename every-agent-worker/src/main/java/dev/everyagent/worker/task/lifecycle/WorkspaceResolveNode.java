package dev.everyagent.worker.task.lifecycle;

import dev.everyagent.contract.rpc.Rpc;
import dev.everyagent.plugin.api.task.TaskChain;
import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.plugin.api.task.TaskLifecycleNode;
import dev.everyagent.worker.modules.WorkspaceManager;
import dev.everyagent.worker.rpc.RpcContext;
import dev.everyagent.worker.task.TaskBootstrap;

/**
 * RPC 阶段节点(order=20)：workspace 解析（新建路径）。
 * rerun（ctx.taskId() 非空）空转——workspace 从 meta 恢复。
 */
public final class WorkspaceResolveNode implements TaskLifecycleNode {

    private final TaskBootstrap taskBootstrap;

    public WorkspaceResolveNode(TaskBootstrap taskBootstrap) {
        this.taskBootstrap = taskBootstrap;
    }

    @Override
    public String id() { return "workspace.resolve"; }

    @Override
    public float order() { return 20; }

    @Override
    public Object invoke(TaskLifecycleContext ctx, TaskChain next) throws Exception {
        var impl = (TaskLifecycleContextImpl) ctx;
        // rerun 路径：taskId 已有，workspace 从 meta 恢复，跳过
        if (impl.taskId() != null && !impl.taskId().isEmpty()) {
            return next.proceed(ctx);
        }
        var rpcCtx = ctx.rpcContext();
        if (!(rpcCtx instanceof RpcContext rc)) return next.proceed(ctx);
        try {
            WorkspaceManager.Root root = taskBootstrap.resolveWorkspace(rc.strParam("workspace"));
            impl.workspaceRoot(root.path().toString());
            impl.workspaceId(taskBootstrap.workspaceIdOf(root.path().toString()));
        } catch (java.io.IOException e) {
            rc.err(Rpc.ERR_INTERNAL, "工作区目录不可用: " + e.getMessage());
            return null;
        }
        return next.proceed(ctx);
    }
}
