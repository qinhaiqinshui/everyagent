package dev.everyagent.plugin.adaptivemaxtokens;

import dev.everyagent.plugin.api.EveryAgentPlugin;
import dev.everyagent.plugin.api.WorkerPluginContext;
import dev.everyagent.worker.config.WorkerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AdaptiveMaxTokensPlugin implements EveryAgentPlugin {
    private static final Logger log = LoggerFactory.getLogger(AdaptiveMaxTokensPlugin.class);

    @Override
    public String id() { return "adaptive-max-tokens"; }

    @Override
    public void activate(WorkerPluginContext ctx) throws Exception {
        WorkerProperties props = ctx.getService(WorkerProperties.class);
        ctx.registerAdvisorProvider(new AdaptiveMaxTokensAdvisorProvider(props));
        log.info("[adaptive-max-tokens] 已注册 AdaptiveMaxTokensAdvisorProvider");
    }
}
