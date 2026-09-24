package dev.everyagent.plugin.api.task;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 任务生命周期上下文（节点的读写面）。
 * <p>外部插件只见此窄接口；内置节点在 worker 侧拿完整 TaskEntry（内部通道）。
 */
public interface TaskLifecycleContext {

    /** 任务 ID。 */
    String taskId();

    /** 任务标题。 */
    String title();

    /** 工作区根路径。 */
    String workspaceRoot();

    /** 工作区稳定 ID。 */
    String workspaceId();

    /** 主 agent 稳定 ID。 */
    String mainAgentId();

    /** 当前任务状态（wire 字符串："created"/"running"/"waiting-user"/"done"/"failed"/"cancelled"）。 */
    String status();

    /** 任务同步原语（锁内节点上行段自行 synchronized）。 */
    Object taskLock();

    /** 通用任务级标记存储（与 TaskInfo.taskFlags() 同源）。 */
    Map<String, Boolean> taskFlags();

    /** 任务开始时间戳（由 status.start 节点写入）。 */
    long startedAt();

    /** 设置任务开始时间戳。 */
    void startedAt(long ms);

    /**
     * 注入 usage 实时广播钩子（由 task.wires 节点调用）。
     * 钩子被 WorkerToolEventAdvisor 在每轮 usage 后触发。
     */
    void onUsageBroadcast(Runnable hook);

    /**
     * 发射 agent 状态事件（status.start/status.finalize 节点用）。
     * @param agentId agent ID
     * @param status 状态字符串（如 "running"/"done"/"failed"/"stopped"）
     */
    void agentStatus(String agentId, String status);
}
