package dev.everyagent.worker.plugin.adapters;

import dev.everyagent.worker.plugin.ToolContextImpl;
import dev.everyagent.worker.plugin.spi.ToolContext;
import dev.everyagent.worker.plugin.spi.ToolProvider;
import dev.everyagent.worker.task.SubAgentManager;
import dev.everyagent.worker.tools.SubAgentTools;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * 子 agent 工具提供者（scope=MAIN）—— 包装 {@link SubAgentTools}。
 *
 * <p>子 agent 的工具集不含本类（结构上禁止递归），故 scope=MAIN。
 *
 * <p>createTools: {@code ToolCallbacks.from(new SubAgentTools(subs, task))}，
 * 与改造前 buildMainAgent 中的装配方式完全一致。
 */
public class SubAgentToolsProvider implements ToolProvider {

    private final SubAgentManager subs;

    public SubAgentToolsProvider(SubAgentManager subs) {
        this.subs = subs;
    }

    @Override
    public String pluginId() {
        return "builtin-sub-agent-tools";
    }

    @Override
    public Scope scope() {
        return Scope.MAIN;
    }

    @Override
    public List<ToolCallback> createTools(ToolContext ctx) {
        ToolContextImpl impl = (ToolContextImpl) ctx;
        return List.of(ToolCallbacks.from(new SubAgentTools(subs, impl.taskEntry())));
    }
}
