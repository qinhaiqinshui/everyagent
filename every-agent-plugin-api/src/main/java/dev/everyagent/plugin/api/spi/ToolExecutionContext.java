package dev.everyagent.plugin.api.spi;

import dev.everyagent.plugin.api.execution.ExecContext;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

/**
 * 工具执行拦截上下文。
 *
 * <p>本身即 {@link ExecContext}（extends），拦截器直读类型化槽位
 * （subjectId / workspaceRoot / metadata 等），不再经 {@code execution()} 中转。
 * 保留 {@link #prompt()} / {@link #chatResponse()} / {@link #toolCalls()} 为
 * 工具执行拦截专属成员。
 */
public interface ToolExecutionContext extends ExecContext {
    Prompt prompt();
    ChatResponse chatResponse();
    List<AssistantMessage.ToolCall> toolCalls();
}
