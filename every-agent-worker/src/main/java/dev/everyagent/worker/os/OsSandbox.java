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
 * <p>职责：作为 DIRECT 默认沙箱实现 {@link SandboxBackend}（grant/revoke no-op、
 * 翻译恒等），以及提供通用宿主进程执行服务（{@link #spawnNative}）。
 *
 * <p>沙箱后端（codex / windows-mic / wsl-ubuntu）由独立插件通过
 * {@link SandboxProviderRegistry} 注册;本类实现 SPI 接口作为 DIRECT 默认行为,
 * 并在解析到 SPI 后端时把 {@code id()/grant()/revoke()/toSandbox()/toHost()}
 * <strong>转发</strong>给它。
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
     * 授权下发转发：有 SPI 后端则交给它（如 codex 施加 ACE、wsl 建立 drvfs 挂载）,
     * 无后端时按 SPI 默认实现（no-op,即 DIRECT 语义——宿主进程即沙箱世界）。
     */
    @Override
    public void grant(List<SandboxBackend.PathGrant> grants) {
        SandboxBackend d = backend();
        if (d != null) {
            d.grant(grants);
        }
    }

    /** 授权回收转发：有 SPI 后端则交给它（撤 ACE / umount）；无后端 no-op。 */
    @Override
    public void revoke(List<Path> hostPaths) {
        SandboxBackend d = backend();
        if (d != null) {
            d.revoke(hostPaths);
        }
    }

    /** 路径翻译转发：有 SPI 后端则交给它；无后端走默认恒等（DIRECT 语义）。 */
    @Override
    public String toSandbox(Path hostPath) {
        SandboxBackend d = backend();
        return d != null ? d.toSandbox(hostPath) : SandboxBackend.super.toSandbox(hostPath);
    }

    /** 反向翻译转发：有 SPI 后端则交给它；无后端走默认恒等（DIRECT 语义）。 */
    @Override
    public Path toHost(String sandboxPath) {
        SandboxBackend d = backend();
        return d != null ? d.toHost(sandboxPath) : SandboxBackend.super.toHost(sandboxPath);
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

    /** 统一沙箱超时毫秒(worker.sandbox.timeout-ms)——命令工具自选执行方式时复用同一护栏。 */
    public long execTimeoutMs() {
        return cfg.getTimeoutMs();
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

    /**
     * <b>stdout / stderr 以文件承载</b>执行(§7.10 输出编码契约,PowerShell 中文乱码根治)。
     *
     * <p><b>为什么不能用管道</b>:PowerShell 5.1 的 stdout 被重定向到<b>管道</b>时,
     * {@code [Console]::OutputEncoding} 取系统 OEM 码页(中文 Windows=936/GBK)而非控制台码页,
     * 于是原生子进程(rg/git/npm)写出的 UTF-8 字节先被 PS 按 GBK 解码(非法序列当场变
     * U+FFFD,信息不可逆丢失),再按 GBK 编码送回管道——读取端任何"智能解码"都救不回来
     *（现场:中文仓库里 {@code rg 架构 docs} 返回 {@code 鏋舵瀯.md: 閺嬭埖鐎?},
     * 文件名再回灌 rg 直接 os error 2)。CLM 下改 setter 被策略拒,会话内 {@code chcp}
     * 也不同步到它,只有 ConPTY 或本方法这条路。
     *
     * <p><b>为什么文件就行</b>:stdout 指向文件时,PS 把该文件句柄直接交给原生子进程,
     * 子进程的原始字节<b>不经 PS 转码</b>直达文件;实测 cmdlet 中文输出与原生 UTF-8 输出
     * 在同一文件里<b>同为合法 UTF-8</b>(不再混码),stderr 同理(ps 流记录仍会是 CLIXML,
     * 由读取端 decodeClixml 统一还原——CLIXML 只取决于 stderr 是否控制台,与承载形态无关)。
     * 文件由本(JVM)进程创建并把可继承句柄交给子进程,故<b>不要求</b>沙箱账户对临时目录
     * 有写权限(实测沙箱内 {@code $env:TEMP} 不可写,只能靠句柄继承)。
     *
     * <p>语义与 {@link #spawnNative} 对齐:超时强杀({@code aborted=true} + exitCode=-1)、
     * 凭据 env 剔除、单流字节上限、退出码取进程真实值、临时文件必删。
     *
     * @param argv      命令参数(argv 直传,无 shell 解析)
     * @param cwd       工作目录
     * @param env       额外环境变量
     * @param timeoutMs 超时毫秒;&le;0 表示不设超时
     * @return 执行结果(stdout / stderr 已按 UTF-8 优先智能解码)
     */
    public ExecResult spawnToFileRedirected(String[] argv, Path cwd, Map<String, String> env,
            long timeoutMs) {
        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.directory(cwd.toFile());
        pb.redirectInput(ProcessBuilder.Redirect.from(NULL_INPUT));
        List<String> scrubbed = dev.everyagent.plugin.api.util.SecretPatterns
                .scrubInPlace(pb.environment());
        if (!scrubbed.isEmpty()) {
            log.info("[os-sandbox] 子进程 env 剔除凭据变量(仅名): {}", scrubbed);
        }
        pb.environment().putAll(sanitizedEnv(env, true));
        java.io.File outFile = null;
        java.io.File errFile = null;
        try {
            outFile = createCarryFile("ea-stdout-", cwd);
            errFile = createCarryFile("ea-stderr-", cwd);
            pb.redirectOutput(ProcessBuilder.Redirect.to(outFile));
            pb.redirectError(ProcessBuilder.Redirect.to(errFile));
            Process p = pb.start();
            boolean aborted;
            if (timeoutMs > 0) {
                aborted = !p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            } else {
                p.waitFor();
                aborted = false;
            }
            if (aborted) {
                p.destroyForcibly();
                p.waitFor(5, TimeUnit.SECONDS);
            }
            // 句柄随子进程退出关闭,故必须等进程结束/强杀后再读,否则拿到半份输出
            String outText = readCappedUtf8(outFile);
            String errText = readCappedUtf8(errFile);
            if (aborted) {
                errText += (errText.isEmpty() ? "" : "\n") + "[exec 超时中止: >" + timeoutMs + "ms]";
                return new ExecResult(capOutput(outText), capOutput(errText), -1, true);
            }
            return new ExecResult(capOutput(outText), capOutput(errText), p.exitValue(), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ExecResult("", "exec 被中断", -1, true);
        } catch (IOException e) {
            return new ExecResult("", "exec 启动失败: " + e.getMessage(), 1, false);
        } finally {
            deleteQuietly(outFile);
            deleteQuietly(errFile);
        }
    }

    /**
     * 建一个临时承载/脚本文件:优先 {@code java.io.tmpdir},不可写则退到工作区
     * {@code <root>/.everyagent/tmp}(受限账户下实测系统 TEMP 会被拒写,不退让会让
     * powershell 工具整体瘫痪)。
     *
     * @param prefix         文件名前缀
     * @param suffix         文件名后缀(如 {@code .ps1} / {@code .tmp})
     * @param workspaceRoot  工作区根(兜底落点,可为 null)
     * @return 已创建的空文件
     * @throws IOException 两处都建不出来
     */
    public static java.io.File createScratchFile(String prefix, String suffix,
            Path workspaceRoot) throws IOException {
        try {
            return java.io.File.createTempFile(prefix, suffix);
        } catch (IOException | IllegalArgumentException | SecurityException primary) {
            if (workspaceRoot == null) {
                throw primary;
            }
            Path dir = workspaceRoot.resolve(".everyagent").resolve("tmp");
            java.nio.file.Files.createDirectories(dir);
            return java.nio.file.Files.createTempFile(dir, prefix, suffix).toFile();
        }
    }

    /** stdout/stderr 承载文件(工作区兜底)。 */
    private static java.io.File createCarryFile(String prefix, Path cwd) throws IOException {
        return createScratchFile(prefix, ".tmp", cwd);
    }

    /** 读承载文件:上限 {@code MAX_OUTPUT_BYTES} 字节,UTF-8 优先智能解码,超限打截断标记。 */
    private static String readCappedUtf8(java.io.File f) {

        try (java.io.InputStream in = new java.io.FileInputStream(f)) {
            byte[] all = in.readNBytes(dev.everyagent.plugin.api.shell.ExecResults.MAX_OUTPUT_BYTES
                    + 1);
            int cap = dev.everyagent.plugin.api.shell.ExecResults.MAX_OUTPUT_BYTES;
            boolean over = all.length > cap;
            byte[] use = over ? java.util.Arrays.copyOf(all, cap) : all;
            String text = dev.everyagent.plugin.api.shell.ExecResults.decodeConsoleOutput(use);
            return over
                    ? text + "\n[输出已截断至 " + cap + " 字节]"
                    : text;
        } catch (IOException e) {
            return "";
        }
    }

    /** 承载文件清理:尽力删,失败留痕不抛(临时目录自带回收)。 */
    private static void deleteQuietly(java.io.File f) {
        if (f == null) {
            return;
        }
        try {
            java.nio.file.Files.deleteIfExists(f.toPath());
        } catch (IOException ignored) {
            // 删不掉不影响结果
        }
    }

    /** 直接执行(ProcessBuilder 以 argv 直传,无 shell 解析;带超时 + 每流输出上限 + 网络 env 处理)。 */
    private ExecResult runDirectCommand(java.util.List<String> cmd, Path cwd, Map<String, String> extraEnv,
            boolean allowNetwork, long timeoutMs) {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(cwd.toFile());
        pb.redirectInput(ProcessBuilder.Redirect.from(NULL_INPUT));
        // ProcessBuilder 默认整块继承父进程 env —— 这里就地剔除凭据形态变量,
        // 防沙箱外直跑的子进程(git/rg/npm 等)从 Env 读到宿主 shell 的 API key(§7.17)
        List<String> scrubbed = dev.everyagent.plugin.api.util.SecretPatterns
                .scrubInPlace(pb.environment());
        if (!scrubbed.isEmpty()) {
            log.info("[os-sandbox] 子进程 env 剔除凭据变量(仅名): {}", scrubbed);
        }
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
