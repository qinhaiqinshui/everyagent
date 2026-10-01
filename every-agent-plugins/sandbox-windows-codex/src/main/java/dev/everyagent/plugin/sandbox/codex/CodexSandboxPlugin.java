package dev.everyagent.plugin.sandbox.codex;

import dev.everyagent.plugin.api.EveryAgentPlugin;
import dev.everyagent.plugin.api.WorkerPluginContext;
import dev.everyagent.plugin.api.config.WorkerConfig;

import java.lang.System.Logger.Level;

/**
 * Windows Codex 沙箱插件入口（设计文档 §2.8/§5，形态对照 WslUbuntuSandboxPlugin）。
 *
 * <p>activate() 中：
 * <ol>
 *   <li>读 {@link WorkerConfig} 与插件配置（codex.* 键）组装
 *       {@link CodexSandboxOptions}，建共享 {@link CodexSandboxManager}；</li>
 *   <li>注册 {@link CodexSandboxProvider}（后端 id=codex；isAvailable 只探测、
 *       绝不触发 setup/UAC，priority 就绪 8 / 未 setup 0）；</li>
 *   <li>注册 {@link CodexBashToolProvider}（appliesTo：当前后端 id==codex 时
 *       提供 bash 工具，经 {@link CodexCommandExecutor} 走 runner 会话）；</li>
 *   <li>注册 {@link CodexSandboxSetupToolProvider}（codex_sandbox_setup——显式
 *       用户动作允许 UAC；codex_sandbox_status——只读状态摘要）。</li>
 * </ol>
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

        // 1. 沙箱后端提供者（id=codex；不抢既有默认后端，见 Provider Javadoc）
        ctx.registerSandboxProvider(new CodexSandboxProvider(manager));

        // 2. 沙箱自己的命令工具（appliesTo=codex 后端选中时）
        ctx.registerToolProvider(new CodexBashToolProvider(manager));

        // 3. 显式 setup/status 工具（setup 会弹 UAC，是唯一的供给入口）
        ctx.registerToolProvider(new CodexSandboxSetupToolProvider(manager));

        LOG.log(Level.INFO,
                "sandbox-windows-codex 已激活（codexHome={0},账户前缀={1},网络策略={2}；"
                        + "Provider 就绪需先经 codex_sandbox_setup 完成 setup）",
                new Object[] { options.codexHome(), options.accountPrefix(),
                        options.networkPolicy() });
    }
}
