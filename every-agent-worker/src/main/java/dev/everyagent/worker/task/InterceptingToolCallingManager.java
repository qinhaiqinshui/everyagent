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

public class InterceptingToolCallingManager implements ToolCallingManager {

    private final ToolCallingManager delegate;
    private final ToolExecutionInterceptorRegistry interceptorRegistry;
    private final ToolExecutionChainExecutor chainExecutor = new ToolExecutionChainExecutor();

    private static final ThreadLocal<Map<String, Object>> CURRENT_PROPERTIES = new ThreadLocal<>();

    public static void setCurrentProperties(Map<String, Object> props) { CURRENT_PROPERTIES.set(props); }
    public static void clearCurrentProperties() { CURRENT_PROPERTIES.remove(); }
    public static Map<String, Object> currentProperties() { return CURRENT_PROPERTIES.get(); }

    public InterceptingToolCallingManager(ToolCallingManager delegate,
            ToolExecutionInterceptorRegistry interceptorRegistry) {
        this.delegate = delegate;
        this.interceptorRegistry = interceptorRegistry;
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
        Map<String, Object> props = currentProperties();
        ToolExecutionContext ctx = new ToolExecutionContextImpl(prompt, chatResponse, toolCalls, props);
        return chainExecutor.run(interceptors, delegate, ctx);
    }

    private record ToolExecutionContextImpl(
            Prompt prompt, ChatResponse chatResponse,
            List<AssistantMessage.ToolCall> toolCalls,
            Map<String, Object> properties) implements ToolExecutionContext {}
}
