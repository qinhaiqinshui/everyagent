package dev.everyagent.plugin.api;

import dev.everyagent.plugin.api.interaction.InteractionService;
import dev.everyagent.plugin.api.spi.SandboxBackend;
import dev.everyagent.plugin.api.spi.TokenEstimator;
import dev.everyagent.plugin.api.spi.WorkspaceManager;
import dev.everyagent.plugin.api.task.TaskService;

/**
 * Worker 核心只读服务 —— 插件经此访问 worker 的公共能力。
 *
 * <p>对标 VSCode 的 {@code vscode.*} 命名空间——插件不直接依赖具体实现类，
 * 只经此接口访问核心服务。
 */
public interface WorkerServices {

    /** 沙箱门面（SandboxBackend，插件可委托挂载与生命周期管理）。 */
    SandboxBackend sandbox();

    /** 工作区管理器（多工作区注册表）。 */
    WorkspaceManager workspaces();

    /** Token 估算器（内置或插件注册的自定义实现）。 */
    TokenEstimator tokenEstimator();

    /** 任务服务（查询任务信息、广播 task.updated）。 */
    TaskService task();

    /** 用户交互服务（向用户发起提问/授权，同步或异步）。 */
    InteractionService interaction();
}
