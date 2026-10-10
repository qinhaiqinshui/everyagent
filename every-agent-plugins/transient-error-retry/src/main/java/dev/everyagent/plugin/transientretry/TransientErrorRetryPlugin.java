package dev.everyagent.plugin.transientretry;

import dev.everyagent.plugin.api.EveryAgentPlugin;
import dev.everyagent.plugin.api.WorkerPluginContext;
import dev.everyagent.plugin.api.config.WorkerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TransientErrorRetryPlugin implements EveryAgentPlugin {
    private static final Logger log = LoggerFactory.getLogger(TransientErrorRetryPlugin.class);

    @Override
    public String id() { return "transient-error-retry"; }

    @Override
    public void activate(WorkerPluginContext ctx) throws Exception {
        WorkerConfig config = ctx.services().config();
        ctx.registerAdvisorProvider(new TransientErrorRetryAdvisorProvider(config));
        log.info("[transient-error-retry] 已注册 TransientErrorRetryAdvisorProvider");
    }
}
