package dev.everyagent.plugin.unattended;

import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.plugin.api.spi.ToolExecutionChain;
import dev.everyagent.plugin.api.spi.ToolExecutionContext;
import dev.everyagent.plugin.api.spi.ToolExecutionInterceptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.util.JacksonUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

public class UnattendedToolInterceptor implements ToolExecutionInterceptor {

    private static final ObjectMapper MAPPER = JacksonUtils.getDefaultJsonMapper();

    public UnattendedToolInterceptor() {
    }

    @Override
    public String id() { return "unattended-tool-interceptor"; }

    @Override
    public float order() { return 100f; }

    @Override
    public ToolExecutionResult invoke(ToolExecutionContext ctx, ToolExecutionChain next) throws Exception {
        ExecContext task = ctx.execution();
        if (task == null
                || !Boolean.TRUE.equals(task.metadata().getOrDefault("unattended", false))) {
            return next.proceed(ctx); // 不是无人值守模式，放行
        }
        Prompt prompt = ctx.prompt();
        ChatResponse chatResponse = ctx.chatResponse();
        List<AssistantMessage.ToolCall> toolCalls = ctx.toolCalls();
        // 检查本轮是否含 ask_user 调用
        List<AssistantMessage.ToolCall> askUserCalls = toolCalls.stream()
                .filter(tc -> "ask_user".equals(tc.name()))
                .toList();
        if (askUserCalls.isEmpty()) {
            return next.proceed(ctx); // 本轮无 ask_user 调用，放行
        }
        // 找到 assistant 消息
        AssistantMessage assistant = chatResponse.getResults().stream()
                .filter(g -> g.getOutput() != null && g.getOutput().hasToolCalls())
                .map(Generation::getOutput)
                .findFirst()
                .orElse(null);
        if (assistant == null) {
            return next.proceed(ctx);
        }
        // 为每个 ask_user 调用合成自动回答
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
        for (AssistantMessage.ToolCall tc : toolCalls) {
            if ("ask_user".equals(tc.name())) {
                String autoAnswer = autoAnswer(tc.arguments());
                responses.add(new ToolResponseMessage.ToolResponse(tc.id(), tc.name(), autoAnswer));
            }
            // 非 ask_user 的工具调用不在拦截器处理——如果本轮混合了 ask_user 和其他工具，
            // 简单方案是只合成 ask_user 的结果，其他工具需真实执行。
            // 但 ToolExecutionResult 是整轮的，无法部分短路。
            // 最简单方案：如果本轮含 ask_user 且有其他工具，不拦截（放行让真实执行）。
        }
        // 如果本轮只有 ask_user 调用（纯 ask_user 轮），短路返回
        if (responses.size() == toolCalls.size()) {
            ToolResponseMessage msg = ToolResponseMessage.builder().responses(responses).build();
            List<Message> history = new ArrayList<>(prompt.getInstructions());
            history.add(assistant);
            history.add(msg);
            return ToolExecutionResult.builder()
                    .conversationHistory(history)
                    .returnDirect(false)
                    .build();
        }
        // 混合轮（ask_user + 其他工具）：放行让真实执行
        return next.proceed(ctx);
    }

    private static String autoAnswer(String toolInput) {
        try {
            JsonNode root = MAPPER.readTree(toolInput);
            JsonNode questions = root.path("questions");
            if (!questions.isArray() || questions.isEmpty()) {
                return "未提供任何问题,跳过提问。";
            }
            List<String> lines = new ArrayList<>();
            for (JsonNode q : questions) {
                String question = textOrEmpty(q.path("question"));
                JsonNode options = q.path("options");
                String firstOption = options.isArray() && !options.isEmpty()
                        ? textOrEmpty(options.get(0)) : "";
                if (!question.isEmpty() && !firstOption.isEmpty()) {
                    lines.add(question + "：" + firstOption);
                }
            }
            if (lines.isEmpty()) {
                return "未提供任何有效问题,跳过提问。";
            }
            return String.join("\n", lines);
        } catch (Exception e) {
            return "问题解析失败,跳过提问。";
        }
    }

    private static String textOrEmpty(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        if (node.isValueNode()) {
            return node.asText("");
        }
        if (node.isObject()) {
            for (String key : new String[]{"text", "label", "name", "value", "title", "option", "content"}) {
                JsonNode v = node.get(key);
                if (v != null && v.isValueNode()) {
                    return v.asText("");
                }
            }
        }
        return node.toString();
    }
}
