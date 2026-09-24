package dev.everyagent.plugin.subagent;

import dev.everyagent.worker.agent.AgentService;
import dev.everyagent.worker.plugin.registry.ToolProviderRegistry;
import org.springframework.stereotype.Component;

@Component
public class SubAgentRegistrar {

    public SubAgentRegistrar(ToolProviderRegistry toolRegistry, AgentService agentService) {
        toolRegistry.register(new SubAgentToolsProvider(agentService));
    }
}
