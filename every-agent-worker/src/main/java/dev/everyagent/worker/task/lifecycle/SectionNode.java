package dev.everyagent.worker.task.lifecycle;

import dev.everyagent.plugin.api.task.TaskChain;
import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.plugin.api.task.TaskLifecycleNode;
import dev.everyagent.plugin.api.task.TaskOutcome;

/**
 * 临界段节点基类：order ∈ [420,850] 的连续 SectionNode 段共享一次 synchronized(ctx.taskLock())。
 * <p>下行段 {@link #down} 在段边界外按 order 升序执行（无锁）；
 * 上行段 {@link #up} 在段共享临界区内按 order 降序执行。
 * <p>非临界段的 SectionNode 按普通 invoke 语义（down → next → up）。
 * <p>down/up 为 protected：仅同包的 TaskLifecycleExecutor 可在临界段组装时直接调用
 * （绕过 invoke 透传样板），插件节点（非 SectionNode）永远走 invoke。
 */
public abstract class SectionNode implements TaskLifecycleNode {

    @Override
    public final Object invoke(TaskLifecycleContext ctx, TaskChain next) throws Exception {
        down(ctx);
        return up(ctx, next.proceed(ctx));
    }

    /** 下行段（开始动作）。默认空。可抛异常（否决：内层不执行）。 */
    protected void down(TaskLifecycleContext ctx) throws Exception {
    }

    /**
     * 上行段（收口动作）。不得抛异常（自吞记 WARN），可改写 result。
     * @param ctx 生命周期上下文
     * @param result 内层（next）返回的结局（已是值，内核已翻译异常）
     * @return 本节点的结局（可改写 result 或原样返回）
     */
    protected abstract Object up(TaskLifecycleContext ctx, Object result);
}
