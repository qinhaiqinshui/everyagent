package dev.everyagent.plugin.api.spi;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;

public interface ToolExecutionContext {
    Prompt prompt();
    ChatResponse chatResponse();
    List<AssistantMessage.ToolCall> toolCalls();
    Map<String, Object> properties();
}
