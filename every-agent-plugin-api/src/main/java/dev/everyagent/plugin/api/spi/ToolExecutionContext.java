package dev.everyagent.plugin.api.spi;

import dev.everyagent.plugin.api.agent.AgentContext;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

/**
 * 工具执行拦截上下文（替代 InterceptingToolCallingManager.currentTask() ThreadLocal）。
 * <p>显式注入任务上下文，插件不再依赖 worker 内部类取任务。
 */
public interface ToolExecutionContext {

    Prompt prompt();

    ChatResponse chatResponse();

    List<AssistantMessage.ToolCall> toolCalls();

    /** 当前任务上下文（AgentContext 窄接口，不暴露 TaskEntry）。 */
    AgentContext agentContext();
}
