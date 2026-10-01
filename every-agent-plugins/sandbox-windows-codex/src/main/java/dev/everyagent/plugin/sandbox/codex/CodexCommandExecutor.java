package dev.everyagent.plugin.sandbox.codex;

import dev.everyagent.plugin.sandbox.codex.accounts.CapSids;
import dev.everyagent.plugin.sandbox.codex.accounts.SandboxAccounts;
import dev.everyagent.plugin.sandbox.codex.accounts.SandboxAccounts.NetworkIdentity;
import dev.everyagent.plugin.sandbox.codex.acl.ProvisioningAcl;
import dev.everyagent.plugin.sandbox.codex.acl.ProvisioningRequest;
import dev.everyagent.plugin.sandbox.codex.acl.RootPolicy;
import dev.everyagent.plugin.sandbox.codex.runner.FrameCodec;
import dev.everyagent.plugin.sandbox.codex.runner.IpcMessage;
import dev.everyagent.plugin.sandbox.codex.runner.IpcMessage.Exit;
import dev.everyagent.plugin.sandbox.codex.runner.IpcMessage.Output;
import dev.everyagent.plugin.sandbox.codex.session.CodexSandboxSession;
import dev.everyagent.plugin.sandbox.codex.session.RunnerClient;
import dev.everyagent.plugin.sandbox.codex.session.RunnerMaterializer;
import dev.everyagent.plugin.sandbox.codex.setup.SandboxDirs;
import dev.everyagent.plugin.sandbox.codex.setup.SetupMarker;
import dev.everyagent.plugin.sandbox.codex.setup.SetupPayload;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * codex 沙箱自己的命令执行器（设计文档 §2.8/§5，形态对照 WslUbuntuCommandExecutor）。
 *
 * <p>执行链：readiness（Windows ∧ marker 双闸门，<b>绝不自动 setup/UAC</b>）→
 * 会话根计算（{@link CodexSandboxManager} 登记根 + 工作区根，逐根取 capability SID）→
 * preflight 刷写根 ACE（{@link ProvisioningAcl}，真实用户身份持有 WRITE_DAC）→
 * 组 {@link CodexSandboxSession.SessionSpec}（cwd=工作区根、cap_sids=各写根 cap +
 * workspace cap、timeout={@code SandboxConfig.timeoutMs}、env 继承）→
 * {@link CodexSandboxSession#open} 拉起 runner 会话 → 聚合 stdout/stderr（base64 解码、
 * 每流上限截断）→ Exit 帧 → wsl 同款格式化尾注。
 *
 * <p>Windows 原生调用集中在三个可注入 seam（{@link SessionOpener}/
 * {@link PreflightRefresher}/{@link RunnerConfigFactory}）的生产默认实现里，
 * 纯逻辑（argv/env/聚合/格式化）跨平台可单测。
 */
public final class CodexCommandExecutor {

    /** 单流输出字符上限（对齐 wsl-ubuntu / DirectSpawnSupport.MAX_OUTPUT_CHARS）。 */
    static final int MAX_OUTPUT_CHARS = 1_000_000;

    /** 父侧看门狗在 SandboxConfig.timeoutMs 之外的收尾宽限（runner 终止 + Exit 帧）。 */
    static final long TEARDOWN_GRACE_MS = 15_000;

    /**
     * PowerShell 脚本预置前缀（对齐宿主 CommandExecutor）。
     * UTF-8 编码设置 + 非成功流静默化，确保中文输出不乱码、progress 等不刷屏。
     */
    static final String POWERSHELL_PREFIX =
            "[Console]::OutputEncoding=[System.Text.Encoding]::UTF8; "
            + "$OutputEncoding=[System.Text.Encoding]::UTF8; "
            + "$PSDefaultParameterValues['Get-Content:Encoding']='UTF8'; "
            + "$PSDefaultParameterValues['Set-Content:Encoding']='UTF8'; "
            + "$PSDefaultParameterValues['Out-File:Encoding']='UTF8'; "
            + "$ProgressPreference='SilentlyContinue'; $InformationPreference='SilentlyContinue'; "
            + "$WarningPreference='SilentlyContinue'; $VerbosePreference='SilentlyContinue'; "
            + "$DebugPreference='SilentlyContinue'; ";

    private static final boolean WINDOWS =
            System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    private static final System.Logger LOG =
            System.getLogger(CodexCommandExecutor.class.getName());

    /** 会话门面（生产适配 {@link CodexSandboxSession}；测试用 fake 帧）。 */
    public interface ExecSession extends AutoCloseable {
        /** 阻塞收下一帧；EOF（runner 退出）返回 null。 */
        FrameCodec.FramedMessage receive();

        /** 限时收下一帧；超时抛 IOException（生产 = PeekNamedPipe 轮询）。 */
        default FrameCodec.FramedMessage receive(long timeoutMs) throws IOException {
            return receive();
        }

        /** 请求终止子进程整树。 */
        void terminate();

        @Override
        void close();
    }

    /** 会话开 seam（生产 = {@link CodexSandboxSession#open}）。 */
    @FunctionalInterface
    public interface SessionOpener {
        ExecSession open(RunnerClient.RunnerConfig cfg, CodexSandboxSession.SessionSpec spec)
                throws IOException;
    }

    /** 写根 ACE preflight seam（生产 = Windows {@link ProvisioningAcl}）。 */
    @FunctionalInterface
    public interface PreflightRefresher {
        void refresh(CodexSandboxOptions options, Map<String, String> capSidsByRoot,
                List<Path> writeRoots, List<Path> readRoots) throws IOException;
    }

    /** runner 启动配置 seam（生产 = 物化 classpath + DPAPI 凭据）。 */
    @FunctionalInterface
    public interface RunnerConfigFactory {
        RunnerClient.RunnerConfig create(CodexSandboxOptions options, String username)
                throws IOException;
    }

    private final CodexSandboxManager manager;
    private final Path workspaceRoot;
    private final Path rgBinary;
    private final boolean nativeGuard;
    private final SessionOpener sessionOpener;
    private final PreflightRefresher preflight;
    private final RunnerConfigFactory runnerConfigFactory;

    public CodexCommandExecutor(CodexSandboxManager manager, Path workspaceRoot,
            Path rgBinary) {
        this(manager, workspaceRoot, rgBinary, true,
                (cfg, spec) -> adapt(CodexSandboxSession.open(cfg, spec)),
                CodexCommandExecutor::refreshWriteRootAces,
                CodexCommandExecutor::createRunnerConfig);
    }

    /** 测试构造：注入 fake seam；nativeGuard=false 跳过 Windows/marker 闸门。 */
    CodexCommandExecutor(CodexSandboxManager manager, Path workspaceRoot, Path rgBinary,
            boolean nativeGuard, SessionOpener sessionOpener, PreflightRefresher preflight,
            RunnerConfigFactory runnerConfigFactory) {
        this.manager = manager;
        this.workspaceRoot = workspaceRoot;
        this.rgBinary = rgBinary;
        this.nativeGuard = nativeGuard;
        this.sessionOpener = sessionOpener;
        this.preflight = preflight;
        this.runnerConfigFactory = runnerConfigFactory;
    }

    /**
     * 执行命令：经 runner 会话在沙箱账户内执行，结果格式化为
     * stdout + [stderr] + 超时/中断/截断 + exit code 尾注（wsl 同款）。
     */
    public String execute(String command, String shell) {
        if (command == null || command.isBlank()) {
            return "execute: command 不能为空";
        }
        CodexSandboxOptions options = manager.options();
        if (nativeGuard) {
            if (!WINDOWS) {
                return "[codex sandbox 仅在 Windows 上可用,当前平台: "
                        + System.getProperty("os.name") + "]";
            }
            if (!SetupMarker.isComplete(options.codexHome(), SetupPayload.SETUP_VERSION)) {
                return "[codex sandbox 未完成 setup;请重新激活/重启 worker 以触发 setup"
                        + "(会弹出 UAC 提权确认)完成账户/ACL/防火墙供给]";
            }
        }
        SessionRun run;
        try {
            run = runInSession(command, options);
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "[exec] codex 会话失败 cmd={0}",
                    truncate(command, 200) + " | " + e);
            return "[codex sandbox 执行失败] "
                    + (e.getMessage() == null ? e.toString() : e.getMessage());
        }
        LOG.log(System.Logger.Level.INFO, "[exec] codex rc={0} timedOut={1} cmd={2}",
                new Object[] { run.exitCode(), run.timedOut(), truncate(command, 200) });
        return format(run);
    }

    private SessionRun runInSession(String command, CodexSandboxOptions options)
            throws IOException {
        Path home = options.codexHome();
        List<Path> roots = sessionWriteRoots();
        Map<String, String> capSids = new LinkedHashMap<>();
        List<RootPolicy.WriteRoot> writeRoots = new ArrayList<>();
        for (Path root : roots) {
            String cap = CapSids.workspaceWriteCapSidForRoot(home, workspaceRoot, root);
            capSids.put(root.toString(), cap);
            writeRoots.add(new RootPolicy.WriteRoot(root, cap));
        }
        RootPolicy policy = new RootPolicy(writeRoots, List.of(), List.of(),
                manager.readRoots()).sanitized(home);
        preflight.refresh(options, capSids, roots, manager.readRoots());

        NetworkIdentity identity = options.networkIdentity(manager.networkDenied());
        String username = SandboxAccounts.usernameFor(options.accountPrefix(), identity);
        RunnerClient.RunnerConfig cfg = runnerConfigFactory.create(options, username);

        long timeoutMs = manager.execTimeoutMs();
        CodexSandboxSession.SessionSpec spec = new CodexSandboxSession.SessionSpec(
                commandArgv(command), workspaceRoot.toString(), childEnv(rgBinary),
                timeoutMs > 0 ? timeoutMs : null, policy.writeRoots(), List.of(),
                CapSids.workspaceCapSidForCwd(home, workspaceRoot),
                wireName(identity), false, null);
        return aggregate(cfg, spec, timeoutMs);
    }

    /** 收帧聚合（父侧看门狗：超时先 terminate 再等 Exit 帧；宽限 TEARDOWN_GRACE_MS）。 */
    private SessionRun aggregate(RunnerClient.RunnerConfig cfg,
            CodexSandboxSession.SessionSpec spec, long timeoutMs) throws IOException {
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        boolean truncated = false;
        boolean timedOut = false;
        boolean interrupted = false;
        int exitCode = 0;
        boolean exited = false;
        long deadline = timeoutMs > 0
                ? System.nanoTime() + (timeoutMs + TEARDOWN_GRACE_MS) * 1_000_000L
                : Long.MAX_VALUE;
        try (ExecSession session = sessionOpener.open(cfg, spec)) {
            while (!exited) {
                FrameCodec.FramedMessage frame;
                if (!timedOut && deadline != Long.MAX_VALUE) {
                    long remainMs = (deadline - System.nanoTime()) / 1_000_000L;
                    if (remainMs <= 0) {
                        timedOut = true;
                        session.terminate();
                        continue;
                    }
                    try {
                        frame = session.receive(remainMs);
                    } catch (IOException waitTimedOut) {
                        timedOut = true;
                        session.terminate();
                        continue;
                    }
                } else {
                    frame = session.receive();
                }
                if (frame == null) {
                    interrupted = true;
                    break;
                }
                if (frame.message() instanceof Output out) {
                    String text = new String(IpcMessage.decodeBytes(out.dataBase64()),
                            StandardCharsets.UTF_8);
                    truncated |= out.stream() == IpcMessage.Stream.STDOUT
                            ? appendCapped(stdout, text) : appendCapped(stderr, text);
                } else if (frame.message() instanceof Exit exit) {
                    exitCode = exit.exitCode();
                    timedOut |= exit.timedOut();
                    exited = true;
                }
                // 其余帧（父→runner 方向不会出现；容忍协议演进）静默丢弃
            }
        }
        return new SessionRun(stdout.toString(), stderr.toString(), exitCode,
                timedOut, interrupted, truncated);
    }

    /** 会话聚合结果（格式化输入；纯数据）。 */
    record SessionRun(String stdout, String stderr, int exitCode, boolean timedOut,
            boolean interrupted, boolean truncated) {
    }

    // ---- 纯函数（跨平台单测） ----

    /** shell 命令 → 子进程 argv：PowerShell -NoProfile -Command（UTF-8 编码前缀 + 用户命令）。 */
    static List<String> commandArgv(String command) {
        return List.of("powershell.exe", "-NoProfile", "-Command",
                POWERSHELL_PREFIX + command);
    }

    /** 继承当前环境；rgBinary 非空时其所在目录前置进 Path（Windows 键名优先）。 */
    static Map<String, String> childEnv(Path rgBinary) {
        Map<String, String> env = new LinkedHashMap<>(System.getenv());
        if (rgBinary != null && rgBinary.getParent() != null) {
            String dir = rgBinary.getParent().toString();
            String key = env.containsKey("Path") ? "Path" : "PATH";
            String current = env.get(key);
            boolean present = current != null && current.toUpperCase(Locale.ROOT)
                    .contains(dir.toUpperCase(Locale.ROOT));
            env.put(key, current == null || current.isBlank() ? dir
                    : present ? current : dir + ";" + current);
        }
        return env;
    }

    /** NetworkIdentity → wire 名（SpawnRequest.network_identity：offline/online）。 */
    static String wireName(NetworkIdentity identity) {
        return identity == NetworkIdentity.OFFLINE ? "offline" : "online";
    }

    /** 追加并执行每流上限；返回 true 表示有内容被截掉。 */
    static boolean appendCapped(StringBuilder sb, String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        int room = MAX_OUTPUT_CHARS - sb.length();
        if (room <= 0) {
            return true;
        }
        sb.append(text, 0, Math.min(text.length(), room));
        return text.length() > room;
    }

    /** stdout / [stderr] / 超时 / 中断 / 截断 / exit code 尾注（wsl 同款格式）。 */
    static String format(SessionRun run) {
        StringBuilder sb = new StringBuilder();
        if (!run.stdout().isEmpty()) {
            sb.append(run.stdout());
        }
        if (!run.stderr().isEmpty()) {
            sb.append(sb.isEmpty() ? "" : "\n").append("[stderr]\n").append(run.stderr());
        }
        if (run.timedOut()) {
            sb.append(sb.isEmpty() ? "" : "\n").append("[命令被沙箱超时中止]");
        }
        if (run.interrupted()) {
            sb.append(sb.isEmpty() ? "" : "\n").append("[runner 管道在 exit 帧前关闭]");
        }
        if (run.truncated()) {
            sb.append(sb.isEmpty() ? "" : "\n")
                    .append("[输出已截断至 ").append(MAX_OUTPUT_CHARS).append(" 字符]");
        }
        if (run.exitCode() != 0) {
            sb.append(sb.isEmpty() ? "" : "\n")
                    .append("[exit code: ").append(run.exitCode()).append("]");
        }
        return sb.toString();
    }

    /** 工作区根不在登记表时补为首根（防御：mount 晚于首次执行）。 */
    List<Path> sessionWriteRoots() {
        List<Path> roots = new ArrayList<>(manager.writeRoots());
        String wsKey = CapSids.canonicalPathKey(workspaceRoot);
        boolean present = roots.stream()
                .anyMatch(r -> CapSids.canonicalPathKey(r).equals(wsKey));
        if (!present) {
            roots.add(0, workspaceRoot);
        }
        return roots;
    }

    // ---- 生产 seam 默认实现（Windows 原生） ----

    private static ExecSession adapt(CodexSandboxSession session) {
        return new ExecSession() {
            @Override
            public FrameCodec.FramedMessage receive() {
                return session.receive();
            }

            @Override
            public FrameCodec.FramedMessage receive(long timeoutMs) throws IOException {
                return session.receive(timeoutMs);
            }

            @Override
            public void terminate() {
                session.terminate();
            }

            @Override
            public void close() {
                session.close();
            }
        };
    }

    /** preflight：真实用户身份对会话写根 ensure allow-write ACE（组 + root cap 双主体）。 */
    static void refreshWriteRootAces(CodexSandboxOptions options,
            Map<String, String> capSidsByRoot, List<Path> writeRoots, List<Path> readRoots)
            throws IOException {
        if (!WINDOWS) {
            return; // 非 Windows 单测/探测路径 no-op
        }
        String groupSid = SandboxAccounts.sidString(
                SandboxAccounts.groupName(options.accountPrefix()));
        ProvisioningRequest.Builder builder = ProvisioningRequest.builder(groupSid,
                SandboxDirs.sandboxDir(options.codexHome()));
        for (Path root : writeRoots) {
            String capSid = capSidsByRoot.get(root.toString());
            if (capSid != null) {
                builder.writeRoot(root.toString(), capSid);
            }
        }
        builder.readRoots(readRoots.stream().map(Path::toString).toList());
        new ProvisioningAcl().applyProvisioning(builder.build());
    }

    /** runner 启动配置：物化 classpath（.sandbox-bin，组 R+X）+ DPAPI 凭据。 */
    static RunnerClient.RunnerConfig createRunnerConfig(CodexSandboxOptions options,
            String username) throws IOException {
        String groupSid = WINDOWS ? SandboxAccounts.sidString(
                SandboxAccounts.groupName(options.accountPrefix())) : null;
        String classpath = RunnerMaterializer.ensureRunnerClasspath(
                options.codexHome(), groupSid);
        String password = RunnerClient.loadPassword(options.codexHome(), username);
        String javaHome = options.javaHome().isBlank()
                ? System.getProperty("java.home") : options.javaHome();
        return new RunnerClient.RunnerConfig(options.codexHome(), username, password,
                classpath, javaHome,
                SandboxDirs.sandboxDir(options.codexHome()).toString());
    }

    private static String truncate(String s, int n) {
        return s == null ? "" : (s.length() <= n ? s : s.substring(0, n) + "...");
    }
}
