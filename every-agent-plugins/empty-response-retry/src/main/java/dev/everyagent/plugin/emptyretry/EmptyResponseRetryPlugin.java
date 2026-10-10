package dev.everyagent.plugin.emptyretry;

import dev.everyagent.plugin.api.EveryAgentPlugin;
import dev.everyagent.plugin.api.WorkerPluginContext;
import dev.everyagent.plugin.api.config.WorkerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EmptyResponseRetryPlugin implements EveryAgentPlugin {
    private static final Logger log = LoggerFactory.getLogger(EmptyResponseRetryPlugin.class);

    @Override
    public String id() { return "empty-response-retry"; }

    @Override
    public void activate(WorkerPluginContext ctx) throws Exception {
        WorkerConfig config = ctx.services().config();
        ctx.registerAdvisorProvider(new EmptyResponseRetryAdvisorProvider(config));
        log.info("[empty-response-retry] 已注册 EmptyResponseRetryAdvisorProvider");
    }
}
