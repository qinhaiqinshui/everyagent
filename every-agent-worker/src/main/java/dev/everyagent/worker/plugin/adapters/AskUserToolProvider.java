package dev.everyagent.worker.plugin.adapters;

import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.plugin.ToolContextImpl;
import dev.everyagent.worker.plugin.spi.ToolContext;
import dev.everyagent.worker.plugin.spi.ToolProvider;
import dev.everyagent.worker.task.PendingAsks;
import dev.everyagent.worker.task.TaskEntry;
import dev.everyagent.worker.tools.AskUserTool;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;

/**
 * ask_user 工具提供者（scope=MAIN）—— 包装 {@link AskUserTool}。
 *
 * <p>子 agent 不注册 ask_user（提问只能由主 agent 发起，§5.6），故 scope=MAIN。
 *
 * <p>createTools: {@code ToolCallbacks.from(new AskUserTool(asks, props, task, agentId))}，
 * 直接添加裸 ToolCallback——无人值守拦截逻辑已上移到 ToolExecutionInterceptor 责任链
 * （{@link dev.everyagent.worker.plugin.spi.ToolExecutionInterceptor}），核心不再硬编码装饰器。
 */
public class AskUserToolProvider implements ToolProvider {

    private final PendingAsks asks;
    private final WorkerProperties props;

    public AskUserToolProvider(PendingAsks asks, WorkerProperties props) {
        this.asks = asks;
        this.props = props;
    }

    @Override
    public String pluginId() {
        return "builtin-ask-user-tool";
    }

    @Override
    public Scope scope() {
        return Scope.MAIN;
    }

    @Override
    public List<ToolCallback> createTools(ToolContext ctx) {
        ToolContextImpl impl = (ToolContextImpl) ctx;
        TaskEntry task = impl.taskEntry();
        List<ToolCallback> tools = new ArrayList<>();
        for (ToolCallback c : ToolCallbacks.from(new AskUserTool(asks, props, task, ctx.agentId()))) {
            tools.add(c);
        }
        return tools;
    }
}
