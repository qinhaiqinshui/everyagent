package dev.everyagent.plugin.sandbox.codex;

import dev.everyagent.plugin.api.EveryAgentPlugin;
import dev.everyagent.plugin.api.WorkerPluginContext;
import dev.everyagent.plugin.api.config.WorkerConfig;

import java.lang.System.Logger.Level;
import java.nio.file.Path;

/**
 * Windows Codex 沙箱插件入口（设计文档 §2.8/§5，形态对照 WslUbuntuSandboxPlugin）。
 *
 * <p>activate() 中：
 * <ol>
 *   <li>读 {@link WorkerConfig} 与插件配置（codex.* 键）组装
 *       {@link CodexSandboxOptions}，建共享 {@link CodexSandboxManager}；</li>
 *   <li>注册 {@link CodexSandboxProvider}（后端 id=codex；isAvailable 仅探测 Windows
 *       平台、priority=8，绝不触发 setup/UAC）；</li>
 *   <li>注册 {@link CodexBashToolProvider}（appliesTo：当前后端 id==codex 时
 *       提供 bash 工具，经 {@link CodexCommandExecutor} 走 runner 会话）。</li>
 * </ol>
 *
 * <p><b>setup 时机</b>：不在 activate() 中做——延迟到 {@link CodexSandboxProvider#create}
 * 被调用时。create() 只在 codex 被沙箱选择器选为最高优先级可用后端时才触发，
 * 这意味着只有当无 WSL 等更高优先级沙箱可用时，才会弹 UAC 做 setup。
 *
 * <p>Win32 绑定层见 {@code win} 包——{@code Native.load} 在 INSTANCE 静态字段
 * 首次触发时才执行，Linux 下不加载（插件可跨平台装载，Provider.isAvailable 恒 false）。
 */
public class CodexSandboxPlugin implements EveryAgentPlugin {

    private static final System.Logger LOG = System.getLogger(CodexSandboxPlugin.class.getName());

    @Override
    public String id() {
        return "sandbox-windows-codex";
    }

    @Override
    public void activate(WorkerPluginContext ctx) throws Exception {
        WorkerConfig props = ctx.getService(WorkerConfig.class);
        CodexSandboxOptions options = CodexSandboxOptions.from(ctx.config(), props);
        CodexSandboxManager manager = new CodexSandboxManager(options,
                props.sandbox().timeoutMs());

        // 1. 沙箱后端提供者（id=codex；isAvailable=Windows 平台探测，priority=8）
        ctx.registerSandboxProvider(new CodexSandboxProvider(manager));

        // 2. 沙箱自己的命令工具（appliesTo=codex 后端选中时）；rg 由插件自带，激活时解析一次
        Path rgPath = CodexRg.resolve(ctx.pluginDir());
        ctx.registerToolProvider(new CodexBashToolProvider(manager, rgPath));

        LOG.log(Level.INFO,
                "sandbox-windows-codex 已激活（codexHome={0},账户前缀={1},网络策略={2}；"
                        + "setup 延迟到 codex 后端被选中时执行）",
                new Object[] { options.codexHome(), options.accountPrefix(),
                        options.networkPolicy() });
    }
}
