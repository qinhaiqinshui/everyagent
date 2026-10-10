package dev.everyagent.plugin.api.task;

/**
 * 任务准入策略（RPC 边缘预检扩展点）。
 * <p>队列插件可注册此策略接管 {@code rpcTaskRun} 的并发上限检查逻辑。
 * 默认无注册策略时，TaskManager 保持原有行为：active >= max → ERR_BUSY。
 * 注册策略后，策略 always-admit（任务进入洋葱 QueueAdmissionNode order=250 排队等待）。
 */
public interface TaskAdmissionPolicy {
    /**
     * 检查是否准入该任务。
     * @param taskId 待创建的任务 ID
     * @param maxConcurrentTasks 配置的最大并发数
     * @return 准入结果
     */
    AdmissionResult check(String taskId, int maxConcurrentTasks);
}
