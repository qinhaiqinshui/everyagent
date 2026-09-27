package dev.everyagent.plugin.transientretry;

import dev.everyagent.plugin.api.EveryAgentPlugin;
import dev.everyagent.plugin.api.WorkerPluginContext;
import dev.everyagent.worker.config.WorkerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TransientErrorRetryPlugin implements EveryAgentPlugin {
    private static final Logger log = LoggerFactory.getLogger(TransientErrorRetryPlugin.class);

    @Override
    public String id() { return "transient-error-retry"; }

    @Override
    public void activate(WorkerPluginContext ctx) throws Exception {
        WorkerProperties props = ctx.getService(WorkerProperties.class);
        ctx.registerAdvisorProvider(new TransientErrorRetryAdvisorProvider(props));
        log.info("[transient-error-retry] 已注册 TransientErrorRetryAdvisorProvider");
    }
}
