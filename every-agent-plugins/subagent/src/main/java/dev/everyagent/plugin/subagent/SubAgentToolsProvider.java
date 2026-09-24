package dev.everyagent.plugin.subagent;

import dev.everyagent.plugin.api.spi.ToolContext;
import dev.everyagent.plugin.api.spi.ToolProvider;
import dev.everyagent.worker.agent.AgentService;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

public class SubAgentToolsProvider implements ToolProvider {

    private final AgentService agentService;

    public SubAgentToolsProvider(AgentService agentService) {
        this.agentService = agentService;
    }

    @Override
    public String pluginId() {
        return "subagent";
    }

    @Override
    public Scope scope() {
        return Scope.MAIN;
    }

    @Override
    public List<ToolCallback> createTools(ToolContext ctx) {
        // ToolContextImpl 实现 ToolContext，其 taskEntry() 返回 TaskEntry，
        // 而 TaskEntry implements AgentContext —— 强转安全。
        var impl = (dev.everyagent.worker.plugin.ToolContextImpl) ctx;
        return List.of(ToolCallbacks.from(new SubAgentTools(agentService, impl.taskEntry())));
    }
}
