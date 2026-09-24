package dev.everyagent.worker.task.lifecycle;

import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.plugin.api.task.TaskLifecycleNode;
import dev.everyagent.plugin.api.task.TaskChain;
import dev.everyagent.plugin.api.task.TaskOutcome;

/**
 * 形态二基类：下行段为空（透传），final invoke = proceed + 上行段。
 * <p>下行段为空意味着下行动作在 next.proceed() 之前不做任何事；
 * 上行段在 next.proceed() 返回后执行收口动作。
 * <p>up() 为 protected：仅同包（lifecycle 包）的 TaskLifecycleExecutor 可直接调用
 * （临界段组合时绕过 invoke 透传样板），插件节点（非 UpstreamNode 子类）永远走 invoke。
 */
public abstract class UpstreamNode implements TaskLifecycleNode {

    @Override
    public final TaskOutcome invoke(TaskLifecycleContext ctx, TaskChain next) throws Exception {
        return up(ctx, next.proceed(ctx));
    }

    /**
     * 上行段（收口动作）。不得抛异常（自吞记 WARN），可改写 result。
     * @param ctx 生命周期上下文
     * @param result 内层（next）返回的结局（已是值，内核已翻译异常）
     * @return 本节点的结局（可改写 result 或原样返回）
     */
    protected abstract TaskOutcome up(TaskLifecycleContext ctx, TaskOutcome result);
}
