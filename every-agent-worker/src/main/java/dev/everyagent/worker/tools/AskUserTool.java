package dev.everyagent.worker.tools;

import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.task.AgentCancelledException;
import dev.everyagent.worker.task.PendingAsks;
import dev.everyagent.worker.task.TaskEntry;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.ArrayList;
import java.util.List;

/**
 * ask_user 工具:主/子 agent 均可用(架构 §5.6)。agentId 恒为真实 Id
 * (主 = task.mainAgentId,子 = 子 agent Id),事件与 jsonl 路由统一用它。
 *
 * <p>一次可问多个问题,每题均为单选题(option 形式);前端渲染时每题自动追加一个
 * 「其他」选项,选中后由用户自由输入。工具返回文本即「题干：答案」逐题拼接,
 * 直接作为 tool.result 写回模型上下文。
 */
public class AskUserTool {

    /** LLM 传入的单题结构:题干 + 候选选项(只支持选择题)。 */
    public record AskQuestionInput(
            @ToolParam(description = "问题文本,清晰具体") String prompt,
            @ToolParam(description = "候选选项列表(至少 1 个;前端会自动追加「其他」选项)",
                    required = false) List<String> options) {
    }

    private final PendingAsks asks;
    private final WorkerProperties props;
    private final TaskEntry task;
    private final String agentId;

    public AskUserTool(PendingAsks asks, WorkerProperties props, TaskEntry task, String agentId) {
        this.asks = asks;
        this.props = props;
        this.task = task;
        this.agentId = agentId;
    }

    @Tool(description = "向用户一次性提出多个选择题并等待回答。当任务信息不足、需要用户抉择时使用。"
            + "每题都是单选题,系统会自动为每题追加一个「其他」选项(选中后由用户自由输入)。")
    public String ask_user(
            @ToolParam(description = "要问用户的问题列表,每题含题干 prompt 与候选选项 options;支持一次问多个") List<AskQuestionInput> questions) {
        try {
            if (questions == null || questions.isEmpty()) {
                return "未提供任何问题,跳过提问。";
            }
            // 题目 id 由 PendingAsks.ask 以真实 askId 派生(askId_i),此处传占位 id。
            List<PendingAsks.AskQuestion> built = new ArrayList<>();
            for (AskQuestionInput q : questions) {
                String prompt = q.prompt() == null ? "" : q.prompt();
                List<String> opts = q.options() == null ? List.of() : q.options();
                built.add(new PendingAsks.AskQuestion("", prompt, opts));
            }
            PendingAsks.AskAnswer ans = asks.ask(task.events, task.taskId, agentId,
                    "question", built, props.getLimits().getAskTimeoutMs());
            return switch (ans.status()) {
                case "answered" -> ans.text();
                case "timeout" -> "用户未在规定时间内回答(已超时)。请基于现有信息继续,并明确告知用户未获得答复。";
                case "cancelled" -> "提问已被取消。";
                default -> "提问异常结束。";
            };
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentCancelledException("task cancelled");
        }
    }
}
