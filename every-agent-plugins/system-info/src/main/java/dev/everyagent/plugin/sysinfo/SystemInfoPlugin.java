package dev.everyagent.plugin.sysinfo;

import dev.everyagent.plugin.api.EveryAgentPlugin;
import dev.everyagent.plugin.api.WorkerPluginContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SystemInfoPlugin implements EveryAgentPlugin {
    private static final Logger log = LoggerFactory.getLogger(SystemInfoPlugin.class);

    @Override
    public String id() { return "system-info"; }

    @Override
    public void activate(WorkerPluginContext ctx) throws Exception {
        ctx.registerAdvisorProvider(new SystemInfoAdvisorProvider(ctx.services()));
        log.info("[system-info] 已注册 SystemInfoAdvisorProvider");
    }
}
