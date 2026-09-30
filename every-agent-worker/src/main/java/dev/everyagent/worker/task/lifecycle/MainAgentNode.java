package dev.everyagent.worker.task.lifecycle;

import dev.everyagent.plugin.api.task.TaskChain;
import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.plugin.api.task.TaskLifecycleNode;
import dev.everyagent.plugin.api.task.TaskOutcome;

/**
 * 下行节点(order=390)：仅 buildMainAgent。
 * 构建主 agent 并设置到 TaskEntry.main，消费输入由后续的 {@link ConsumeInputNode}(396) 统一负责。
 * 这样避免了 MainAgentNode 与 ConsumeInputNode 对同一条输入各消费一次导致 user.message 重复。
 */
public final class MainAgentNode implements TaskLifecycleNode {

    @Override
    public String id() { return "main.agent"; }

    @Override
    public float order() { return 390; }

    @Override
    public Object invoke(TaskLifecycleContext ctx, TaskChain next) throws Exception {
        var impl = (TaskLifecycleContextImpl) ctx;
        var t = impl.taskEntryImpl();
        var main = impl.mainAgentBuilder().apply(impl.priorConversation());
        t.main = main;
        return next.proceed(ctx);
    }
}
