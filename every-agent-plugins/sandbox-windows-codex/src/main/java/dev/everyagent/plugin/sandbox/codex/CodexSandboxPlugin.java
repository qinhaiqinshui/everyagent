package dev.everyagent.plugin.sandbox.codex;

import dev.everyagent.plugin.api.EveryAgentPlugin;
import dev.everyagent.plugin.api.WorkerPluginContext;
import dev.everyagent.plugin.api.config.WorkerConfig;

import dev.everyagent.plugin.sandbox.codex.accounts.LogonSessionMonitor;
import dev.everyagent.plugin.sandbox.codex.accounts.SandboxAccounts.NetworkIdentity;
import dev.everyagent.plugin.sandbox.codex.proxy.ProxyRegistry;
import dev.everyagent.plugin.sandbox.codex.session.RunnerPools;

import java.io.IOException;
import java.lang.System.Logger.Level;

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

    /** 登录会话审计线程（deactivate 停止；null = 非 Windows 或审计关闭）。 */
    private volatile Thread logonSessionAudit;
    /** activate 快照（deactivate 时关停池化 runner 用）。 */
    private volatile CodexSandboxOptions lastOptions;

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
        lastOptions = options;

        // 1. 沙箱后端提供者（id=codex；isAvailable=Windows 平台探测，priority=8）
        ctx.registerSandboxProvider(new CodexSandboxProvider(manager));

        // 2. 沙箱自己的命令工具（appliesTo=codex 后端选中时）
        ctx.registerToolProvider(new CodexBashToolProvider(manager));

        // 3. 会话泄漏可观测性：周期枚举沙箱账户登录会话数（INFO；超阈值 WARN 附处置提示）
        if (com.sun.jna.Platform.isWindows()
                && options.logonSessionAuditIntervalMs() > 0) {
            logonSessionAudit = new LogonSessionMonitor(options.accountPrefix(),
                    options.logonSessionAuditThreshold()).startDaemon(
                            options.logonSessionAuditIntervalMs());
        }

        // 4. 宿主侧 loopback 代理（阶段二沙箱 HTTPS）：仅 online 身份启动。activate 时
        //    networkDenied 恒 false（accept 尚未发生）→ auto 兜底为 online 即启动；
        //    policy=offline 永不启动。启动失败降级直连（不注入代理 env），沙箱命令不受阻。
        if (com.sun.jna.Platform.isWindows() && options.proxyEnabled()
                && options.networkIdentity(manager.networkDenied()) == NetworkIdentity.ONLINE) {
            try {
                ProxyRegistry.ensureStarted(options).ifPresent(ep -> LOG.log(Level.INFO,
                        "[codex-proxy] online 策略:loopback 代理已就绪 {0}"
                                + "（沙箱命令注入代理 env;关闭开关 codex.proxy-enabled=false）",
                        ep.proxyUrl()));
            } catch (IOException | RuntimeException e) {
                LOG.log(Level.WARNING,
                        "[codex-proxy] loopback 代理启动失败,沙箱命令将直连(不注入代理 env): {0}",
                        e.toString());
            }
        }

        LOG.log(Level.INFO,
                "sandbox-windows-codex 已激活（codexHome={0},账户前缀={1},网络策略={2},"
                        + "runner池化={3}(max={4},idleTtl={5}ms),代理={6}(首选端口={7})；"
                        + "setup 延迟到 codex 后端被选中时执行）",
                new Object[] { options.codexHome(), options.accountPrefix(),
                        options.networkPolicy(), options.runnerPoolEnabled() ? "开" : "关",
                        options.runnerPoolMaxSize(), options.runnerPoolIdleTtlMs(),
                        options.proxyEnabled() ? "开" : "关", options.proxyPreferredPort() });
    }

    @Override
    public void deactivate() {
        Thread audit = logonSessionAudit;
        logonSessionAudit = null;
        if (audit != null) {
            audit.interrupt();
        }
        // 池化 runner 的登录会话随管道关闭/进程退出回收；主动发 shutdown 加速收口
        RunnerPools.invalidateAll(lastOptions != null ? lastOptions.codexHome() : null);
        // loopback 代理随 worker 进程收口（在途隧道连接一并关闭）
        ProxyRegistry.stopAll();
    }
}
