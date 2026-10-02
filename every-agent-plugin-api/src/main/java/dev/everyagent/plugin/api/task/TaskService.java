package dev.everyagent.plugin.api.task;

/**
 * 任务服务 —— 插件经 {@link dev.everyagent.plugin.api.WorkerServices#task()} 访问。
 *
 * <p>插件可按 taskId 查询任务运行时（{@link TaskRuntime}，内存运行中任务），
 * 或查询磁盘终态任务条目（{@link StoredTaskInfo}），
 * 并在任务数据变化时通知前端（{@code publishUpdated} 触发 {@code task.updated} 广播）。
 */
public interface TaskService {

    /**
     * 获取运行中任务运行时（内存驻留任务；不存在/已终态驱逐返回 null）。
     * <p>返回 {@link TaskRuntime}（继承 {@link dev.everyagent.plugin.api.execution.ExecContext}），插件可访问 agents / events / log 等运行时能力。
     */
    TaskRuntime get(String taskId);

    /** 磁盘任务索引条目（终态任务的磁盘路由索引；不存在返回 null）。 */
    StoredTaskInfo diskEntry(String taskId);

    /** 广播 task.updated（运行中用内存 summary，磁盘终态用磁盘 summary）。 */
    void publishUpdated(String taskId);

    /**
     * 重新挂接流推送源（DataPusher 据此实时推送事件）。
     * <p>队列续跑场景：QueueLoopNode 的 next.proceed 内层链在上行段执行 persistence.untrack
     * 摘除流源，但 persistence.track 在 queue.loop 之前、不随续跑重入。
     * 续跑前调用此方法重新挂接，否则第二轮事件只落盘不实时推送（前端延迟到 task.poll 补齐）。
     */
    void reattachStream(String taskId);
}
