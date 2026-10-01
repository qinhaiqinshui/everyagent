package dev.everyagent.plugin.filechange;

import dev.everyagent.plugin.api.EveryAgentPlugin;
import dev.everyagent.plugin.api.WorkerPluginContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FileChangePlugin implements EveryAgentPlugin {
    private static final Logger log = LoggerFactory.getLogger(FileChangePlugin.class);

    @Override
    public String id() { return "file-change"; }

    @Override
    public void activate(WorkerPluginContext ctx) throws Exception {
        ctx.registerAdvisorProvider(new FileChangeAdvisorProvider(ctx.services().task()));
        log.info("[file-change] 已注册 FileChangeAdvisorProvider");
    }
}
