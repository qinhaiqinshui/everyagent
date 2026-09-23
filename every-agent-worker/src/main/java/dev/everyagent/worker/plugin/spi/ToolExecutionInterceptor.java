package dev.everyagent.worker.plugin.spi;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolExecutionResult;

import java.util.List;

/**
 * 工具执行拦截链节点 SPI（洋葱模型，下行阶段）。
 *
 * 核心在 ToolCallingManager.executeToolCalls() 入口遍历所有拦截器。
 * 每个拦截器可检查本轮工具调用列表，返回合成结果短路，或返回 null 放行。
 * 零拦截器或全部放行 → 委托真实 ToolCallingManager 执行。
 */
public interface ToolExecutionInterceptor {

    int order();

    /**
     * 下行拦截：在真实工具执行前调用。
     * @return 非 null → 短路，用合成结果；null → 放行到下一节点 / 真实执行
     */
    ToolExecutionResult beforeToolExecution(
            Prompt prompt, ChatResponse chatResponse,
            List<AssistantMessage.ToolCall> toolCalls);
}
