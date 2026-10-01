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

    /** 任务运行时（TaskEntry 的窄面：可访问任务级持久化 metadata 及运行时能力；早期节点尚未创建任务时为 null）。 */
    TaskRuntime taskInfo();

    /** 任务同步原语（锁内节点上行段自行 synchronized）。 */
    Object taskLock();

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

    /** task.run 的用户输入文本（首条输入，main.agent 节点消费）。 */
    String input();

    /** task.run 的原始内容（含 opaque token，供前端回放还原）。 */
    String rawContent();

    /** task.run 的通用插件参数容器（核心不解释，插件自行消费）。 */
    java.util.Map<String, Object> runParams();

    /**
     * 任务级持久化数据（便捷方法，委托给 {@link #taskInfo()}).
     * @return 任务运行时的 metadata Map，或 taskInfo() 为 null 时返回 null
     */
    default java.util.Map<String, Object> metadata() {
        TaskRuntime info = taskInfo();
        return info != null ? info.metadata() : null;
    }

    /** RPC 应答器（链节点直接调 ctx.ok 返回前端；仅 RPC 线程阶段有效，虚拟线程阶段为 null）。 */
    Object rpcContext();

    // ---- 可写 setter（队列循环节点在 poll 后把队列项数据设到当前 ctx）----

    /** 设置用户输入文本（队列项 poll 后覆盖当前 ctx 的 input）。 */
    void input(String input);

    /** 设置原始内容（队列项 poll 后覆盖当前 ctx 的 rawContent）。 */
    void rawContent(String rawContent);

    /**
     * 设置通用插件参数容器（队列项 poll 后覆盖当前 ctx 的 runParams；null 忽略）。
     * task.run 的 metadata 参数是一次性插件参数（如 editSeq/insert），经此传递，不落盘。
     */
    void runParams(java.util.Map<String, Object> runParams);

    /** 设置 metadata（队列项 poll 后覆盖当前 ctx 的 metadata；null 或 taskEntry 为 null 时忽略）。 */
    void metadata(java.util.Map<String, Object> metadata);

    // ---- 运行时访问 ----

    /**
     * 获取任务运行时（与 {@link #taskInfo()} 同一对象的语义别名，保留既有插件调用点）。
     * <p>插件可访问 agents / events / log / fileChanges 等运行时能力。
     * taskEntry 尚未创建时返回 null（RPC 阶段早期节点）。
     */
    default TaskRuntime taskRuntime() {
        return taskInfo();
    }
}
