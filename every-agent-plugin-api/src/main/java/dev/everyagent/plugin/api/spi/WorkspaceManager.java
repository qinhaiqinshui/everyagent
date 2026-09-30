package dev.everyagent.plugin.api.spi;

import java.nio.file.Path;
import java.util.List;

/**
 * 工作区管理器最小接口 —— 插件经 {@link ToolContext#workspaces()} 或 {@code WorkerServices.workspaces()} 访问。
 *
 * <p>worker 的 {@code dev.everyagent.worker.modules.WorkspaceManager} 实现此接口。
 * 需要完整工作区注册表能力的内置组件可直接依赖 worker 具体类。
 */
public interface WorkspaceManager {

    /** 按 root（规范化键）查注册表返回稳定 workspaceId；未注册返回 null。 */
    String idOfRoot(String root);

    /** 系统目录绝对路径。 */
    Path systemDir();

    /** 默认工作区根路径。 */
    Path defaultRoot();

    /**
     * 清理注册表中已失效的工作区根（目录不存在），返回当前全部存活挂载根路径列表。
     *
     * <p>沙箱插件（如 wsl-ubuntu）经此获取全部工作区的宿主路径，用于批量挂载进发行版。
     *
     * @return 存活工作区根路径列表（可能为空）
     */
    List<Path> pruneStaleAndListMountRoots();
}
