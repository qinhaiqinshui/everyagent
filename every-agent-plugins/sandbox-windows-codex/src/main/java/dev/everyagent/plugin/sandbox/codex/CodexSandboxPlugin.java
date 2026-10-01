package dev.everyagent.plugin.sandbox.codex;

import dev.everyagent.plugin.api.EveryAgentPlugin;
import dev.everyagent.plugin.api.WorkerPluginContext;
import dev.everyagent.plugin.api.config.WorkerConfig;

import dev.everyagent.plugin.sandbox.codex.accounts.SandboxSecrets;
import dev.everyagent.plugin.sandbox.codex.setup.SetupErrorReport;
import dev.everyagent.plugin.sandbox.codex.setup.SetupMarker;
import dev.everyagent.plugin.sandbox.codex.setup.SetupOrchestrator;
import dev.everyagent.plugin.sandbox.codex.setup.SetupPayload;
import dev.everyagent.plugin.sandbox.codex.setup.SetupStatus;

import java.nio.file.Path;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
 *   <li>Windows 平台 + setup 未完成时，同步执行 {@link SetupOrchestrator#ensureSetup}
 *       完成账户/ACL/防火墙/WFP/marker 供给（会弹 UAC；幂等：已完成则 marker 双闸门短路）。
 *       setup 失败则插件激活失败（不影响其他插件）。</li>
 * </ol>
 *
 * <p>Win32 绑定层见 {@code win} 包——{@code Native.load} 在 INSTANCE 静态字段
 * 首次触发时才执行，Linux 下不加载（插件可跨平台装载，Provider.isAvailable 恒 false）。
 */
public class CodexSandboxPlugin implements EveryAgentPlugin {

    private static final System.Logger LOG = System.getLogger(CodexSandboxPlugin.class.getName());

    private static final boolean WINDOWS =
            System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");

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

        // 3. 同步完成 setup（幂等：marker 双闸门就绪则短路；非 Windows 跳过）
        if (WINDOWS && !SetupMarker.isComplete(options.codexHome(),
                SetupPayload.SETUP_VERSION)) {
            LOG.log(Level.INFO, "codex 沙箱 setup 未完成,开始同步 setup(会弹 UAC)...");
            List<String> roots = new ArrayList<>();
            for (Path root : manager.writeRoots()) {
                roots.add(root.toString());
            }
            SetupPayload payload = SetupPayload.create()
                    .mode(SetupPayload.Mode.FULL)
                    .accounts(options.accountPrefix(), options.codexHome().toString(),
                            System.getProperty("user.name"))
                    .writeRoots(roots)
                    .proxyPorts(options.proxyPorts());
            payload.model().allowLocalBinding = options.allowLocalBinding();
            try {
                SetupOrchestrator.ensureSetup(payload);
            } catch (SetupErrorReport.SetupException e) {
                String msg = "codex 沙箱 setup 失败: code=" + e.code() + " " + e.getMessage();
                if (SetupErrorReport.ORCHESTRATOR_HELPER_LAUNCH_CANCELED.equals(e.code())) {
                    msg += "(用户在 UAC 弹窗拒绝了提权)";
                }
                LOG.log(Level.ERROR, msg);
                throw e; // 插件激活失败（不影响其他插件）
            }
            LOG.log(Level.INFO, "codex 沙箱 setup 完成(marker 版本 {0},codexHome={1})",
                    new Object[] { SetupPayload.SETUP_VERSION, options.codexHome() });
        }

        // 4. 打印状态摘要（诊断用，不弹 UAC）
        LOG.log(Level.INFO, SetupStatus.statusSummary(
                WINDOWS, options,
                SetupMarker.read(options.codexHome()),
                SandboxSecrets.exists(options.codexHome()),
                WINDOWS ? SetupStatus.accountFlags(options, false) : null,
                WINDOWS ? SetupStatus.accountFlags(options, true) : null));
    }
}

