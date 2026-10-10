package dev.everyagent.plugin.api.task;

/**
 * 任务内核：对话式调用 agent（轮次循环）。
 * 必须把一切异常翻译为 Object（中断位恢复）。
 */
@FunctionalInterface
public interface TaskKernel {
    Object run(TaskLifecycleContext ctx);
}
