package dev.everyagent.worker.task.lifecycle;

import dev.everyagent.plugin.api.task.TaskLifecycleContext;

/**
 * 纯上行节点基类：SectionNode 的特化（下行段为空）。
 * <p>上行段在 next.proceed() 返回后执行收口动作；order ∈ [420,850] 时
 * 上行段进入临界段共享临界区（见 TaskLifecycleExecutor）。
 */
public abstract class UpstreamNode extends SectionNode {

    @Override
    protected final void down(TaskLifecycleContext ctx) {
        // 纯上行节点：下行段为空
    }
}
