package dev.everyagent.worker.agent;

import dev.everyagent.plugin.api.agent.AgentContext;
import dev.everyagent.plugin.api.agent.AgentFactory;
import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.plugin.api.interaction.InteractionService;
import dev.everyagent.plugin.api.model.EventEmitter;
import dev.everyagent.plugin.api.model.ModelConfig;
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

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * per-run 拦截工具调用管理器（非单例）。
 *
 * <p>{@link ExecContext} 作为构造参数直接持有（S4 起替代原黑盒 properties map），
 * 不依赖 ThreadLocal——reactive 流的工具执行可能切换到 boundedElastic 线程，
 * ThreadLocal 不可靠。每 run 新建实例，状态隔离。
 */
public class InterceptingToolCallingManager implements ToolCallingManager {

    private final ToolCallingManager delegate;
    private final ToolExecutionInterceptorRegistry interceptorRegistry;
    private final ToolExecutionChainExecutor chainExecutor = new ToolExecutionChainExecutor();
    private final ExecContext execution;

    public InterceptingToolCallingManager(ToolCallingManager delegate,
            ToolExecutionInterceptorRegistry interceptorRegistry,
            ExecContext execution) {
        this.delegate = delegate;
        this.interceptorRegistry = interceptorRegistry;
        this.execution = execution;
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
        ToolExecutionContext ctx = new ToolExecutionContextImpl(prompt, chatResponse, toolCalls, execution);
        return chainExecutor.run(interceptors, delegate, ctx);
    }

    private record ToolExecutionContextImpl(
            Prompt prompt, ChatResponse chatResponse,
            List<AssistantMessage.ToolCall> toolCalls,
            ExecContext execution) implements ToolExecutionContext {

        // — ExecContext 委托 —
        @Override public String subjectId() { return execution.subjectId(); }
        @Override public String workspaceRoot() { return execution.workspaceRoot(); }
        @Override public String workspaceId() { return execution.workspaceId(); }
        @Override public ModelConfig snapshot() { return execution.snapshot(); }
        @Override public EventEmitter emitter() { return execution.emitter(); }
        @Override public AgentFactory agentFactory() { return execution.agentFactory(); }
        @Override public Map<String, Object> metadata() { return execution.metadata(); }
        @Override public Path dataDir() { return execution.dataDir(); }
        @Override public boolean terminal() { return execution.terminal(); }
        @Override public InteractionService interaction() { return execution.interaction(); }
        @Override public Map<String, AgentContext> agents() { return execution.agents(); }
    }
}
