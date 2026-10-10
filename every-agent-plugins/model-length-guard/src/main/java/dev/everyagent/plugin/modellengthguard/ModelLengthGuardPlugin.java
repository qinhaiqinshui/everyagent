package dev.everyagent.plugin.modellengthguard;

import dev.everyagent.plugin.api.EveryAgentPlugin;
import dev.everyagent.plugin.api.WorkerPluginContext;
import dev.everyagent.plugin.api.WorkerServices;
import dev.everyagent.plugin.api.config.WorkerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ModelLengthGuardPlugin implements EveryAgentPlugin {
    private static final Logger log = LoggerFactory.getLogger(ModelLengthGuardPlugin.class);

    @Override
    public String id() { return "model-length-guard"; }

    @Override
    public void activate(WorkerPluginContext ctx) throws Exception {
        WorkerConfig config = ctx.services().config();
        WorkerServices services = ctx.services();
        ctx.registerAdvisorProvider(new ModelLengthGuardAdvisorProvider(config, services));
        log.info("[model-length-guard] 已注册 ModelLengthGuardAdvisorProvider");
    }
}
