package dev.everyagent.plugin.api.spi;

import dev.everyagent.plugin.api.execution.ExecContext;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

public interface ToolExecutionContext {
    Prompt prompt();
    ChatResponse chatResponse();
    List<AssistantMessage.ToolCall> toolCalls();

    /**
     * 统一执行上下文(subjectId / workspaceRoot / snapshot / metadata 等类型化槽位)。
     * <p>S4 起替代原黑盒 map {@code properties().get("taskEntry")} 取数路径,
     * 拦截器(如 unattended)经此判定主体策略与任务域数据。
     */
    ExecContext execution();
}
