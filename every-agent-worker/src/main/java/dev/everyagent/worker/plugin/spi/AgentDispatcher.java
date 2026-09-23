package dev.everyagent.worker.plugin.spi;

/**
 * 子 agent 调度策略 SPI —— 插件实现此接口提供不同调度方案。
 *
 * <p>现有进程内虚拟线程方案变为默认插件（dispatcher-in-process）。
 * 社区可开发 dispatcher-docker（每子 agent 一个容器）、
 * dispatcher-remote（跨机分工，走 cmd 频道）。
 *
 * <p>改造前：SubAgentManager + SubAgentTools 硬编码为进程内方案。
 * 改造后：SubAgentManager 从 {@link dev.everyagent.worker.plugin.registry.AgentDispatcherRegistry} 选择调度策略。
 *
 * <p>注意：此 SPI 在阶段一仅定义接口，实际改造在阶段三（内置功能拆分时）。
 */
public interface AgentDispatcher {

    /** 策略 id（"in-process"/"docker"/"remote"）。 */
    String id();

    /**
     * 派生子 agent。
     *
     * @param req 派发请求
     * @return agent 句柄
     */
    AgentHandle dispatch(DispatchRequest req);

    /**
     * 等待子 agent 完成。
     *
     * @param handle agent 句柄
     * @param timeoutMs 等待超时（毫秒）
     * @return agent 结果
     */
    AgentResult waitFor(AgentHandle handle, long timeoutMs);

    /**
     * 停止子 agent。
     */
    void stop(AgentHandle handle);

    /**
     * 列出活跃子 agent。
     */
    java.util.List<AgentInfo> list();

    /** 子 agent 句柄。 */
    record AgentHandle(String agentId, String title) {}

    /** 派发请求。 */
    record DispatchRequest(String taskId, String agentId, String title, String input,
            String workspaceRoot, String modelConfigId) {}

    /** 子 agent 结果。 */
    record AgentResult(String agentId, String output, boolean success, String error) {}

    /** 子 agent 信息。 */
    record AgentInfo(String agentId, String title, String status, String model,
            long createdAt, long totalTokens) {}
}
