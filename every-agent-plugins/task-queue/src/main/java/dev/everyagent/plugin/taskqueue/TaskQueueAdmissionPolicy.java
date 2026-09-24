package dev.everyagent.plugin.taskqueue;

import dev.everyagent.plugin.api.task.AdmissionResult;
import dev.everyagent.plugin.api.task.TaskAdmissionPolicy;

/**
 * 队列插件准入策略：always-admit。
 * <p>队列插件接管准入后，所有任务都被允许进入洋葱模型，
 * 并发控制由 QueueAdmissionNode(order=250) 的 Semaphore 排队处理，
 * 而非在 RPC 边缘硬拒绝 ERR_BUSY。
 */
public class TaskQueueAdmissionPolicy implements TaskAdmissionPolicy {

    @Override
    public AdmissionResult check(String taskId, int maxConcurrentTasks) {
        return AdmissionResult.admit();
    }
}
