package dev.everyagent.plugin.api;

import java.nio.file.Path;

import dev.everyagent.plugin.api.config.WorkerConfig;
import dev.everyagent.plugin.api.event.StreamEmitter;
import dev.everyagent.plugin.api.interaction.InteractionService;
import dev.everyagent.plugin.api.model.EventEmitter;
import dev.everyagent.plugin.api.spi.IdGenerator;
import dev.everyagent.plugin.api.spi.NativeExec;
import dev.everyagent.plugin.api.spi.SandboxBackend;
import dev.everyagent.plugin.api.spi.TokenEstimator;
import dev.everyagent.plugin.api.spi.WorkspaceManager;
import dev.everyagent.plugin.api.task.TaskServices;

/**
 * Worker 核心只读服务 —— 插件经此访问 worker 的公共能力。
 *
 * <p>对标 VSCode 的 {@code vscode.*} 命名空间——插件不直接依赖具体实现类，
 * 只经此接口访问核心服务。
 *
 * <p>继承 {@link TaskServices} 以提供 task 域服务（{@code task()} / {@code store()}）。
 * 不需要 task 功能的插件只面向基础 {@code WorkerServices} 编程，
 * 不会被被迫传递性 import task 域类型。
 */
public interface WorkerServices extends TaskServices {

    /** 沙箱门面（SandboxBackend，插件可委托挂载与生命周期管理）。 */
    SandboxBackend sandbox();

    /** 宿主原生进程执行器（argv 直传 + 超时 + 输出上限；git 等平台受控操作用）。 */
    NativeExec nativeExec();

    /** 工作区管理器（多工作区注册表）。 */
    WorkspaceManager workspaces();

    /** Token 估算器（内置或插件注册的自定义实现）。 */
    TokenEstimator tokenEstimator();

    /** 用户交互服务（向用户发起提问/授权，同步或异步）。 */
    InteractionService interaction();

    /** Worker 配置只读视图（插件面向 WorkerConfig 接口编程）。 */
    WorkerConfig config();

    /** ID 生成器（单调递增 long ID + 短 ID）。 */
    IdGenerator ids();

    /** 事件扇出口（向 hub 连接广播事件）。 */
    StreamEmitter stream();

    /**
     * 获取指定 subject（任务）的数据目录（落盘根）。
     *
     * <p>用于底层组件（如 slash 自管存储）直接读写自己的文件，
     * 不经 TaskStore / meta.json。
     *
     * @param subjectId 任务 ID
     * @return 数据目录 Path；任务不存在或终态清理后返回 null
     */
    Path dataDirOf(String subjectId);

    /**
     * 获取指定 subject（任务）的事件发射器。
     *
     * <p>运行中任务返回有效 emitter（经 EventLog → DataPusher → stream 频道广播）；
     * 终态任务返回 null（不广播，调用方应跳过 emit）。
     *
     * @param subjectId 任务 ID
     * @return EventEmitter 或 null（终态）
     */
    EventEmitter emitterOf(String subjectId);

    /**
     * 注册轮闭合监听器（插件在 activate 时调用）。
     *
     * <p>当 RoundIndexStore 持久化新闭合轮后，会回调所有已注册的监听器，
     * 传递 taskId、dataDir 和闭合轮信息列表。插件可据此写入按轮分片的数据文件。
     */
    void addRoundClosedListener(dev.everyagent.plugin.api.task.RoundClosedListener listener);
}
