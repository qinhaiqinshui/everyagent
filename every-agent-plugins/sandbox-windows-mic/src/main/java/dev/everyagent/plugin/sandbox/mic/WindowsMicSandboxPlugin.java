package dev.everyagent.plugin.sandbox.mic;

import dev.everyagent.plugin.api.EveryAgentPlugin;
import dev.everyagent.plugin.api.WorkerPluginContext;
import dev.everyagent.plugin.api.config.WorkerConfig;

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
        WorkerConfig props = ctx.getService(WorkerConfig.class);
        ctx.registerSandboxProvider(new WindowsMicSandboxProvider(props));
    }
}
