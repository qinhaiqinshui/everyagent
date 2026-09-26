package dev.everyagent.plugin.api.task;

import dev.everyagent.plugin.api.permission.TaskInfo;

/**
 * 任务服务 —— 插件经 {@link dev.everyagent.plugin.api.WorkerServices#task()} 访问。
 *
 * <p>插件可按 taskId 查询任务信息（{@link TaskInfo}），
 * 并在任务数据变化时通知前端（{@code publishUpdated} 触发 {@code task.updated} 广播）。
 */
public interface TaskService {

    /** 获取任务信息（内存运行中或磁盘终态；不存在返回 null）。 */
    TaskInfo get(String taskId);

    /** 广播 task.updated（运行中用内存 summary，磁盘终态用磁盘 summary）。 */
    void publishUpdated(String taskId);
}
