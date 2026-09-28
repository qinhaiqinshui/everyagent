package dev.everyagent.plugin.agentsmd;

import dev.everyagent.plugin.api.EveryAgentPlugin;
import dev.everyagent.plugin.api.WorkerPluginContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AgentsMdPlugin implements EveryAgentPlugin {
    private static final Logger log = LoggerFactory.getLogger(AgentsMdPlugin.class);

    @Override
    public String id() { return "agents-md"; }

    @Override
    public void activate(WorkerPluginContext ctx) throws Exception {
        ctx.registerAdvisorProvider(new AgentsMdAdvisorProvider());
        log.info("[agents-md] 已注册 AgentsMdAdvisorProvider");
    }
}
