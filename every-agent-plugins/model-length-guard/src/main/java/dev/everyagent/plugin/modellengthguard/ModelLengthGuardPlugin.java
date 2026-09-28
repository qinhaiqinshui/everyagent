package dev.everyagent.plugin.modellengthguard;

import dev.everyagent.plugin.api.EveryAgentPlugin;
import dev.everyagent.plugin.api.WorkerPluginContext;
import dev.everyagent.plugin.api.WorkerServices;
import dev.everyagent.worker.config.WorkerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ModelLengthGuardPlugin implements EveryAgentPlugin {
    private static final Logger log = LoggerFactory.getLogger(ModelLengthGuardPlugin.class);

    @Override
    public String id() { return "model-length-guard"; }

    @Override
    public void activate(WorkerPluginContext ctx) throws Exception {
        WorkerProperties props = ctx.getService(WorkerProperties.class);
        WorkerServices services = ctx.services();
        ctx.registerAdvisorProvider(new ModelLengthGuardAdvisorProvider(props, services));
        log.info("[model-length-guard] 已注册 ModelLengthGuardAdvisorProvider");
    }
}
