package dev.everyagent.plugin.api;

import dev.everyagent.plugin.api.agent.AgentFactory;
import dev.everyagent.plugin.api.config.WorkerConfig;
import dev.everyagent.plugin.api.event.StreamEmitter;
import dev.everyagent.plugin.api.interaction.InteractionService;
import dev.everyagent.plugin.api.spi.IdGenerator;
import dev.everyagent.plugin.api.spi.NativeExec;
import dev.everyagent.plugin.api.spi.SandboxBackend;
import dev.everyagent.plugin.api.spi.TokenEstimator;
import dev.everyagent.plugin.api.spi.WorkspaceManager;
import dev.everyagent.plugin.api.task.TaskService;
import dev.everyagent.plugin.api.task.TaskStoreService;

/**
 * Worker 核心只读服务 —— 插件经此访问 worker 的公共能力。
 *
 * <p>对标 VSCode 的 {@code vscode.*} 命名空间——插件不直接依赖具体实现类，
 * 只经此接口访问核心服务。
 */
public interface WorkerServices {

    /** 沙箱门面（SandboxBackend，插件可委托挂载与生命周期管理）。 */
    SandboxBackend sandbox();

    /** 宿主原生进程执行器（argv 直传 + 超时 + 输出上限；git 等平台受控操作用）。 */
    NativeExec nativeExec();

    /** 工作区管理器（多工作区注册表）。 */
    WorkspaceManager workspaces();

    /** Token 估算器（内置或插件注册的自定义实现）。 */
    TokenEstimator tokenEstimator();

    /** 任务服务（查询任务信息、广播 task.updated）。 */
    TaskService task();

    /** 任务落盘服务（队列读写、截断、meta 读写、会话重建等）。 */
    TaskStoreService store();

    /** 用户交互服务（向用户发起提问/授权，同步或异步）。 */
    InteractionService interaction();

    /** Worker 配置只读视图（插件面向 WorkerConfig 接口编程）。 */
    WorkerConfig config();

    /** ID 生成器（单调递增 long ID + 短 ID）。 */
    IdGenerator ids();

    /** 事件扇出口（向 hub 连接广播事件）。 */
    StreamEmitter stream();

    /** Agent 工厂（创建 agent 装配会话）。 */
    AgentFactory agentFactory();
}
