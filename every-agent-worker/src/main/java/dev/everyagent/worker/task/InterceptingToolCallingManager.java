package dev.everyagent.worker.task;

import dev.everyagent.plugin.api.spi.ToolExecutionContext;
import dev.everyagent.worker.plugin.registry.ToolExecutionInterceptorRegistry;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Map;

/**
 * per-run 拦截工具调用管理器（非单例）。
 *
 * <p>properties 作为构造参数直接持有，不依赖 ThreadLocal——reactive 流的工具执行
 * 可能切换到 boundedElastic 线程，ThreadLocal 不可靠。每 run 新建实例，状态隔离。
 */
public class InterceptingToolCallingManager implements ToolCallingManager {

    private final ToolCallingManager delegate;
    private final ToolExecutionInterceptorRegistry interceptorRegistry;
    private final ToolExecutionChainExecutor chainExecutor = new ToolExecutionChainExecutor();
    private final Map<String, Object> properties;

    public InterceptingToolCallingManager(ToolCallingManager delegate,
            ToolExecutionInterceptorRegistry interceptorRegistry,
            Map<String, Object> properties) {
        this.delegate = delegate;
        this.interceptorRegistry = interceptorRegistry;
        this.properties = properties;
    }

    @Override
    public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
        return delegate.resolveToolDefinitions(chatOptions);
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
        var interceptors = interceptorRegistry.sorted();
        if (interceptors.isEmpty()) {
            return delegate.executeToolCalls(prompt, chatResponse);
        }
        AssistantMessage assistant = chatResponse.getResults().stream()
                .filter(g -> g.getOutput() != null && g.getOutput().hasToolCalls())
                .map(Generation::getOutput)
                .findFirst()
                .orElse(null);
        if (assistant == null) {
            return delegate.executeToolCalls(prompt, chatResponse);
        }
        List<AssistantMessage.ToolCall> toolCalls = assistant.getToolCalls();
        ToolExecutionContext ctx = new ToolExecutionContextImpl(prompt, chatResponse, toolCalls, properties);
        return chainExecutor.run(interceptors, delegate, ctx);
    }

    private record ToolExecutionContextImpl(
            Prompt prompt, ChatResponse chatResponse,
            List<AssistantMessage.ToolCall> toolCalls,
            Map<String, Object> properties) implements ToolExecutionContext {}
}
