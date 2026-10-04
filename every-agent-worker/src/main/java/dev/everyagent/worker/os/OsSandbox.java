package dev.everyagent.worker.os;

import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.plugin.registry.SandboxProviderRegistry;
import dev.everyagent.plugin.api.spi.ExecResult;
import dev.everyagent.plugin.api.spi.NativeExec;
import dev.everyagent.plugin.api.spi.SandboxBackend;
import dev.everyagent.plugin.api.spi.SandboxProvider.SandboxConfig;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * OS 级进程沙箱门面（瘦身版）。
 *
 * <p>职责：作为 DIRECT 默认沙箱实现 {@link SandboxBackend}（mount 返回原路径,
 * onWorkspaceRemoved no-op），以及提供通用宿主进程执行服务（{@link #spawnNative}）。
 *
 * <p>沙箱后端（wsl-ubuntu / windows-mic）由独立插件通过 {@link SandboxProviderRegistry}
 * 注册;本类实现 SPI 接口作为 DIRECT 默认行为（mount 原路径直通、onWorkspaceRemoved no-op）,
 * 并在解析到 SPI 后端时把 {@code id()/mount()/onWorkspaceRemoved()} <strong>转发</strong>给它。
 * 后端解析按注册表代次惰性完成（插件注册晚于本类初始化,见 §7.10）。
 *
 * <p>核心的宿主访问工具（"允许AI访问电脑"开关）直接通过 ProcessBuilder 执行,
 * 不经本类 delegate。
 */
@Component
public final class OsSandbox implements SandboxBackend, NativeExec {

    private static final Logger log = LoggerFactory.getLogger(OsSandbox.class);

    /** 单流输出字符上限(stdout / stderr 各自适用):超出截断,防止超大输出撑爆上下文 / 内存。 */
    private static final int MAX_OUTPUT_CHARS = 1_000_000;

    /** 子进程 stdin 的 null 设备(Windows=NUL 设备,其余=/dev/null,见命令 stdin 契约 §7.10)。 */
    private static final java.io.File NULL_INPUT = new java.io.File(
            System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")
                    ? "NUL" : "/dev/null");

    private final WorkerProperties props;
    private final WorkerProperties.Sandbox cfg;
    /** 沙箱命令执行线程:虚拟线程。 */
    private final ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
    /** SPI 沙箱提供者注册表。 */
    private final SandboxProviderRegistry sandboxRegistry;
    /** SPI 沙箱后端委托（按注册表代次惰性解析；null = 无可用后端,退化为直接 spawn）。 */
    private volatile SandboxBackend delegate;
    /** 上一次解析对应的 {@link SandboxProviderRegistry#generation()}；-1 = 尚未解析过。 */
    private volatile long delegateGeneration = -1;
    /** 解析互斥锁:并发首次访问只解析一次。 */
    private final Object resolveLock = new Object();

    @jakarta.annotation.PreDestroy
    void shutdown() {
        exec.shutdownNow();
    }

    public OsSandbox(WorkerProperties props,
            SandboxProviderRegistry sandboxRegistry) {
        this.props = props;
        this.cfg = props.getSandbox();
        this.sandboxRegistry = sandboxRegistry;
    }

    /**
     * 启动期日志：<b>只陈述配置与候选,不对生效后端定论</b>。
     *
     * <p>时序红线（架构 §7.10）：全部 {@code SandboxProvider} 都由插件在 {@code PluginLoader}
     * 的 {@code @PostConstruct} 里注册,而 {@code PluginLoader → WorkerServices → OsSandbox}
     * 的构造依赖链决定了本方法必然<b>早于</b>任何注册执行。曾在此一次性 {@code select()} 定论,
     * 结果恒定打出「无可用 SPI 后端」并让 delegate 永远为 null —— 既没有 SPI 命令工具
     * （各后端 {@code ToolProvider.appliesTo} 全不成立）,也没有挂载/清理转发。
     * 生效后端改由 {@link #backend()} 按注册表代次惰性解析。
     */
    @PostConstruct
    void logBackendAtStartup() {
        String type = normalizeBackend(cfg.getType());
        if (!cfg.isEnabled()) {
            log.info("[sandbox] 沙箱未启用,命令直接 spawn(仅超时/输出护栏):type={}", type);
            return;
        }
        log.info("[sandbox] 沙箱已启用 type={} → 归一 {};SPI 候选 = {}"
                + "(插件注册晚于本组件初始化,生效后端将于首次使用时定论)",
                cfg.getType() == null || cfg.getType().isBlank() ? "auto" : cfg.getType().trim(),
                type, providersDesc());
    }

    /** 生效后端 id：有 SPI 后端时是其 id，否则 {@code "direct"}（本类自身即 DIRECT 默认沙箱）。 */
    @Override
    public String id() {
        SandboxBackend d = backend();
        return d != null ? d.id() : "direct";
    }

    /**
     * 挂载转发：有 SPI 后端则交给它（如 wsl-ubuntu 的批量 drvfs 挂载）,
     * 无后端才走 SPI 默认实现（原路径直通,即 DIRECT 语义）。
     *
     * <p>门面吞掉 mount 会让 {@code SandboxPathRegistry} 的宿主↔沙箱路径映射整体失效。
     */
    @Override
    public Map<Path, String> mount(List<SandboxBackend.MountRequest> requests) {
        SandboxBackend d = backend();
        return d != null ? d.mount(requests) : SandboxBackend.super.mount(requests);
    }

    /** 工作区移除清理转发：有 SPI 后端则交给它（如 wsl-ubuntu 的 best-effort umount）。 */
    @Override
    public void onWorkspaceRemoved(Path root) {
        SandboxBackend d = backend();
        if (d != null) {
            d.onWorkspaceRemoved(root);
        }
    }

    /**
     * 按注册表代次惰性解析 SPI 后端委托。
     *
     * <p>代次未变（含解析结果为 null）直接复用缓存,不重复探测；沙箱未启用或无注册表恒为 null。
     */
    public SandboxBackend backend() {
        if (!cfg.isEnabled() || sandboxRegistry == null) {
            return null;
        }
        long gen = sandboxRegistry.generation();
        if (gen == delegateGeneration) {
            return delegate;
        }
        synchronized (resolveLock) {
            if (gen != delegateGeneration) {
                SandboxBackend prev = delegate;
                SandboxBackend sel = sandboxRegistry.select(sandboxSpiConfig());
                delegate = sel;
                delegateGeneration = gen;
                if (sel != null) {
                    log.info("[sandbox] 生效 SPI 后端 = {}(代次 {},候选 {}){}",
                            sel.id(), gen, providersDesc(),
                            prev != null && !prev.id().equals(sel.id())
                                    ? ",由 " + prev.id() + " 切换" : "");
                } else {
                    log.warn("[sandbox] 未解析到可用 SPI 后端(代次 {},候选 {}),使用 DIRECT 默认沙箱(直接 spawn)",
                            gen, providersDesc());
                }
            }
            return delegate;
        }
    }

    /** 把 {@code WorkerProperties.Sandbox} 折算成 SPI 侧 {@code SandboxConfig}。 */
    private SandboxConfig sandboxSpiConfig() {
        return new SandboxConfig(
                normalizeBackend(cfg.getType()), cfg.isEnabled(), cfg.networkDenied(),
                cfg.isAllowPrivilegeEscalation(),
                cfg.getTimeoutMs(), props.resolveSandboxPersistentRoot(), props);
    }

    /** 候选清单（id+priority,不探测可用性）;无注册表时返回占位描述。 */
    private String providersDesc() {
        return sandboxRegistry == null ? "（无注册表）" : sandboxRegistry.describeProviders();
    }

    /**
     * backend 值归一:{@code auto}(默认)| {@code wsl-ubuntu} |
     * {@code windows-mic} | {@code none};
     * 旧值 {@code wsl-direct} → {@code wsl-ubuntu}（静默兼容）;
     * 旧值 {@code wsl-bwrap}/{@code bwrap}/{@code wsl} → {@code auto} 并 WARN;
     * 别名 {@code acl}→windows-mic、{@code direct}→wsl-ubuntu;
     * 未知/空值 → auto。
     */
    static String normalizeBackend(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (s) {
            case "wsl-ubuntu", "wsl-direct", "direct" -> "wsl-ubuntu";
            case "wsl-bwrap", "wsl", "bwrap" -> {
                log.warn("[sandbox] 旧后端类型 {} 已废弃,归一为 auto", s);
                yield "auto";
            }
            case "windows-mic", "acl", "mic" -> "windows-mic";
            case "none" -> "none";
            default -> "auto";
        };
    }

    /** 全局提权是否默认放行(worker.sandbox.allow-privilege-escalation=true)。 */
    public boolean privilegeAllowedByDefault() {
        return cfg.isAllowPrivilegeEscalation();
    }

    /**
     * 宿主原生进程 argv 直传(不做 wsl/mic 降权;供 NativeGit 等平台受控操作使用)。
     * 网络放行,超时沿用统一沙箱超时。
     */
    public ExecResult spawnNative(String[] argv, Path cwd, Map<String, String> env) {
        return spawnNative(argv, cwd, env, cfg.getTimeoutMs());
    }

    /**
     * 同上,可自定义超时(clone/pull/push 大仓库可能较慢,走 worker.git.timeout-ms)。
     */
    public ExecResult spawnNative(String[] argv, Path cwd, Map<String, String> env, long timeoutMs) {
        return runDirectCommand(java.util.List.of(argv), cwd, env, true, timeoutMs);
    }

    /** 直接执行(ProcessBuilder 以 argv 直传,无 shell 解析;带超时 + 每流输出上限 + 网络 env 处理)。 */
    private ExecResult runDirectCommand(java.util.List<String> cmd, Path cwd, Map<String, String> extraEnv,
            boolean allowNetwork, long timeoutMs) {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(cwd.toFile());
        pb.redirectInput(ProcessBuilder.Redirect.from(NULL_INPUT));
        pb.environment().putAll(sanitizedEnv(extraEnv, allowNetwork));
        try {
            Process p = pb.start();
            Future<String> out = exec.submit(() -> drain(p.getInputStream()));
            Future<String> err = exec.submit(() -> drain(p.getErrorStream()));
            boolean aborted = false;
            String outText;
            String errText;
            try {
                outText = out.get(timeoutMs, TimeUnit.MILLISECONDS);
                errText = awaitQuiet(err);
            } catch (TimeoutException e) {
                aborted = true;
                p.destroyForcibly();
                outText = awaitQuiet(out);
                errText = awaitQuiet(err);
                errText += "\n[exec 超时中止: >" + timeoutMs + "ms]";
            }
            int code = aborted ? -1 : p.waitFor();
            return new ExecResult(capOutput(outText), capOutput(errText), code, aborted);
        } catch (IOException e) {
            return new ExecResult("", "exec 启动失败: " + e.getMessage(), 1, false);
        } catch (Exception e) {
            return new ExecResult("", "exec 异常: " + e.getMessage(), 1, false);
        }
    }

    /** 等待读取任务收尾,超时/异常回退空串(进程已死后管道很快 EOF)。 */
    private static String awaitQuiet(Future<String> task) {
        try {
            return task.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "";
        }
    }

    /** 单流输出截断(stdout / stderr 各自适用)。 */
    private static String capOutput(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        return s.length() > MAX_OUTPUT_CHARS
                ? s.substring(0, MAX_OUTPUT_CHARS) + "\n[输出已截断至 " + MAX_OUTPUT_CHARS + " 字符]"
                : s;
    }

    /** 按网络许可清理环境:allowNetwork=false 时移除代理相关变量,防子进程绕过。 */
    private Map<String, String> sanitizedEnv(Map<String, String> extra, boolean allowNetwork) {
        Map<String, String> env = new java.util.HashMap<>(extra == null ? Map.of() : extra);
        if (!allowNetwork) {
            List<String> proxyKeys = List.of("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy",
                    "ALL_PROXY", "all_proxy", "NO_PROXY", "no_proxy");
            proxyKeys.forEach(env::remove);
        }
        return env;
    }

    private static String drain(java.io.InputStream in) throws IOException {
        try (java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            // 智能 UTF-8 → ANSI 码页回退：PowerShell cmdlet 在管道重定向 + CLM 下
            // 仍按系统 ANSI 码页（如 GBK）编码中文，外部程序输出 UTF-8；
            // 严格 UTF-8 失败时回退 ANSI 以正确还原中文（BUG-1 修复）。
            return dev.everyagent.plugin.api.shell.ExecResults.decodeConsoleOutput(bos.toByteArray());
        }
    }
}
