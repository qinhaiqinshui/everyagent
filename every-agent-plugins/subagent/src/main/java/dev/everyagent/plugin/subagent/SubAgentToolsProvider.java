package dev.everyagent.plugin.subagent;

import dev.everyagent.plugin.api.spi.ToolContext;
import dev.everyagent.plugin.api.spi.ToolProvider;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

public class SubAgentToolsProvider implements ToolProvider {

    private final SubAgentManager subAgentManager;

    public SubAgentToolsProvider(SubAgentManager subAgentManager) {
        this.subAgentManager = subAgentManager;
    }

    @Override
    public String pluginId() {
        return "subagent";
    }

    @Override
    public List<ToolCallback> createTools(ToolContext ctx) {
        return List.of(ToolCallbacks.from(new SubAgentTools(subAgentManager, ctx)));
    }
}
