package dev.everyagent.plugin.sandbox.mic;

import dev.everyagent.plugin.api.EveryAgentPlugin;
import dev.everyagent.plugin.api.WorkerPluginContext;
import dev.everyagent.worker.config.WorkerProperties;

/**
 * Windows MIC 沙箱插件入口。
 *
 * <p>activate() 中获取 WorkerProperties，创建并注册 WindowsMicSandboxProvider。
 */
public class WindowsMicSandboxPlugin implements EveryAgentPlugin {

    @Override
    public String id() {
        return "sandbox-windows-mic";
    }

    @Override
    public void activate(WorkerPluginContext ctx) throws Exception {
        WorkerProperties props = ctx.getService(WorkerProperties.class);
        ctx.registerSandboxProvider(new WindowsMicSandboxProvider(props));
    }
}
