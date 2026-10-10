package dev.everyagent.plugin.askuser;

import dev.everyagent.plugin.api.config.WorkerConfig;
import dev.everyagent.plugin.api.spi.ToolContext;
import dev.everyagent.plugin.api.spi.ToolProvider;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * ask_user 工具提供者 —— 包装 {@link AskUserTool}。
 *
 * <p>主/子 agent 均注册 ask_user（{@code ToolProvider.appliesTo} 默认 true），
 * 提问不限于主 agent 发起。
 *
 * <p>createTools: {@code ToolCallbacks.from(new AskUserTool(config, ctx, ctx.agentId()))}
 * （ToolContext 本身即 ExecContext），直接返回裸 ToolCallback——无人值守拦截在
 * ToolExecutionInterceptor 责任链按工具名 {@code ask_user} 拦截，与本插件解耦。
 */
public class AskUserToolProvider implements ToolProvider {

    private final WorkerConfig config;

    public AskUserToolProvider(WorkerConfig config) {
        this.config = config;
    }

    @Override
    public String pluginId() {
        return "ask-user";
    }

    @Override
    public List<ToolCallback> createTools(ToolContext ctx) {
        return List.of(ToolCallbacks.from(new AskUserTool(config, ctx, ctx.agentId())));
    }
}
