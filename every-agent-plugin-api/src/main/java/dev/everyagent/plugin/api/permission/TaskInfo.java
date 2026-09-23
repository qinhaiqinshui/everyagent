package dev.everyagent.plugin.api.permission;

import java.util.Map;

/**
 * 任务信息最小接口 —— 授权 SPI 用此替代对 worker {@code TaskEntry} 的直接引用。
 *
 * <p>worker 的 {@code TaskEntry} 实现此接口。
 * 需要完整任务运行时数据的内置组件可直接依赖 {@code TaskEntry} 具体类。
 */
public interface TaskInfo {

    /** 任务 ID。 */
    String taskId();

    /**
     * 通用任务级标记存储（替代原 aiReview/unattended 布尔字段）。
     * 插件用字符串 key 存取（如 "ai-review"、"unattended"），核心不感知具体 key。
     */
    Map<String, Boolean> taskFlags();
}
