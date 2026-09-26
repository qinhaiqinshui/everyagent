package dev.everyagent.plugin.api.permission;

import java.nio.file.Path;
import java.util.Map;

/**
 * 任务信息接口 —— 授权 SPI 用此替代对 worker {@code TaskEntry} 的直接引用。
 *
 * <p>worker 的 {@code TaskEntry} 实现此接口。
 * 需要完整任务运行时数据的内置组件可直接依赖 {@code TaskEntry} 具体类。
 */
public interface TaskInfo {

    /** 任务 ID。 */
    String taskId();

    /**
     * 任务状态（wire 字符串：{@code "created"}/{@code "running"}/{@code "waiting-user"}
     * /{@code "done"}/{@code "failed"}/{@code "cancelled"}）。
     */
    String status();

    /** 语法糖：status 是否为终态。 */
    boolean terminal();

    /**
     * 任务级持久化数据（替代原 taskFlags）。
     * <p>插件用字符串 key 存取任意类型值（如 {@code "ai-review"}→Boolean、
     * {@code "unattended"}→Boolean），核心不感知具体 key/value 类型。
     * 随任务 meta.json 落盘、再运行仍保持。
     */
    Map<String, Object> metadata();

    /** 任务数据目录路径（workspaces/&lt;workspaceId&gt;/tasks/&lt;taskId&gt;/）。 */
    Path taskDir();
}
