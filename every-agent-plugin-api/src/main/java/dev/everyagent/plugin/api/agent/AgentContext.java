package dev.everyagent.plugin.api.agent;

/**
 * Agent 层需要的任务面（窄接口）。
 * <p>worker 的 {@code TaskEntry} 实现此接口；agent 层只依赖此接口，
 * 不再强引用 {@code TaskEntry} 具体类。
 * 外部插件也只见此接口（非 worker 内部通道）。
 */
public interface AgentContext {

    /** 任务 ID。 */
    String taskId();

    /** 工作区根路径。 */
    String workspaceRoot();

    /**
     * 事件发射端口：内存 EventLog append；落盘由 task 层 TaskStore 承接。
     * agent 层通过此端口发射所有事件（delta/thinking/message/usage/toolResult/agent 系列与 error），
     * 不直接引用 TaskEvents。
     */
    AgentEventChannel events();

    /**
     * 任务是否已终态（事件泄漏防御用）。
     * 等价于 {@code task.status.terminal()}。
     */
    boolean taskTerminal();

    /**
     * 记录主 agent 最近一轮实测 usage（原 TaskEntry.recordUsage + onUsageBroadcast）。
     * 内存记录，meta 落盘在 task 层。
     * @param round 本轮 usage
     * @param contextWindowTokens 上下文窗口上限
     * @param model 模型名
     */
    void recordMainUsage(Object round, Long contextWindowTokens, String model);
}
