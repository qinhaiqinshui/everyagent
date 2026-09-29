package dev.everyagent.plugin.sandbox.wslubuntu;

import dev.everyagent.plugin.api.EveryAgentPlugin;
import dev.everyagent.plugin.api.WorkerPluginContext;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.modules.WorkspaceManager;

/**
 * WSL Ubuntu 沙箱插件入口。
 *
 * <p>activate() 中：
 * <ol>
 *   <li>获取 WorkerProperties 和 WorkspaceManager；</li>
 *   <li>创建并注册 {@link WslUbuntuSandboxProvider}（沙箱后端提供者）；</li>
 *   <li>创建并注册 {@link WslUbuntuBashToolProvider}（沙箱自己的命令工具）。</li>
 * </ol>
 */
public class WslUbuntuSandboxPlugin implements EveryAgentPlugin {

    @Override
    public String id() {
        return "sandbox-wsl-ubuntu";
    }

    @Override
    public void activate(WorkerPluginContext ctx) throws Exception {
        WorkerProperties props = ctx.getService(WorkerProperties.class);
        WorkspaceManager workspaces = ctx.getService(WorkspaceManager.class);

        // 1. 注册沙箱后端提供者
        ctx.registerSandboxProvider(new WslUbuntuSandboxProvider(props, workspaces));

        // 2. 注册沙箱自己的命令工具（ToolProvider）
        ctx.registerToolProvider(new WslUbuntuBashToolProvider(props, workspaces));
    }
}
