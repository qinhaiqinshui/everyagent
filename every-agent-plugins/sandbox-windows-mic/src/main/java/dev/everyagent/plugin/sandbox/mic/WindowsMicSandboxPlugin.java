package dev.everyagent.plugin.sandbox.mic;

import dev.everyagent.plugin.api.EveryAgentPlugin;
import dev.everyagent.plugin.api.WorkerPluginContext;
import dev.everyagent.plugin.api.config.WorkerConfig;

import java.nio.file.Path;

/**
 * Windows MIC 沙箱插件入口。
 *
 * <p>activate() 中获取 WorkerProperties，创建并注册 WindowsMicSandboxProvider，
 * 并注册 {@link WindowsMicShellToolProvider}（自带 rg 注入 PATH）。
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

        // rg 由插件自带（<pluginDir>/bin/rg.exe），激活时解析一次
        Path rgPath = MicRg.resolve(ctx.pluginDir());
        // CommandExecutor 的 rgBinDir 需要的是目录路径，resolve 返回的是 rg.exe 文件路径
        Path rgDir = rgPath != null ? rgPath.getParent() : null;
        ctx.registerToolProvider(new WindowsMicShellToolProvider(rgDir));
    }
}

