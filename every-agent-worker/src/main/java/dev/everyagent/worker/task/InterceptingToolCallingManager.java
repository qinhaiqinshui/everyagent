package dev.everyagent.worker.task;

import dev.everyagent.worker.plugin.spi.ToolExecutionInterceptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;

/**
 * 拦截型工具调用管理器：装饰共享 ToolCallingManager，
 * 在 executeToolCalls 入口遍历 ToolExecutionInterceptor 链。
 * 第一个返回非 null 的拦截器短路，否则委托真实 manager 执行。
 *
 * <p>与 {@link LoopRepeatGuardToolManager} 同构——装饰 + 入口拦截 + 合成结果短路。
 */
public class InterceptingToolCallingManager implements ToolCallingManager {

    private final ToolCallingManager delegate;
    private final List<ToolExecutionInterceptor> interceptors;

    /**
     * 当前线程绑定的任务上下文（per-run），由 {@code AgentRunner} 在执行前设置、
     * 执行后清除。{@link ToolExecutionInterceptor} 单例通过 {@link #currentTask()} 获取
     * 当前任务，读取 {@code taskFlags} 判断是否拦截。
     */
    private static final ThreadLocal<TaskEntry> CURRENT_TASK = new ThreadLocal<>();

    public static void setCurrentTask(TaskEntry task) { CURRENT_TASK.set(task); }
    public static void clearCurrentTask() { CURRENT_TASK.remove(); }
    public static TaskEntry currentTask() { return CURRENT_TASK.get(); }

    public InterceptingToolCallingManager(ToolCallingManager delegate,
            List<ToolExecutionInterceptor> interceptors) {
        this.delegate = delegate;
        this.interceptors = interceptors; // 已按 order 排序
    }

    @Override
    public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
        return delegate.resolveToolDefinitions(chatOptions);
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
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
        for (ToolExecutionInterceptor interceptor : interceptors) {
            ToolExecutionResult result = interceptor.beforeToolExecution(prompt, chatResponse, toolCalls);
            if (result != null) {
                return result;
            }
        }
        return delegate.executeToolCalls(prompt, chatResponse);
    }
}
