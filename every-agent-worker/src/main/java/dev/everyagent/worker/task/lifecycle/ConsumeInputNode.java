package dev.everyagent.worker.task.lifecycle;

import dev.everyagent.plugin.api.task.TaskChain;
import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.plugin.api.task.TaskLifecycleNode;
import dev.everyagent.plugin.api.task.UserInput;

/**
 * 消费输入节点（order=396）——输入消费的唯一入口。
 * 从 ctx 读取 input/rawContent，调用 consumeInput 消费。
 * <p>首轮：ctx.input() 为 ThreadSubmitNode 从 RPC 设置的原始输入（即旧 initialInput）。
 * <p>后续轮次：QueueLoopNode(395) 从队列 poll 后覆盖 ctx.input()，本节点再消费。
 * <p>MainAgentNode(390) 只负责构建 agent，不再消费输入——避免同一输入被消费两次。
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
