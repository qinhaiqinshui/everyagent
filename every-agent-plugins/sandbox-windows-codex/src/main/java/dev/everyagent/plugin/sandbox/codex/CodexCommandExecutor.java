package dev.everyagent.plugin.sandbox.codex;

import dev.everyagent.plugin.api.shell.ExecResults;
import dev.everyagent.plugin.api.util.SecretPatterns;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * codex 沙箱自己的命令执行器（设计文档 §2.8/§5，形态对照 WslUbuntuCommandExecutor）。
 *
 * <p>执行链：readiness（Windows ∧ marker 双闸门，setup 在 {@link CodexSandboxProvider#create}
 * 时已完成）→
 * 会话根计算（{@link CodexSandboxManager} 登记根 + 工作区根，逐根取 capability SID）→
 * preflight 刷写根 ACE（{@link ProvisioningAcl}，真实用户身份持有 WRITE_DAC）→
 * 组 {@link CodexSandboxSession.SessionSpec}（cwd=工作区根、cap_sids=各写根 cap +
 * workspace cap、timeout={@code SandboxConfig.timeoutMs}、env 继承）→
 * {@link CodexSandboxSession#open} 拉起 runner 会话 → 聚合 stdout/stderr（base64 还原
 * 原始字节、每流字节上限截断、整段 {@link ExecResults#decodeConsoleOutput(byte[])}
 * 智能 UTF-8/ANSI 解码）→ Exit 帧 → wsl 同款格式化尾注。
 *
 * <p>Windows 原生调用集中在三个可注入 seam（{@link SessionOpener}/
 * {@link PreflightRefresher}/{@link RunnerConfigFactory}）的生产默认实现里，
 * 纯逻辑（argv/env/聚合/格式化）跨平台可单测。
 */
public final class CodexCommandExecutor {

    /** 父侧看门狗在 SandboxConfig.timeoutMs 之外的收尾宽限（runner 终止 + Exit 帧）。 */
    static final long TEARDOWN_GRACE_MS = 15_000;

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
    /** 探测到的 shell（缓存，进程生命周期内只探测一次）。 */
    private final ShellChoice shell;

    public CodexCommandExecutor(CodexSandboxManager manager, Path workspaceRoot,
            Path rgBinary) {
        this(manager, workspaceRoot, rgBinary, true, detectShell(),
                (cfg, spec) -> adapt(CodexSandboxSession.open(cfg, spec)),
                CodexCommandExecutor::refreshWriteRootAces,
                CodexCommandExecutor::createRunnerConfig);
    }

    /** 测试构造：注入 fake seam；nativeGuard=false 跳过 Windows/marker 闸门。 */
    CodexCommandExecutor(CodexSandboxManager manager, Path workspaceRoot, Path rgBinary,
            boolean nativeGuard, SessionOpener sessionOpener, PreflightRefresher preflight,
            RunnerConfigFactory runnerConfigFactory) {
        this(manager, workspaceRoot, rgBinary, nativeGuard, ShellChoice.POWERSHELL,
                sessionOpener, preflight, runnerConfigFactory);
    }

    /** 测试构造：显式指定 shell。 */
    CodexCommandExecutor(CodexSandboxManager manager, Path workspaceRoot, Path rgBinary,
            boolean nativeGuard, ShellChoice shell, SessionOpener sessionOpener,
            PreflightRefresher preflight, RunnerConfigFactory runnerConfigFactory) {
        this.manager = manager;
        this.workspaceRoot = workspaceRoot;
        this.rgBinary = rgBinary;
        this.nativeGuard = nativeGuard;
        this.shell = shell;
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
                return "[codex sandbox 未完成 setup;setup 应在后端 create() 时自动触发,"
                        + "若仍失败请检查 UAC 是否被拒绝或重新启动 worker]";
            }
        }
        SessionRun run;
        try {
            run = runInSession(command, options);
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "[exec] codex 会话失败 cmd={0}",
                    ExecResults.truncate(command, 200) + " | " + e);
            // 物化缓存可能失真（.sandbox-bin 被外部清理/篡改）：丢弃缓存，
            // 下一条命令重做物化自愈（代价仅一次 ~150ms 校验）
            RunnerMaterializer.invalidateRunnerClasspath(options.codexHome());
            return "[codex sandbox 执行失败] "
                    + (e.getMessage() == null ? e.toString() : e.getMessage());
        }
        LOG.log(System.Logger.Level.INFO, "[exec] codex rc={0} timedOut={1} cmd={2}",
                new Object[] { run.exitCode(), run.timedOut(), ExecResults.truncate(command, 200) });
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
        long tStart = System.nanoTime();
        preflight.refresh(options, capSids, roots, manager.readRoots());
        long tPreflight = System.nanoTime();

        NetworkIdentity identity = options.networkIdentity(manager.networkDenied());
        String username = SandboxAccounts.usernameFor(options.accountPrefix(), identity);
        RunnerClient.RunnerConfig cfg = runnerConfigFactory.create(options, username);
        long tConfig = System.nanoTime();

        long timeoutMs = manager.execTimeoutMs();
        // 命令投递(仅 PowerShell 分支):主形态=脚本文件承载(-File),PS 报错定位引用
        // 用户命令行而非内部包装前缀(见 commandArgvForFile javadoc);文件写失败回退
        // -EncodedCommand,行为无回退仅定位质量回退。CMD 分支保持 commandArgv 原样。
        Path cmdScript = null;
        List<String> argv;
        if (shell.isPowerShell) {
            try {
                cmdScript = writeCommandScript(workspaceRoot, command);
                argv = commandArgvForFile(cmdScript);
            } catch (IOException | RuntimeException e) {
                cmdScript = null;
                argv = commandArgv(command);
                LOG.log(System.Logger.Level.WARNING,
                        "[exec] 命令脚本文件承载失败,回退 -EncodedCommand: {0}", e.toString());
            }
        } else {
            argv = commandArgv(command);
        }
        CodexSandboxSession.SessionSpec spec = new CodexSandboxSession.SessionSpec(
                argv, workspaceRoot.toString(),
                childEnv(rgBinary, workspaceRoot, sandboxProfileDir()),
                timeoutMs > 0 ? timeoutMs : null, policy.writeRoots(), List.of(),
                CapSids.workspaceCapSidForCwd(home, workspaceRoot),
                wireName(identity), false, null);
        try {
            SessionRun run = aggregate(cfg, spec, timeoutMs);
            LOG.log(System.Logger.Level.INFO,
                    "[exec] timing preflight={0}ms runnerCfg={1}ms sessionExec={2}ms",
                    new Object[] { (tPreflight - tStart) / 1_000_000L,
                            (tConfig - tPreflight) / 1_000_000L,
                            (System.nanoTime() - tConfig) / 1_000_000L });
            return run;
        } finally {
            if (cmdScript != null) {
                try {
                    Files.deleteIfExists(cmdScript);
                } catch (IOException ignored) {
                    // 尽力清理;.everyagent/tmp 随任务清理兜底
                }
            }
        }
    }

    /** 收帧聚合（父侧看门狗：超时先 terminate 再等 Exit 帧；宽限 TEARDOWN_GRACE_MS）。 */
    private SessionRun aggregate(RunnerClient.RunnerConfig cfg,
            CodexSandboxSession.SessionSpec spec, long timeoutMs) throws IOException {
        java.io.ByteArrayOutputStream stdout = new java.io.ByteArrayOutputStream();
        java.io.ByteArrayOutputStream stderr = new java.io.ByteArrayOutputStream();
        boolean truncated = false;
        boolean timedOut = false;
        boolean interrupted = false;
        int exitCode = 0;
        boolean exited = false;
        long deadline = timeoutMs > 0
                ? System.nanoTime() + (timeoutMs + TEARDOWN_GRACE_MS) * 1_000_000L
                : Long.MAX_VALUE;
        long tOpen = System.nanoTime();
        try (ExecSession session = sessionOpener.open(cfg, spec)) {
            LOG.log(System.Logger.Level.INFO, "[exec] timing sessionOpen={0}ms",
                    (System.nanoTime() - tOpen) / 1_000_000L);
            boolean firstOutputLogged = false;
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
                    if (!firstOutputLogged) {
                        firstOutputLogged = true;
                        LOG.log(System.Logger.Level.INFO,
                                "[exec] timing firstOutput={0}ms",
                                (System.nanoTime() - tOpen) / 1_000_000L);
                    }
                    byte[] chunk = IpcMessage.decodeBytes(out.dataBase64());
                    truncated |= out.stream() == IpcMessage.Stream.STDOUT
                            ? appendCapped(stdout, chunk) : appendCapped(stderr, chunk);
                } else if (frame.message() instanceof Exit exit) {
                    exitCode = exit.exitCode();
                    timedOut |= exit.timedOut();
                    exited = true;
                }
                // 其余帧（父→runner 方向不会出现；容忍协议演进）静默丢弃
            }
        }
        // 全流原始字节一次性智能解码：严格 UTF-8 失败回退系统 ANSI 码页，
        // 解决 PowerShell CLM 下中文按 GBK 编码、外部程序按 UTF-8 输出的混合编码问题（BUG-1）；
        // 整段解码也消除了逐帧解码时多字节字符跨 chunk 被拆导致的替换字符隐患。
        // stderr 再过 decodeClixml：powershell.exe 在 stderr 非控制台时把 error/warning 流
        // 记录序列化成 CLIXML(与文件/管道承载无关),还原成真实错误文本,绝不静默丢弃
        // (2026-10 实测:文件承载下 Write-Error 仍产生 CLIXML;此前只有 worker 核心的
        // DIRECT 路径做了还原,codex 后端漏接)。
        return new SessionRun(
                ExecResults.decodeConsoleOutput(stdout.toByteArray()),
                ExecResults.decodeClixml(ExecResults.decodeConsoleOutput(stderr.toByteArray())),
                exitCode, timedOut, interrupted, truncated);
    }

    /** 会话聚合结果（格式化输入；纯数据）。 */
    record SessionRun(String stdout, String stderr, int exitCode, boolean timedOut,
            boolean interrupted, boolean truncated) {
    }

    // ---- 纯函数（跨平台单测） ----

    /**
     * shell 命令 → 子进程 argv：按探测到的 shell 分派。
     *
     * <p><b>回退形态</b>(PowerShell 分支):脚本文件承载({@link #commandArgvForFile})是主形态,
     * 本方法仅在脚本文件写失败时使用。仍走 <b>cmd-chcp 包装</b>(BUG-1 混排编码的正解,
     * ARCHITECTURE「cmd-chcp 包装」条):CLM 禁 {@code [Console]::OutputEncoding} 的 setter,但 getter
     * 在 PS 进程<b>首次访问时</b>才读 {@code GetConsoleOutputCP()} 并缓存——把
     * {@code chcp.com 65001} 挪到 powershell.exe <b>启动之前</b>(cmd 先建隐藏控制台并设
     * CP=65001,PS 在同一控制台里启动),PS 自身输出与「管道内捕获原生输出」的解码即全部
     * UTF-8,与原生工具的 UTF-8 字节同流同码,严格解码一次通过。脚本经
     * {@code -EncodedCommand}(base64(UTF-16LE),Java getBytes 不带 BOM)投递:载荷是
     * cmd 安全字符集,免疫 {@code &}/{@code |}/引号嵌套解析;{@code /d} 跳过 AutoRun。
     * 实测 git/rg 管道捕获与直出全净,退出码经 cmd→powershell 正确传导。
     */
    List<String> commandArgv(String command) {
        if (shell.isPowerShell) {
            // -ExecutionPolicy Bypass:沙箱账户默认 Restricted 策略会拦截 .ps1 脚本
            // (如 npm.ps1),per-process 旁路不影响系统策略。
            // 尾部 POWERSHELL_EXIT_TAIL:把最后一个原生子进程的退出码转成 powershell.exe 的
            // 进程码——否则 Exit 帧里的退出码恒 0,模型分不清 rg「无匹配=1」与「用错=2」。
            String script = ExecResults.POWERSHELL_PREFIX + command
                    + ExecResults.POWERSHELL_EXIT_TAIL;
            String encoded = Base64.getEncoder()
                    .encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
            return List.of("cmd.exe", "/d", "/s", "/c",
                    "chcp.com 65001 >nul 2>&1 & " + shell.exe
                            + " -NoProfile -ExecutionPolicy Bypass -EncodedCommand " + encoded);
        }
        return List.of(shell.exe, "/c", "chcp 65001 >nul & " + command);
    }

    /**
     * <b>主形态</b>:三段式脚本(prefix/用户命令/exit 尾部各占一行,见 {@link #buildScript})
     * 写入工作区 {@code .everyagent/tmp/ea-cmd-<pid>-<纳秒>.ps1},cmd-chcp 包装不变(码页
     * 必须先于 powershell 启动),载荷经 {@code -File "路径"} 投递(带引号容忍空格)。
     *
     * <p><b>为什么换掉 -EncodedCommand</b>:单行形态下 PS 报错的 PositionMessage 会连内部
     * 包装前缀一起回显——{@code At line:1 char:506 + ...nue'; $DebugPreference=
     * 'SilentlyContinue'; java -version 2>&1; $__EA...}——泄漏实现细节且 char:506 定位
     * 不可读;用户命令独占一行后变为精确引用用户代码(2026-10 实测对照):
     * {@code At <脚本>:2 char:1 + java -version 2>&1}。
     */
    List<String> commandArgvForFile(Path script) {
        return List.of("cmd.exe", "/d", "/s", "/c",
                "chcp.com 65001 >nul 2>&1 & " + shell.exe
                        + " -NoProfile -ExecutionPolicy Bypass -File \"" + script + "\"");
    }

    /**
     * 三段式脚本内容:prefix/用户命令/exit 尾部各占一行。用户命令独占一行是
     * {@link #commandArgvForFile} 定位质量的前提(PS PositionMessage 引用整行)。
     * 行首的 {@code ;}(exit 尾部)PS 5.1 接受(实测),保持与常量原样拼接。
     */
    static String buildScript(String command) {
        return ExecResults.POWERSHELL_PREFIX + "\n" + command + "\n"
                + ExecResults.POWERSHELL_EXIT_TAIL;
    }

    /**
     * 命令脚本落盘:{@code <workspaceRoot>/.everyagent/tmp/ea-cmd-<pid>-<纳秒>.ps1},
     * UTF-8 BOM(PS 5.1 读无 BOM 文件按系统 ACP 解码,脚本里的中文常量会错)。
     *
     * <p>命名 pid+纳秒、{@code CREATE_NEW} 独占创建,<b>绝不走 {@link Files#createTempFile}</b>
     * (其内部 SecureRandom 首次取数在无 profile 账户下实测恒 ~8s,CryptAcquireContext 超时
     * ——8s 教训,与 runner OutputFiles 同源);扩展名必须 .ps1:{@code powershell -File}
     * 拒绝其它扩展(实测 .tmp 直接报错)。纯 JDK(零 JNA),跨平台可跑。
     */
    static Path writeCommandScript(Path workspaceRoot, String command) throws IOException {
        Path dir = workspaceRoot != null
                ? workspaceRoot.resolve(".everyagent").resolve("tmp")
                : Path.of(System.getProperty("java.io.tmpdir"));
        Files.createDirectories(dir);
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] body = buildScript(command).getBytes(StandardCharsets.UTF_8);
        long pid = ProcessHandle.current().pid();
        IOException last = null;
        for (int i = 0; i < 4; i++) {
            Path p = dir.resolve("ea-cmd-" + pid + "-" + Long.toHexString(System.nanoTime())
                    + ".ps1");
            try (java.io.OutputStream out = Files.newOutputStream(p,
                    java.nio.file.StandardOpenOption.CREATE_NEW)) {
                out.write(bom);
                out.write(body);
                return p;
            } catch (java.nio.file.FileAlreadyExistsException retry) {
                last = retry;
                // 纳秒撞名,换名重试
            }
        }
        throw new IOException("无法创建命令脚本(名字冲突): " + dir, last);
    }

    /** 探测 shell：pwsh.exe → powershell.exe → cmd.exe（进程级缓存，只探测一次）。 */
    static ShellChoice detectShell() {
        ShellChoice cached = cachedShell;
        if (cached != null) {
            return cached;
        }
        synchronized (CodexCommandExecutor.class) {
            if (cachedShell != null) {
                return cachedShell;
            }
            if (findInPath("pwsh.exe") != null) {
                cachedShell = ShellChoice.PWSH;
            } else if (findInPath("powershell.exe") != null) {
                cachedShell = ShellChoice.POWERSHELL;
            } else {
                cachedShell = ShellChoice.CMD;
            }
            LOG.log(System.Logger.Level.INFO, "[shell] detected: {0}", cachedShell.exe);
            return cachedShell;
        }
    }

    /** 探测缓存（volatile 双检锁；static 全进程只探测一次）。 */
    private static volatile ShellChoice cachedShell;

    /** PATH 中查找可执行文件；找到返回绝对路径，未找到返回 null。 */
    static String findInPath(String name) {
        String pathEnv = System.getenv("Path");
        if (pathEnv == null || pathEnv.isBlank()) {
            pathEnv = System.getenv("PATH");
        }
        if (pathEnv == null || pathEnv.isBlank()) {
            return null;
        }
        String sep = java.io.File.pathSeparator;
        for (String dir : pathEnv.split(java.util.regex.Pattern.quote(sep))) {
            if (dir.isBlank()) {
                continue;
            }
            java.nio.file.Path candidate = java.nio.file.Path.of(dir, name);
            if (java.nio.file.Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().toString();
            }
        }
        return null;
    }

    /** 探测到的 shell 类型。 */
    enum ShellChoice {
        PWSH("pwsh.exe", true),
        POWERSHELL("powershell.exe", true),
        CMD("cmd.exe", false);

        final String exe;
        final boolean isPowerShell;

        ShellChoice(String exe, boolean isPowerShell) {
            this.exe = exe;
            this.isPowerShell = isPowerShell;
        }
    }

    /**
     * 继承当前环境<b>但剔除凭据形态变量</b>（§7.17「真实 key 不进事件日志」的环境侧对应物：
     * 宿主 shell 里散落的 {@code *KEY}/{@code *TOKEN}/值像 {@code sk-ant-…} 的变量不得进入沙箱,
     * 否则沙箱内任意命令 {@code Get-ChildItem Env:} 即可窃取,再随工具输出落盘）；
     * rgBinary 非空时其所在目录前置进 Path（Windows 键名优先）；
     * workspaceRoot 非空时 {@code TEMP}/{@code TMP} 显式指到工作区
     * {@code <workspaceRoot>/.everyagent/tmp}——落点必须 <b>capability 覆盖</b>：命令子进程
     * 跑在 {@code WRITE_RESTRICTED} 受限令牌下,写检查要求 restricting SIDs 也授权,
     * 普通组 ACE（如 EACodexSandboxUsers (M)）对其无效——实测 {@code <codexHome>/.sandbox/tmp}
     * 组 ACL 齐全仍被写拒,只有 setup 注入过 capability SID ACE 的工作区树可写。不覆盖则
     * 继承来的宿主 TEMP（真实用户的 %LOCALAPPDATA%\Temp）不可写,mvn/pytest 等用临时目录的
     * 工具全数 Access Denied（命令 env 是整块替换,只修 runner env 会被这里盖回去;该目录由
     * ChildProcess.OutputFiles.scratchDir 每次 spawn 时 createDirectories 保证存在）。
     * <p>profileDir 非空时 {@code USERPROFILE}/{@code HOME}/{@code APPDATA}/
     * {@code LOCALAPPDATA} 指到<b>沙箱账户真 profile</b>（{@code C:\Users\<account>},
     * 由 runner 的 LOGON_WITH_PROFILE 创建/加载）——env 型工具(git/npm/pip)按平台约定
     * 把配置/缓存放用户目录,指宿主目录则只读(写全拒)且宿主 .gitconfig/.npmrc 凭据
     * 泄露;指真 profile 则可写+隔离+账户级持久复用(依赖只冷下载一份)。JVM 系无需
     * 任何 env——user.home 走账户 profile(GetUserProfileDirectory),LOGON_WITH_PROFILE
     * 加载后自然正确。实测依据:仅授账户 Full(无组/无 capability)的目录受限令牌可写
     * (写检查=DACL 授予∩restricting SIDs,账户 user SID 两边都在)。
     */
    static Map<String, String> childEnv(Path rgBinary, Path workspaceRoot, Path profileDir) {
        Map<String, String> env = new LinkedHashMap<>(inheritEnv());
        if (rgBinary != null && rgBinary.getParent() != null) {
            String dir = rgBinary.getParent().toString();
            String key = env.containsKey("Path") ? "Path" : "PATH";
            String current = env.get(key);
            boolean present = current != null && current.toUpperCase(Locale.ROOT)
                    .contains(dir.toUpperCase(Locale.ROOT));
            env.put(key, current == null || current.isBlank() ? dir
                    : present ? current : dir + ";" + current);
        }
        if (workspaceRoot != null) {
            String tmp = workspaceRoot.resolve(".everyagent").resolve("tmp").toString();
            env.put("TEMP", tmp);
            env.put("TMP", tmp);
        }
        // JDK 18+(JEP 400)在重定向流上 System.out/stderr 默认按 native.encoding(Windows=ACP,
        // 中文机器=GBK)编码;本链路 PS 侧已统一按 UTF-8 解码(cmd-chcp 包装),JVM 的 GBK 字节
        // 直出时污染整流触发 GBK 回退(PS 字面量中文反向乱码),PS 管道捕获时被按 UTF-8 有损
        // 解成 U+FFFD(不可逆,实测 mvn 中文断言消息全损)。经 env 统一钉住 JVM 流编码;
        // 代价是每次 JVM 启动 stderr 多一行「Picked up JAVA_TOOL_OPTIONS」(可接受),
        // JDK<18 未知属性静默忽略无害;putIfAbsent 不覆盖用户显式配置
        // (ARCHITECTURE「cmd-chcp 包装」①②③之②)。
        env.putIfAbsent("JAVA_TOOL_OPTIONS", "-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8");
        if (profileDir != null) {
            env.put("USERPROFILE", profileDir.toString());
            env.put("HOME", profileDir.toString());
            env.put("APPDATA", profileDir.resolve("AppData").resolve("Roaming").toString());
            env.put("LOCALAPPDATA", profileDir.resolve("AppData").resolve("Local").toString());
        }
        if (workspaceRoot != null) {
            injectGitSafeDirectory(env, workspaceRoot);
        }
        return env;
    }

    /**
     * git safe.directory 注入（对齐 codex 原生 {@code sandbox_utils::inject_git_safe_directory}，
     * 逐语义移植）：从 workspaceRoot 向上找含 {@code .git} 的树根（{@code Files.exists}——
     * worktree/子模块的 {@code .git} 是文件），经 git 的 env 配置机制
     * {@code GIT_CONFIG_COUNT/KEY_n/VALUE_n} 注入 {@code safe.directory=<root>} 与
     * {@code <root>/*}（嵌套仓库一并信任）。
     * <p>为什么必须：仓库属主是宿主用户而命令跑在沙箱账户下,git ≥2.35.2 的 ownership
     * 保护直接 fatal（实测）；沙箱 profile 切断了对宿主 {@code .gitconfig} 的借读后,
     * 宿主曾有的 {@code safe.directory=*} 豁免不再可见（那本就是意外依赖+泄露面）。
     * <p>为什么这样而非写 {@code ~/.gitconfig}：env 注入零落盘、每次 spawn 按当时
     * workspaceRoot 重算、且精确到本仓库树——比宿主原来的 {@code *} 更收紧。已存
     * {@code GIT_CONFIG_COUNT} 时在其后追加（codex 同款）。路径用 {@code /}（git 口径）。
     */
    private static void injectGitSafeDirectory(Map<String, String> env, Path workspaceRoot) {
        Path cur = workspaceRoot.toAbsolutePath().normalize();
        while (cur != null) {
            if (Files.exists(cur.resolve(".git"))) {
                String root = cur.toString().replace('\\', '/');
                int n = 0;
                try {
                    n = Integer.parseInt(env.getOrDefault("GIT_CONFIG_COUNT", "0"));
                } catch (NumberFormatException ignore) {
                    // 宿主残留非法值,从 0 起追加(覆盖)
                }
                env.put("GIT_CONFIG_KEY_" + n, "safe.directory");
                env.put("GIT_CONFIG_VALUE_" + n, root);
                env.put("GIT_CONFIG_KEY_" + (n + 1), "safe.directory");
                env.put("GIT_CONFIG_VALUE_" + (n + 1), root + "/*");
                env.put("GIT_CONFIG_COUNT", String.valueOf(n + 2));
                return;
            }
            cur = cur.getParent();
        }
    }

    /**
     * 沙箱账户 profile 目录:{@code %SystemDrive%\Users\<onlineAccount>}（Windows 默认
     * profile 落点;由 runner 侧 LOGON_WITH_PROFILE 首启创建,此处仅推导路径）。
     */
    Path sandboxProfileDir() {
        String prefix = manager.options().accountPrefix();
        String username = SandboxAccounts.onlineUsername(prefix);
        return Path.of(System.getenv().getOrDefault("SystemDrive", "C:"), "Users", username);
    }

    /** 父环境继承 + 凭据剔除（审计只打变量名，绝不打值）；同口径供 runner 复用。 */
    static Map<String, String> inheritEnv() {
        SecretPatterns.EnvScrub scrub = SecretPatterns.scrubEnv(System.getenv());
        if (scrub.cleaned()) {
            LOG.log(System.Logger.Level.INFO, "[exec] 沙箱 env 剔除凭据变量(仅名): {0}",
                    scrub.removedNames());
        }
        return scrub.env();
    }

    /** NetworkIdentity → wire 名（SpawnRequest.network_identity：offline/online）。 */
    static String wireName(NetworkIdentity identity) {
        return identity == NetworkIdentity.OFFLINE ? "offline" : "online";
    }

    /** 追加并执行每流字节上限；返回 true 表示有内容被截掉。 */
    static boolean appendCapped(java.io.ByteArrayOutputStream out, byte[] chunk) {
        if (chunk == null || chunk.length == 0) {
            return false;
        }
        int room = ExecResults.MAX_OUTPUT_CHARS - out.size();
        if (room <= 0) {
            return true;
        }
        int n = Math.min(chunk.length, room);
        out.write(chunk, 0, n);
        return chunk.length > room;
    }

    /** stdout / [stderr] / 超时 / 中断 / 截断 / exit code 尾注（委托 {@link ExecResults}）。 */
    static String format(SessionRun run) {
        return ExecResults.format(run.stdout(), run.stderr(), run.exitCode(),
                run.timedOut(), run.interrupted(), run.truncated());
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

}