package dev.everyagent.plugin.api.task;

/** 链的下一环。 */
@FunctionalInterface
public interface TaskChain {
    TaskOutcome proceed(TaskLifecycleContext ctx) throws Exception;
}
