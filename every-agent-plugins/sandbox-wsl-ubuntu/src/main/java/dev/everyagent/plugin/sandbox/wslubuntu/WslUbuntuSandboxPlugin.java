package dev.everyagent.plugin.sandbox.wslubuntu;

import dev.everyagent.plugin.api.EveryAgentPlugin;
import dev.everyagent.plugin.api.WorkerPluginContext;
import dev.everyagent.plugin.api.config.WorkerConfig;
import dev.everyagent.plugin.api.spi.WorkspaceManager;

import java.nio.file.Path;

/**
 * WSL Ubuntu 沙箱插件入口。
 *
 * <p>activate() 中：
 * <ol>
 *   <li>获取 WorkerConfig 和 WorkspaceManager；</li>
 *   <li>获取插件目录 {@code pluginDir}（镜像与启动器脚本由插件自己管理）；</li>
 *   <li>创建并注册 {@link WslUbuntuSandboxProvider}（沙箱后端提供者）；</li>
 *   <li>创建并注册 {@link WslUbuntuBashToolProvider}（沙箱自己的命令工具）；</li>
 *   <li>注册「/禁用网络」任务级命令与 token 解析器——真断网（发行版内 {@code unshare -n}）
 *       只有本后端做得到，故命令入口、任务级状态与落地全部归本插件，worker 核心不持有。</li>
 * </ol>
 */
public class WslUbuntuSandboxPlugin implements EveryAgentPlugin {

    @Override
    public String id() {
        return "sandbox-wsl-ubuntu";
    }

    @Override
    public void activate(WorkerPluginContext ctx) throws Exception {
        WorkerConfig props = ctx.getService(WorkerConfig.class);
        WorkspaceManager workspaces = ctx.getService(WorkspaceManager.class);
        Path pluginDir = ctx.pluginDir();

        // 1. 注册沙箱后端提供者
        ctx.registerSandboxProvider(new WslUbuntuSandboxProvider(props, workspaces, pluginDir));

        // 2. 注册沙箱自己的命令工具（ToolProvider）
        ctx.registerToolProvider(new WslUbuntuBashToolProvider(props, workspaces, pluginDir,
                ctx.services()));

        // 3. 注册「/禁用网络」：命令入口 + 提交期从 AI 上下文剥离 token（状态存任务 metadata）
        ctx.registerSlashProvider("network", () -> NetworkSlashProvider.items(ctx.services()));
        ctx.registerSlashTokenResolver(new NetworkSlashResolver());
    }
}
