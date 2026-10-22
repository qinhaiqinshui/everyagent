package dev.everyagent.plugin.sandbox.codex.runner;

import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinNT;

import java.nio.charset.StandardCharsets;

import dev.everyagent.plugin.api.shell.ExecResults;
import dev.everyagent.plugin.sandbox.codex.runner.FrameCodec.FramedMessage;
import dev.everyagent.plugin.sandbox.codex.runner.IpcMessage.Error;
import dev.everyagent.plugin.sandbox.codex.runner.IpcMessage.ErrorStage;
import dev.everyagent.plugin.sandbox.codex.runner.IpcMessage.Exit;
import dev.everyagent.plugin.sandbox.codex.runner.IpcMessage.Output;
import dev.everyagent.plugin.sandbox.codex.runner.IpcMessage.SpawnRequest;
import dev.everyagent.plugin.sandbox.codex.runner.IpcMessage.SpawnReady;
import dev.everyagent.plugin.sandbox.codex.win.Kernel32Ex;

/**
 * 沙箱内 runner 进程入口（设计文档 §2.7/§4，对齐 codex src/bin/command_runner/win.rs）。
 *
 * <p>以沙箱账户身份运行：连接 broker 预建的两条命名管道（普通客户端 CreateFileW），
 * 先读 {@code spawn_request}（版本必须 6），从自身令牌派生受限令牌（capability SID），
 * CreateProcessAsUserW 启动子进程（Job 原子挂接 + 句柄白名单），回 {@code spawn_ready}，
 * 然后进入帧循环：stdin→子进程管道、close_stdin→关写端、terminate→杀树、resize→no-op
 * （ConPTY 暂缓）；stdout/stderr 双线程转发 {@code output} 帧；子进程退出后发
 * {@code exit} 帧并以 0 退出（runner 自身正常与否；子进程退出码走 exit 帧——与 codex
 * 略有差异，runner 退出码语义按本插件设计）。启动失败发 {@code error} 帧并非零退出。
 *
 * <p>用法：{@code java dev.everyagent.plugin.sandbox.codex.runner.CodexRunnerMain
 * --pipe-in=\\.\pipe\every-agent-codex-runner-<nonce>-in
 * --pipe-out=\\.\pipe\every-agent-codex-runner-<nonce>-out}
 */
public final class CodexRunnerMain {

    private static final String OPT_PIPE_IN = "--pipe-in=";
    private static final String OPT_PIPE_OUT = "--pipe-out=";
    /** 类加载时刻（≈main 入口）；stage 计时基准。 */
    private static final long T0 = System.nanoTime();
    /**
     * 进程创建时刻（epoch ms，OS 口径）；用于测 JVM boot（进程创建 → T0）。
     * startInstant 精度为 ms 且可能为空，取不到则 0。
     */
    private static final long PROCESS_START_MILLIS = ProcessHandle.current().info()
            .startInstant().map(java.time.Instant::toEpochMilli).orElse(0L);
    private static final long T0_WALL_MILLIS = System.currentTimeMillis();

    /** tee 就绪前的打点缓冲（此时 System.err 无控制台，直写会进黑洞）。 */
    private static final java.util.List<String> PENDING = new java.util.ArrayList<>();
    private static volatile boolean teeReady;

    /**
     * 控制台码页探测开关：默认随 EA_CONPROBE（非 "0" 即启用）。声明为可写静态而非每次
     * 读 env，是为了同 JVM 内嵌跑 runner 主循环的冒烟测试能显式关掉探测（探测会 spawn
     * powershell 探针，与被测的多命令会话互不干扰；生产路径行为不变）。
     */
    static volatile boolean consoleProbeEnabled = !"0".equals(System.getenv("EA_CONPROBE"));

    /** 测试钩子：关闭本 JVM 内的 runner 控制台码页探测。 */
    static void disableConsoleProbeForTest() {
        consoleProbeEnabled = false;
    }

    private CodexRunnerMain() {
    }

    /** 阶段耗时打点（ms，进 runner-stderr.log；定位启动链路瓶颈用）。 */
    private static void stage(String msg) {
        String line = "[codex-runner] +" + ((System.nanoTime() - T0) / 1_000_000L) + "ms " + msg;
        if (teeReady) {
            System.err.println(line);
        } else {
            synchronized (PENDING) {
                PENDING.add(line);
            }
        }
    }

    /** tee 装好后补打 boot 行 + 冲刷缓冲。 */
    private static void flushPending() {
        teeReady = true;
        if (PROCESS_START_MILLIS > 0) {
            long boot = T0_WALL_MILLIS - PROCESS_START_MILLIS;
            System.err.println("[codex-runner] +" + boot + "ms jvm boot (process create"
                    + " → class init)");
        }
        synchronized (PENDING) {
            for (String line : PENDING) {
                System.err.println(line);
            }
            PENDING.clear();
        }
    }

    public static void main(String[] args) {
        stage("main entered");
        // Windows 守卫：防误用（broker 只会在 Windows 上 spawn 本类）
        if (!Platform.isWindows()) {
            System.err.println("[codex-runner] this runner only supports Windows (os.name="
                    + System.getProperty("os.name") + ")");
            System.exit(2);
        }
        stage("Platform.isWindows ok (JNA core loaded)");
        // stderr tee：沙箱进程无控制台，JVM/未捕获异常输出默认进黑洞（对齐排障需要，
        // broker 侧 CREATE_NO_WINDOW）。重定向 System.err 到 %TEMP%\runner-stderr.log
        //（broker 已把 TEMP 指向 <codexHome>/.sandbox/tmp），保留原 stderr 双写。
        // 任何 tee 失败静默降级——诊断日志绝不改变 runner 行为。
        String in = null;
        String out = null;
        for (String arg : args) {
            if (arg.startsWith(OPT_PIPE_IN)) {
                in = arg.substring(OPT_PIPE_IN.length());
            } else if (arg.startsWith(OPT_PIPE_OUT)) {
                out = arg.substring(OPT_PIPE_OUT.length());
            }
        }
        installStderrTee();
        flushPending();
        stage("stderr tee installed");
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            System.err.println("[codex-runner] uncaught in " + t.getName() + ": " + e);
            e.printStackTrace(System.err);
            System.err.flush();
        });
        if (in == null || out == null) {
            System.err.println("usage: CodexRunnerMain --pipe-in=<name> --pipe-out=<name>");
            System.exit(2);
        }
        System.err.println("[codex-runner] start pipes in=" + in + " out=" + out);
        // 控制台码页探测（ConsoleProbe）刻意不接在启动路径上。2026-10-05 实测回归:
        // 探测放在此处(main → run 之前)会顶破 broker 的 15s 管道连接窗口,症状是
        //   [codex sandbox 执行失败] timed out after 15000ms connecting \\.\pipe\...-in
        // ——沙箱整体起不来。元凶不是探测本身的耗时,而是它把 JNA jnidispatch.dll 的
        // 首次 unpack+load(本环境实测约 8s,见 run() 的预热注释与既有排障记录)提前到了
        // openPipe 之前,启动预算翻倍;若本机控制台 API 可用,还要再叠两次 powershell 探针
        // spawn(每次预算 25s)。真要评估"继承控制台"这条路,接入位置必须是
        // run() 内 stage("pipes opened") 之后、且默认关闭由环境变量开启,绝不能再压到
        // 管道连接之前。非 ASCII 正确性的兜底是文件承载(ChildProcess.OutputFiles,直出路径);
        // PS 管道内捕获路径的正解是控制台码页探测(2026-10 起默认启用,EA_CONPROBE=0 可关,
        // 接入点见 run() 内 stage("pipes opened") 之后),rg 命令名特判包装(plugin-api RgShim)已删除。
        System.exit(run(in, out));
    }

    /** System.err → 文件 tee（追加；失败静默）。 */
    private static void installStderrTee() {
        try {
            String tempDir = System.getenv("TEMP");
            if (tempDir == null || tempDir.isBlank()) {
                tempDir = System.getProperty("java.io.tmpdir");
            }
            java.nio.file.Path log = java.nio.file.Path.of(tempDir, "runner-stderr.log");
            java.nio.file.Files.writeString(log,
                    "---- runner session " + java.time.LocalTime.now()
                            + " pid=" + ProcessHandle.current().pid() + " ----"
                            + System.lineSeparator(),
                    java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
            java.io.PrintStream original = System.err;
            java.io.PrintStream tee = new java.io.PrintStream(
                    new java.io.FileOutputStream(log.toFile(), true), true,
                    java.nio.charset.StandardCharsets.UTF_8) {
                @Override
                public void println(String x) {
                    original.println(x);
                    super.println(x);
                }

                @Override
                public void print(String x) {
                    original.print(x);
                    super.print(x);
                }
            };
            System.setErr(tee);
        } catch (Throwable ignored) {
            // 静默降级：诊断输出不可用时保留原行为
        }
    }

    /** 0 = runner 正常走完；非 0 = 已尽力发 error 帧（对齐 codex 语义）。 */
    static int run(String pipeInName, String pipeOutName) {
        // 关键切分：JNA 首次 native 调用会触发 jnidispatch.dll 从 jar 解压到
        // java.io.tmpdir 再 LoadLibrary（沙箱账户私有 temp，疑为 EDR 强查点）。
        // 单独预热以把它与 CreateFileW 的阻塞时长分开归因。
        System.err.println("[codex-runner] tmpdir=" + System.getProperty("java.io.tmpdir")
                + " TEMP=" + System.getenv("TEMP"));
        stage("warming jna native");
        Kernel32.INSTANCE.GetCurrentProcess();
        stage("jna native warmed (jnidispatch unpack+load done)");
        WinNT.HANDLE in = openPipe(pipeInName, WinNT.FILE_GENERIC_READ);
        stage("pipe-in connected (CreateFileW in)");
        WinNT.HANDLE out = openPipe(pipeOutName, WinNT.FILE_GENERIC_WRITE);
        stage("pipes opened");
        // 控制台码页探测：默认启用，EA_CONPROBE=0 显式关闭；必须在管道已连接之后、
        // 并在后台线程里跑——它既不能压 pipe-connect 预算，也不能压 spawn_ready 预算。
        // 结论落地是"后续命令的 spawn 形态可能变化"(探测完成前的首条命令仍按现状 spawn,
        // 可接受)；复测不通过自动回退 CREATE_NO_WINDOW(见 ConsoleProbe)。
        if (consoleProbeEnabled) {
            Thread.ofVirtual().name("codex-console-probe").start(() -> {
                try {
                    ConsoleProbe.runOnce(java.nio.file.Path.of(System.getProperty("user.dir")));
                    // 结论落地:由 ChildProcess 自己的字段承接(它不在 spawn 路径上引用本类)
                    ChildProcess.setInheritConsoleMode(ConsoleProbe.inheritConsole());
                    System.err.println("[codex-runner] console probe: verdict="
                            + ConsoleProbe.verdict()
                            + " inheritConsole=" + ConsoleProbe.inheritConsole()
                            + " cp=" + ConsoleProbe.cpAtStart() + "->" + ConsoleProbe.cpNow());
                    System.err.flush();
                } catch (Throwable t) {
                    System.err.println("[codex-runner] console probe failed: " + t);
                    System.err.flush();
                }
            });
        }
        Object writeLock = new Object(); // output 读线程与主线程共用 -out 管写端
        try {
            // 池化多命令会话：单一读线程终身持有 -in 管道做帧分发（消除主线程/输入线程
            // 争读同一管道的竞态），主线程串行执行命令（spawn_request → … → exit 后回到
            // 等待下一条 spawn_request）；shutdown 帧或管道 EOF 终结整个 runner。
            RunnerState state = new RunnerState();
            Thread.ofVirtual().name("codex-runner-frames")
                    .start(() -> frameReaderLoop(in, state));
            return commandLoop(out, writeLock, state);
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    /**
     * runner 生命周期状态：读线程（唯一的 -in 管道读者）与命令执行主线程之间的交接面。
     * 命令队列容量 1 ——「每命令独占」由 worker 侧 RunnerPool 保证，runner 侧对并发
     * 第二条 spawn_request 按协议违规处理（杀子进程退出）而非排队积压。
     */
    static final class RunnerState {
        /** 投毒哨兵：读线程终结（EOF/读错/shutdown）后唤醒执行线程收场。 */
        private static final FramedMessage POISON = new FramedMessage(-1, new IpcMessage.Terminate());

        private final java.util.concurrent.LinkedBlockingQueue<FramedMessage> commands =
                new java.util.concurrent.LinkedBlockingQueue<>(1);
        private final java.util.concurrent.atomic.AtomicReference<ChildProcess> current =
                new java.util.concurrent.atomic.AtomicReference<>();
        private volatile boolean stopped;

        /** 读线程投递新命令；已终结或上一条仍在队列 → false（协议违规）。 */
        boolean offerCommand(FramedMessage frame) {
            if (stopped) {
                return false;
            }
            return commands.offer(frame);
        }

        /** 阻塞取下一条命令；投毒/中断返回 null（runner 收场）。 */
        FramedMessage takeCommand() {
            try {
                FramedMessage f = commands.take();
                return f == POISON ? null : f;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }

        /** 读线程终结：终止当前子进程（父管道关闭 = 会话终结，codex 同款）并投毒。 */
        void end() {
            stopped = true;
            ChildProcess child = current.get();
            if (child != null) {
                child.terminate();
            }
            // 父进程已离开：队列里未被取走的命令没有执行意义，清掉保证投毒必然落位
            commands.clear();
            commands.offer(POISON);
        }
    }

    /**
     * 帧读分发线程（runner 生命周期内唯一读 -in 管道的线程）：stdin/close_stdin/
     * terminate/resize 路由到当前子进程；spawn_request 入命令队列；shutdown/EOF/读错
     * → 终结。命令间无共享可变状态（每条命令的子进程/令牌均新建），串扰面为零。
     */
    private static void frameReaderLoop(WinNT.HANDLE in, RunnerState state) {
        while (true) {
            FramedMessage frame;
            try {
                frame = FrameCodec.readFrame(in);
            } catch (RuntimeException e) {
                System.err.println("[codex-runner] input read failed: " + e.getMessage());
                state.end();
                return;
            }
            if (frame == null) { // 父进程关闭管道 → 会话终结
                System.err.println("[codex-runner] parent pipe closed; stopping");
                state.end();
                return;
            }
            ChildProcess child = state.current.get();
            switch (frame.message()) {
                case IpcMessage.Stdin s -> {
                    if (child != null) {
                        child.writeStdin(IpcMessage.decodeBytes(s.dataBase64()));
                    }
                }
                case IpcMessage.CloseStdin c -> {
                    if (child != null) {
                        child.closeStdin();
                    }
                }
                case IpcMessage.Terminate t -> {
                    if (child != null) {
                        child.terminate();
                    }
                }
                case IpcMessage.Resize r -> { /* no-op：ConPTY 暂缓（设计 §1.2），帧合法仅忽略 */ }
                case IpcMessage.SpawnRequest req -> {
                    if (!state.offerCommand(frame)) { // 上一条仍在执行/队列：协议违规
                        System.err.println("[codex-runner] spawn_request while busy"
                                + " (pooling exclusive-use violation); stopping");
                        state.end();
                        return;
                    }
                }
                case IpcMessage.Shutdown s -> { // 池化回收信号：优雅退出
                    System.err.println("[codex-runner] shutdown frame received; exiting");
                    state.end();
                    return;
                }
                default -> { /* spawn_ready/output/exit/error：非输入帧，忽略 */ }
            }
        }
    }

    /**
     * 命令执行主循环：取 spawn_request → 受限令牌 + 子进程 → spawn_ready → 帧会话 →
     * exit 帧 → 回到等待下一条。旧单命令语义兼容：无 shutdown 帧时，父进程在 exit 后
     * 关闭管道（旧 worker 行为）→ 读线程 EOF 投毒 → 本循环退出，行为与旧版一致。
     * 启动失败（读帧/派生/写 spawn_ready）发 error 帧并返回 1（对齐旧版）。
     */
    private static int commandLoop(WinNT.HANDLE out, Object writeLock, RunnerState state) {
        while (true) {
            FramedMessage frame = state.takeCommand();
            if (frame == null) {
                return 0; // shutdown / 管道 EOF：runner 干净收场
            }
            SpawnRequest req = validateSpawnRequest(frame, out, writeLock);
            if (req == null) {
                return 1;
            }
            stage("spawn_request read");
            // 2) 受限令牌 + 子进程（失败 → error(stage=spawn_child)）
            ChildProcess child = spawnChild(req, out, writeLock);
            if (child == null) {
                return 1;
            }
            stage("token+child spawned pid=" + child.processId());
            state.current.set(child);
            try {
                if (!req.stdinOpen()) {
                    child.closeStdin(); // stdin 关闭 = 子进程读 EOF
                }
                // 3) spawn_ready（失败 → error(stage=write_spawn_ready)）
                try {
                    synchronized (writeLock) {
                        FrameCodec.writeFrame(out, new SpawnReady(child.processId()));
                    }
                } catch (RuntimeException e) {
                    sendError(out, writeLock, ErrorStage.WRITE_SPAWN_READY, null,
                            "write spawn_ready failed: " + e.getMessage());
                    return 1;
                }
                stage("spawn_ready sent");
                // 4) 帧会话：输出转发 + 等待退出（输入处理在读线程）
                runSession(out, writeLock, req, child);
            } finally {
                state.current.compareAndSet(child, null);
                child.close();
            }
        }
    }

    private static void runSession(WinNT.HANDLE out, Object writeLock,
            SpawnRequest req, ChildProcess child) {
        java.util.concurrent.atomic.AtomicBoolean firstOutput =
                new java.util.concurrent.atomic.AtomicBoolean();
        child.startOutputReaders((chunk, stderr) -> {
            if (firstOutput.compareAndSet(false, true)) {
                stage("first output");
            }
            sendOutput(out, writeLock, chunk, stderr);
        });
        ChildProcess.ExitResult r = child.waitForExit(req.timeoutMs());
        stage("child exited rc=" + r.exitCode() + " timedOut=" + r.timedOut());
        if (!r.terminatedCleanly()) {
            System.err.println("[codex-runner] root process did not exit after termination");
        } else if (!child.awaitOutputReaders(ChildProcess.TERMINATION_WAIT_MS)) {
            // 排空大尾巴输出;未在宽限内排空 = 尾部字节可能缺失,必须让模型知情(ISSUES 一.2)
            sendNote(out, writeLock, ExecResults.DRAIN_TIMEOUT_NOTE);
        }
        if (child.degradedToPipes()) {
            // 文件承载创建失败换轨了管道承载:承载语义已变,不能无人知晓(ISSUES 一.3)
            sendNote(out, writeLock, ExecResults.CARRIER_FALLBACK_NOTE);
        }
        try {
            synchronized (writeLock) {
                FrameCodec.writeFrame(out, new Exit(r.exitCode(), r.timedOut()));
            }
        } catch (RuntimeException e) {
            System.err.println("[codex-runner] exit write failed: " + e.getMessage());
        }
        stage("exit frame sent");
        // 读线程随 run() 收口消亡;主线程回到等待下一条 spawn_request
    }

    /**
     * 把单行降级/告警提示以 Output 帧(stderr 流)送出。必须在 Exit 帧<b>之前</b>调用
     * ——worker 侧收帧循环见到 Exit 即收口,晚于它的 Output 帧没人读。提示常量取自
     * {@link ExecResults}(编译期内联,runner 物化 classpath 不因此新增 jar 依赖)。
     */
    private static void sendNote(WinNT.HANDLE out, Object writeLock, String note) {
        try {
            sendOutput(out, writeLock, (note + "\n").getBytes(StandardCharsets.UTF_8), true);
        } catch (RuntimeException e) {
            System.err.println("[codex-runner] note write failed: " + e.getMessage());
        }
    }

    /** 校验已读入的 spawn_request 帧（版本/类型/必填字段；失败发 error 帧返回 null）。 */
    private static SpawnRequest validateSpawnRequest(FramedMessage frame, WinNT.HANDLE out,
            Object writeLock) {
        if (frame.version() != IpcMessage.IPC_PROTOCOL_VERSION) {
            sendError(out, writeLock, ErrorStage.READ_SPAWN_REQUEST, null,
                    "runner: unsupported protocol version " + frame.version());
            return null;
        }
        if (!(frame.message() instanceof SpawnRequest req)) {
            sendError(out, writeLock, ErrorStage.READ_SPAWN_REQUEST, null,
                    "runner: expected spawn_request, got " + frame.message().tag());
            return null;
        }
        if (req.command().isEmpty() || req.capSids().isEmpty()) {
            sendError(out, writeLock, ErrorStage.READ_SPAWN_REQUEST, null,
                    "runner: spawn_request missing command or cap_sids");
            return null;
        }
        return req;
    }

    private static ChildProcess spawnChild(SpawnRequest req, WinNT.HANDLE out, Object writeLock) {
        try {
            WinNT.HANDLE token = SandboxTokenFactory.createRestrictedTokenWithCaps(req.capSids());
            stage("restricted token derived");
            try {
                return ChildProcess.spawn(token, req.command(), req.cwd(), req.env(),
                        desktopFor(req.privateDesktopName()));
            } finally {
                closeQuietly(token);
            }
        } catch (Win32Exception e) {
            sendError(out, writeLock, ErrorStage.SPAWN_CHILD, e.lastError(), e.getMessage());
            return null;
        } catch (RuntimeException e) {
            sendError(out, writeLock, ErrorStage.SPAWN_CHILD, null, e.getMessage());
            return null;
        }
    }

    /** 私有桌面名 → lpDesktop（父进程跨 runner 存活的桌面，Winsta0 前缀）。 */
    private static String desktopFor(String privateDesktopName) {
        return privateDesktopName == null ? null : "Winsta0\\" + privateDesktopName;
    }

    private static void sendOutput(WinNT.HANDLE out, Object writeLock, byte[] chunk, boolean stderr) {
        try {
            synchronized (writeLock) {
                FrameCodec.writeFrame(out,
                        new Output(IpcMessage.encodeBytes(chunk),
                                stderr ? IpcMessage.Stream.STDERR : IpcMessage.Stream.STDOUT));
            }
        } catch (RuntimeException e) {
            System.err.println("[codex-runner] output write failed: " + e.getMessage());
        }
    }

    /** 尽力而为发 error 帧（对齐 codex send_error：失败不再追错）。 */
    private static void sendError(WinNT.HANDLE out, Object writeLock, ErrorStage stage,
            Integer windowsErrorCode, String message) {
        try {
            synchronized (writeLock) {
                FrameCodec.writeFrame(out, new Error(message, stage, windowsErrorCode));
            }
        } catch (RuntimeException ignored) {
            // 管道已不可用
        }
    }

    /** CreateFileW 连接命名管道（普通客户端，OPEN_EXISTING）。 */
    private static WinNT.HANDLE openPipe(String name, int desiredAccess) {
        WinNT.HANDLE h = Kernel32.INSTANCE.CreateFile(name, desiredAccess, 0, null,
                WinNT.OPEN_EXISTING, 0, null);
        if (h == null || h.getPointer() == null
                || Pointer.nativeValue(h.getPointer()) == -1
                || Pointer.nativeValue(h.getPointer()) == 0) {
            throw Win32Exception.of("CreateFileW(pipe " + name + ")");
        }
        return h;
    }

    private static void closeQuietly(WinNT.HANDLE h) {
        if (h != null && h.getPointer() != null && Pointer.nativeValue(h.getPointer()) != 0) {
            Kernel32Ex.INSTANCE.CloseHandle(h);
        }
    }
}
