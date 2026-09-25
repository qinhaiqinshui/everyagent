package dev.everyagent.worker.task.lifecycle;

import dev.everyagent.plugin.api.task.TaskChain;
import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.plugin.api.task.TaskLifecycleNode;
import dev.everyagent.plugin.api.task.TaskOutcome;

/**
 * 下行节点(order=390)：buildMainAgent + consumeInput(首条输入)。
 * buildMainAgent 经上下文回调委托给 TaskManager，consumeInput 调上下文自身方法（行为零变化）。
 */
public final class MainAgentNode implements TaskLifecycleNode {

    @Override
    public String id() { return "main.agent"; }

    @Override
    public float order() { return 390; }

    @Override
    public Object invoke(TaskLifecycleContext ctx, TaskChain next) throws Exception {
        var impl = (TaskLifecycleContextImpl) ctx;
        var t = impl.taskEntry();
        var main = impl.mainAgentBuilder().apply(impl.priorConversation());
        t.main = main;
        impl.consumeInput(main, impl.initialInput());
        return next.proceed(ctx);
    }
}
