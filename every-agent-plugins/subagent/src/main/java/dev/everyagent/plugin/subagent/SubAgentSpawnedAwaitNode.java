package dev.everyagent.plugin.subagent;

import dev.everyagent.plugin.api.task.TaskChain;
import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.plugin.api.task.TaskLifecycleNode;
import dev.everyagent.plugin.api.task.TaskOutcome;
import dev.everyagent.plugin.api.task.TaskRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 上行节点(order=950)：awaitAllBeforeFinish（等全部子 agent，超时级联停）。
 * 中断检测：await 后若线程被中断，改写 result 为 CANCELLED。
 */
public final class SubAgentSpawnedAwaitNode implements TaskLifecycleNode {

    private static final Logger log = LoggerFactory.getLogger(SubAgentSpawnedAwaitNode.class);

    private final SubAgentManager subs;

    public SubAgentSpawnedAwaitNode(SubAgentManager subs) {
        this.subs = subs;
    }

    @Override
    public String id() { return "spawned.await"; }

    @Override
    public float order() { return 950; }

    @Override
    public Object invoke(TaskLifecycleContext ctx, TaskChain next) throws Exception {
        // 下行:重置 per-task 子 agent 状态(清除上一轮 stopAll 置位的 stopRequested),
        // 否则同一任务的后续轮次 run_agent 会被误拒("任务已停止,未启动子 agent")。
        // 用 taskId 而非 taskInfo():下行阶段 TaskEntry 可能尚未创建(由 order=50
        // 的 TaskEntryCreateNode 在内层创建)。对于再运行(rerun)场景,taskId 在
        // 生命周期启动前已由 TaskManager 设置,此时即可安全重置上一轮的 stopRequested。
        String tid = ctx.taskId();
        if (tid != null && !tid.isEmpty()) {
            subs.resetForRun(tid);
        }

        Object result = next.proceed(ctx);
        TaskRuntime t = (TaskRuntime) ctx.taskInfo();
        try {
            subs.awaitAllBeforeFinish(t);
        } catch (RuntimeException e) {
            log.warn("awaitAllBeforeFinish 异常 task={}", ctx.taskId(), e);
        }
        if (Thread.currentThread().isInterrupted()) {
            TaskOutcome to = (TaskOutcome) result;
            return new TaskOutcome(
                TaskOutcome.TaskEndStatus.CANCELLED, null,
                to.startedAt(), System.currentTimeMillis());
        }
        return result;
    }
}
