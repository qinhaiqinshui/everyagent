package dev.everyagent.plugin.api.task;

import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Path;

/**
 * 磁盘任务索引条目（终态任务的只读快照）—— 插件用此替代对 worker
 * {@code TaskStore.StoredTask} 的直接引用。
 *
 * <p>worker 的 {@code TaskStore.StoredTask} 实现此接口。
 */
public interface StoredTaskInfo {

    /** 任务 ID。 */
    String taskId();

    /** 任务数据目录路径。 */
    Path dir();

    /** 任务摘要（meta.json 解析结果，可变 ObjectNode 共享引用，只读）。 */
    ObjectNode summary();

    /** 工作区稳定 ID（磁盘存储维度）。 */
    String workspaceId();
}
