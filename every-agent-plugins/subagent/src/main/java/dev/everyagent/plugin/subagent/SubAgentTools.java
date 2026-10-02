package dev.everyagent.plugin.subagent;

import dev.everyagent.plugin.api.exception.AgentCancelledException;
import dev.everyagent.plugin.api.execution.ExecContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * subagent 工具集:绑定 {@link ExecContext} 整个上下文句柄(工具调用时由
 * {@code ToolContext} 传入,本身即 ExecContext),不再以 taskId 反查任务服务(§8.2)。
 */
public class SubAgentTools {

    private final SubAgentManager subAgentManager;
    private final ExecContext ctx;

    public SubAgentTools(SubAgentManager subAgentManager, ExecContext ctx) {
        this.subAgentManager = subAgentManager;
        this.ctx = ctx;
    }

    @Tool(description = "派生一个子 agent 去完成一项独立子任务。子 agent 有独立上下文,看不到当前对话。"
            + "异步运行,立即返回 agentId;必须再用 wait_agents 等待完成并收集结果。若想同时运行多个Agent,可并行派发多个run_agent。")
    public String run_agent(
            @ToolParam(description = "交给子 agent 的完整任务描述(需自包含,不能引用本对话内容)") String input,
            @ToolParam(description = "简短标题", required = false) String title,
            @ToolParam(description = "指定复用的 agentId", required = false) String agentId) {
        try {
            return subAgentManager.run(ctx, input, title, agentId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentCancelledException("task cancelled");
        }
    }

    @Tool(description = "列出当前任务下全部子 agent,返回 JSON {agents:[{agentId,title,createdAt,status,latestActivity}]};"
            + "status 终态为 completed/stopped/error,running 运行中,waiting-user 表示该子 agent 挂起等待用户回答;"
            + "latestActivity 是最近一次活动快照(reasoning/content/error/createdAt/updatedAt),不含完整历史。")
    public String list_agents() {
        try {
            return subAgentManager.list(ctx);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentCancelledException("task cancelled");
        }
    }

    @Tool(description = "等待子 agent 完成并返回 JSON:传 agentId 返回 {mode:\"single\",waitStatus,agent};"
            + "省略则等待全部未落定子 agent,返回 {mode:\"list\",waitStatus,agents}。"
            + "waitStatus=timeout 表示本次等待超时,仍返回最新摘要供判断进度。")
    public String wait_agents(
            @ToolParam(description = "要等待的子 agent agentId(可选,来自 list_agents;省略则等待全部)", required = false) String agentId,
            @ToolParam(description = "超时毫秒(可选,默认 30000)", required = false) Long timeoutMs) {
        try {
            return subAgentManager.waitFor(ctx, agentId, timeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentCancelledException("task cancelled");
        }
    }

    @Tool(description = "停止一个子 agent。")
    public String stop_agent(
            @ToolParam(description = "要停止的 agentId") String agentId) {
        return subAgentManager.stop(ctx, agentId);
    }
}
