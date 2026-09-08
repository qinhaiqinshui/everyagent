package dev.everyagent.worker.tools;

import dev.everyagent.worker.task.SubAgentManager;
import dev.everyagent.worker.task.TaskEntry;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 主 agent 专属的子 agent 工具集(架构 §5.6)。子 agent 的工具集不含本类(禁递归)。
 */
public class SubAgentTools {

    private final SubAgentManager subs;
    private final TaskEntry task;

    public SubAgentTools(SubAgentManager subs, TaskEntry task) {
        this.subs = subs;
        this.task = task;
    }

    @Tool(description = "派生一个子 agent 去完成一项独立子任务。子 agent 有独立上下文,看不到当前对话。"
            + "默认阻塞等待其完成后返回结论;设 blocking=false 则异步运行。若想同时运行多个Agent，必须使用异步模式，否则多个run_agent工具将会串行运行。")
    public String run_agent(
            @ToolParam(description = "交给子 agent 的完整任务描述(需自包含,不能引用本对话内容)") String input,
            @ToolParam(description = "简短标题", required = false) String title,
            @ToolParam(description = "指定复用的 agentId", required = false) String agentId,
            @ToolParam(description = "是否阻塞等待完成,默认 true", required = false) Boolean blocking) {
        try {
            return subs.run(task, input, title, agentId, blocking == null || blocking);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new dev.everyagent.worker.task.AgentCancelledException("task cancelled");
        }
    }

    @Tool(description = "列出当前任务下全部子 agent,返回 JSON {agents:[{agentId,title,createdAt,status,latestActivity}]};"
            + "status 终态为 completed/stopped/error,running 运行中,waiting-user 表示该子 agent 挂起等待用户回答;"
            + "latestActivity 是最近一次活动快照(reasoning/content/error/createdAt/updatedAt),不含完整历史。")
    public String list_agents() {
        try {
            return subs.list(task);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new dev.everyagent.worker.task.AgentCancelledException("task cancelled");
        }
    }

    @Tool(description = "等待子 agent 完成并返回 JSON:传 agentId 返回 {mode:\"single\",waitStatus,agent};"
            + "省略则等待全部未落定子 agent,返回 {mode:\"list\",waitStatus,agents}。"
            + "waitStatus=timeout 表示本次等待超时,仍返回最新摘要供判断进度。")
    public String wait_agents(
            @ToolParam(description = "要等待的子 agent agentId(可选,来自 list_agents;省略则等待全部)", required = false) String agentId,
            @ToolParam(description = "超时毫秒(可选,默认 30000)", required = false) Long timeoutMs) {
        try {
            return subs.waitFor(task, agentId, timeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new dev.everyagent.worker.task.AgentCancelledException("task cancelled");
        }
    }

    @Tool(description = "停止一个子 agent。")
    public String stop_agent(
            @ToolParam(description = "要停止的 agentId") String agentId) {
        return subs.stop(task, agentId);
    }
}
