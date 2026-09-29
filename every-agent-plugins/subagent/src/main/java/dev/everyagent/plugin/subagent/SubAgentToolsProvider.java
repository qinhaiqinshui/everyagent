package dev.everyagent.plugin.subagent;

import dev.everyagent.plugin.api.spi.ToolContext;
import dev.everyagent.plugin.api.spi.ToolProvider;
import dev.everyagent.worker.agent.AgentService;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Map;

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
    public List<ToolCallback> createTools(ToolContext ctx) {
        var impl = (dev.everyagent.worker.plugin.ToolContextImpl) ctx;
        Map<String, Object> properties = Map.of("taskEntry", impl.taskEntry());
        return List.of(ToolCallbacks.from(new SubAgentTools(agentService, properties)));
    }
}
