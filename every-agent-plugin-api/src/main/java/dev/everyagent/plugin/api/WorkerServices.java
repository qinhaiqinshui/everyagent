package dev.everyagent.plugin.api;

import dev.everyagent.plugin.api.spi.SandboxBackend;
import dev.everyagent.plugin.api.spi.PermissionGate;
import dev.everyagent.plugin.api.spi.WorkspaceManager;

/**
 * Worker 核心只读服务 —— 插件经此访问 worker 的公共能力。
 *
 * <p>对标 VSCode 的 {@code vscode.*} 命名空间——插件不直接依赖具体实现类，
 * 只经此接口访问核心服务。
 */
public interface WorkerServices {

    /** 沙箱门面（SandboxBackend，插件可委托命令执行）。 */
    SandboxBackend sandbox();

    /** 权限门（PermissionGate，工具经此授权链）。 */
    PermissionGate gate();

    /** 工作区管理器（多工作区注册表）。 */
    WorkspaceManager workspaces();
}
