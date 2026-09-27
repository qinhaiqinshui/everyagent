package dev.everyagent.plugin.contextcompression;

import dev.everyagent.plugin.api.EveryAgentPlugin;
import dev.everyagent.plugin.api.WorkerPluginContext;
import dev.everyagent.worker.config.WorkerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ContextCompressionPlugin implements EveryAgentPlugin {
    private static final Logger log = LoggerFactory.getLogger(ContextCompressionPlugin.class);

    @Override
    public String id() { return "context-compression"; }

    @Override
    public void activate(WorkerPluginContext ctx) throws Exception {
        WorkerProperties props = ctx.getService(WorkerProperties.class);
        log.info("[context-compression] 插件已激活");
    }
}
