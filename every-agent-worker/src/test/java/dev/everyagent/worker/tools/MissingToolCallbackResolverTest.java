package dev.everyagent.worker.tools;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MissingToolCallbackResolver 回归测试(修复「No ToolCallback found for tool name: X」
 * 中断任务的问题):模型调用本 agent 未注册的工具名时,经
 * {@code resolutionFallbackEnabled(true) + MissingToolCallbackResolver} 应把缺失工具降级为
 * 一次错误文本工具结果回传模型,而不是抛 {@link IllegalStateException} 中断任务。
 */
class MissingToolCallbackResolverTest {

    @Test
    void resolverReturnsFallbackThatReportsMissingTool() {
        ToolCallback fallback = new MissingToolCallbackResolver().resolve("powershell");

        assertNotNull(fallback, "任意未注册工具名都应解析出兜底回调");
        assertEquals("powershell", fallback.getToolDefinition().name());
        String result = fallback.call("{\"command\":\"dir\"}");
        assertTrue(result.contains("No ToolCallback found for tool name: powershell"),
                "兜底回调应返回框架同源错误文案: " + result);
    }

    @Test
    void missingToolBecomesErrorResultInsteadOfThrowing() {
        // 请求工具集只有 bash(模拟平台/模型幻觉:模型却调用了未注册的 powershell)
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .model("m")
                .toolCallbacks(List.of(ToolCallbacks.from(new FakeTools())))
                .build();
        Prompt prompt = new Prompt(List.of(new UserMessage("hi")), options);
        ChatResponse chatResponse = toolCallResponse("call-1", "powershell", "{\"command\":\"dir\"}");

        ToolCallingManager manager = ToolCallingManager.builder()
                .toolCallbackResolver(new MissingToolCallbackResolver())
                .resolutionFallbackEnabled(true)
                .build();

        ToolExecutionResult result = manager.executeToolCalls(prompt, chatResponse);

        // 不再抛 IllegalStateException,工具结果含「未找到」错误文本,模型可据此自纠
        Message last = result.conversationHistory().get(result.conversationHistory().size() - 1);
        assertTrue(last instanceof ToolResponseMessage, "历史末条应为工具响应: " + last.getClass());
        String data = ((ToolResponseMessage) last).getResponses().get(0).responseData();
        assertTrue(data.contains("No ToolCallback found for tool name: powershell"),
                "工具响应应携带框架同源错误文案: " + data);
    }

    @Test
    void withoutFallbackManagerStillThrowsAsBefore() {
        // 对照:未开启 resolutionFallback 时,框架原生行为仍是抛 IllegalStateException(被本修复消除)
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .model("m")
                .toolCallbacks(List.of(ToolCallbacks.from(new FakeTools())))
                .build();
        Prompt prompt = new Prompt(List.of(new UserMessage("hi")), options);
        ChatResponse chatResponse = toolCallResponse("call-1", "powershell", "{\"command\":\"dir\"}");

        ToolCallingManager manager = ToolCallingManager.builder().build();

        assertThrows(IllegalStateException.class, () -> manager.executeToolCalls(prompt, chatResponse),
                "未开启兜底时缺失工具仍应抛框架原生异常");
    }

    private static ChatResponse toolCallResponse(String id, String name, String args) {
        AssistantMessage m = AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", name, args)))
                .build();
        return new ChatResponse(List.of(new Generation(m,
                ChatGenerationMetadata.builder().finishReason("tool_calls").build())));
    }

    static final class FakeTools {

        @Tool(description = "执行命令")
        public String bash(String command) {
            return "ok";
        }
    }
}
