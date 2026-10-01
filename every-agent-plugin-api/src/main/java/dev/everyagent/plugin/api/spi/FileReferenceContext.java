package dev.everyagent.plugin.api.spi;

import java.nio.file.Path;

/**
 * 文件引用处理上下文 —— {@link FileReferenceHandler#process} 的 per-任务参数。
 *
 * <p>只暴露任务级只读信息；读取文件等能力由插件经
 * {@link dev.everyagent.plugin.api.WorkerPluginContext#services()} 获取
 * （jailed 路径校验、PermissionGate 授权链由 handler 自行走既有通道）。
 */
public interface FileReferenceContext {

    /** 任务 ID。 */
    String taskId();

    /** 工作区稳定 ID。 */
    String workspaceId();

    /** 工作区根路径（任务挂靠的工作区目录）。 */
    Path workspaceRoot();
}
