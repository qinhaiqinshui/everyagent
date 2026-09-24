package dev.everyagent.worker.agent;

import dev.everyagent.plugin.api.agent.AgentContext;
import dev.everyagent.worker.task.AgentEntity;

/**
 * Agent 层公共 API。
 * <p>主 agent 和子 agent 的调用者（task 层、subagent 插件、其他插件）共用此接口。
 * Phase 1 内核直接调 AgentRunner.run；Phase 2 换为调 AgentService.run（同一入口）。
 */
public interface AgentService {

    /**
     * 同步运行单个 agent 至最终回答（现 AgentRunner.run，主/子共用同一入口不变）。
     */
    void run(AgentEntity a) throws InterruptedException;

    /**
     * 异步派生子 agent（现 SubAgentManager.run 核心逻辑下沉）。
     * @param ctx 任务上下文（AgentContext，不触 TaskEntry）
     * @param input 子 agent 输入
     * @param title 子 agent 标题
     * @param reuseAgentId 复用已有 agentId（续跑），null=新建
     * @return 结果文本（给主 agent 的）
     */
    String spawn(AgentContext ctx, String input, String title, String reuseAgentId) throws InterruptedException;

    /**
     * 等待子 agent 完成。
     * @param ctx 任务上下文
     * @param agentId 指定 agent（null=等待全部）
     * @param timeoutMs 超时毫秒
     * @return 结果文本（JSON 格式，给主 agent 的）
     */
    String waitFor(AgentContext ctx, String agentId, Long timeoutMs) throws InterruptedException;

    /**
     * 停止指定子 agent。
     */
    String stop(AgentContext ctx, String agentId);

    /**
     * 停止全部子 agent。
     */
    void stopAll(AgentContext ctx);

    /**
     * 列出活跃子 agent。
     * @return 结果文本（JSON 格式，给主 agent 的）
     */
    String list(AgentContext ctx) throws InterruptedException;
}
