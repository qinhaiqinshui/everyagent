package dev.everyagent.worker.os;

import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.plugin.registry.SandboxProviderRegistry;
import dev.everyagent.plugin.api.spi.ExecResult;
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
 * 注册;本类实现 SPI 接口的默认行为（不挂载、不清理），插件后端覆盖之。
 *
 * <p>核心的宿主访问工具（"允许AI访问电脑"开关）直接通过 ProcessBuilder 执行,
 * 不经本类 delegate。
 */
@Component
public final class OsSandbox implements SandboxBackend {

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
    /** SPI 沙箱后端委托（从 SandboxProviderRegistry 选择；null = 无可用后端,退化为直接 spawn）。 */
    private volatile SandboxBackend delegate;

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
     * 启动即解析并打印生效后端。
     */
    @PostConstruct
    void logBackendAtStartup() {
        if (cfg.isEnabled()) {
            String type = normalizeBackend(cfg.getType());
            SandboxConfig sboxConfig = new SandboxConfig(
                    type, cfg.isEnabled(), cfg.networkDenied(),
                    cfg.isAllowPrivilegeEscalation(),
                    cfg.getTimeoutMs(), props.resolveSandboxPersistentRoot(), props);
            this.delegate = sandboxRegistry.select(sboxConfig);
            if (delegate != null) {
                log.info("[sandbox] SPI 后端委托 = {}", delegate.id());
            } else {
                log.info("[sandbox] 无可用 SPI 后端,使用 DIRECT 默认沙箱(直接 spawn)");
            }
        } else {
            log.info("[sandbox] 沙箱未启用,命令直接 spawn(仅超时/输出护栏):type={}",
                    cfg.getType() == null || cfg.getType().isBlank() ? "auto" : cfg.getType().trim());
        }
    }

    @Override
    public String id() {
        return "direct";
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

    /** 全局网络策略是否默认放行(worker.sandbox.allow-network=true)。 */
    public boolean networkAllowedByDefault() {
        return !cfg.networkDenied();
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
            return bos.toString(java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
