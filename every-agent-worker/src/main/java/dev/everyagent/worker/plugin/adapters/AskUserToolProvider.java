package dev.everyagent.worker.plugin.adapters;

import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.plugin.api.spi.ToolContext;
import dev.everyagent.plugin.api.spi.ToolProvider;
import dev.everyagent.worker.tools.AskUserTool;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;

/**
 * ask_user 工具提供者 —— 包装 {@link AskUserTool}。
 *
 * <p>子 agent 不注册 ask_user（提问只能由主 agent 发起，§5.6）。
 *
 * <p>createTools: {@code ToolCallbacks.from(new AskUserTool(props, ctx, agentId))}（ctx
 * 本身即 ExecContext），直接添加裸 ToolCallback——无人值守拦截逻辑已上移到
 * ToolExecutionInterceptor 责任链
 * （{@link dev.everyagent.worker.plugin.spi.ToolExecutionInterceptor}），核心不再硬编码装饰器。
 */
public class AskUserToolProvider implements ToolProvider {

    private final WorkerProperties props;

    public AskUserToolProvider(WorkerProperties props) {
        this.props = props;
    }

    @Override
    public String pluginId() {
        return "builtin-ask-user-tool";
    }

    @Override
    public List<ToolCallback> createTools(ToolContext ctx) {
        List<ToolCallback> tools = new ArrayList<>();
        for (ToolCallback c : ToolCallbacks.from(new AskUserTool(props, ctx, ctx.agentId()))) {
            tools.add(c);
        }
        return tools;
    }
}
