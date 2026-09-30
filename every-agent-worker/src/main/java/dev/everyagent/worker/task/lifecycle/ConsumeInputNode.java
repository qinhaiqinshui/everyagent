package dev.everyagent.worker.task.lifecycle;

import dev.everyagent.plugin.api.task.TaskChain;
import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.plugin.api.task.TaskLifecycleNode;
import dev.everyagent.plugin.api.task.UserInput;

/**
 * 消费输入节点（order=396）。
 * 从 ctx 读取 input/rawContent，调用 consumeInput 消费。
 * 职责单一：只做 consumeInput，不关心队列、不关心编辑重发。
 * MainAgentNode(390) 的首条 consumeInput 不变（它消费 initialInput，不走队列循环）。
 */
public final class ConsumeInputNode implements TaskLifecycleNode {

    @Override
    public String id() { return "consume.input"; }

    @Override
    public float order() { return 396; }

    @Override
    public Object invoke(TaskLifecycleContext ctx, TaskChain next) throws Exception {
        var impl = (TaskLifecycleContextImpl) ctx;
        // consumeInput：授权门失效 + 记 user.message + 开轮落盘 + 入会话内存
        impl.consumeInput(impl.taskEntryImpl().main,
                UserInput.of(ctx.input(), ctx.rawContent()));
        return next.proceed(ctx);  // → kernel
    }
}
