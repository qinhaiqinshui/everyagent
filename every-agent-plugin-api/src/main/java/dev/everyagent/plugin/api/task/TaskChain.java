package dev.everyagent.plugin.api.task;

/** 链的下一环。 */
@FunctionalInterface
public interface TaskChain {
    Object proceed(TaskLifecycleContext ctx) throws Exception;
}
