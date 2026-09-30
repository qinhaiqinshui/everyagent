package dev.everyagent.plugin.sandbox.codex.runner;

import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinNT;

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

    private CodexRunnerMain() {
    }

    public static void main(String[] args) {
        // Windows 守卫：防误用（broker 只会在 Windows 上 spawn 本类）
        if (!Platform.isWindows()) {
            System.err.println("[codex-runner] this runner only supports Windows (os.name="
                    + System.getProperty("os.name") + ")");
            System.exit(2);
        }
        String in = null;
        String out = null;
        for (String arg : args) {
            if (arg.startsWith(OPT_PIPE_IN)) {
                in = arg.substring(OPT_PIPE_IN.length());
            } else if (arg.startsWith(OPT_PIPE_OUT)) {
                out = arg.substring(OPT_PIPE_OUT.length());
            }
        }
        if (in == null || out == null) {
            System.err.println("usage: CodexRunnerMain --pipe-in=<name> --pipe-out=<name>");
            System.exit(2);
        }
        System.exit(run(in, out));
    }

    /** 0 = runner 正常走完；非 0 = 已尽力发 error 帧（对齐 codex 语义）。 */
    static int run(String pipeInName, String pipeOutName) {
        WinNT.HANDLE in = openPipe(pipeInName, WinNT.FILE_GENERIC_READ);
        WinNT.HANDLE out = openPipe(pipeOutName, WinNT.FILE_GENERIC_WRITE);
        Object writeLock = new Object(); // output 读线程与主线程共用 -out 管写端
        try {
            // 1) 先读 spawn_request（写 spawn_ready 之前必须等到）
            SpawnRequest req = readSpawnRequest(in, out, writeLock);
            if (req == null) {
                return 1;
            }
            // 2) 受限令牌 + 子进程（失败 → error(stage=spawn_child)）
            ChildProcess child = spawnChild(req, out, writeLock);
            if (child == null) {
                return 1;
            }
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
                // 4) 帧循环：输出转发 + 输入处理 + 等待退出
                runSession(in, out, writeLock, req, child);
                return 0;
            } finally {
                child.close();
            }
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    private static void runSession(WinNT.HANDLE in, WinNT.HANDLE out, Object writeLock,
            SpawnRequest req, ChildProcess child) {
        child.startOutputReaders((chunk, stderr) -> sendOutput(out, writeLock, chunk, stderr));
        Thread input = Thread.ofVirtual().name("codex-runner-input")
                .start(() -> inputLoop(in, child));
        ChildProcess.ExitResult r = child.waitForExit(req.timeoutMs());
        if (!r.terminatedCleanly()) {
            System.err.println("[codex-runner] root process did not exit after termination");
        } else {
            child.awaitOutputReaders(ChildProcess.TERMINATION_WAIT_MS); // 排空大尾巴输出
        }
        try {
            synchronized (writeLock) {
                FrameCodec.writeFrame(out, new Exit(r.exitCode(), r.timedOut()));
            }
        } catch (RuntimeException e) {
            System.err.println("[codex-runner] exit write failed: " + e.getMessage());
        }
        // input 虚拟线程随 System.exit 消亡，不阻塞退出
    }

    /** stdin/close_stdin/terminate/resize；管道 EOF 或读错 → 杀树（codex 同款）。 */
    private static void inputLoop(WinNT.HANDLE in, ChildProcess child) {
        while (true) {
            FramedMessage frame;
            try {
                frame = FrameCodec.readFrame(in);
            } catch (RuntimeException e) {
                System.err.println("[codex-runner] input read failed: " + e.getMessage());
                child.terminate();
                return;
            }
            if (frame == null) { // 父进程关闭管道 → 会话终结
                child.terminate();
                return;
            }
            switch (frame.message()) {
                case IpcMessage.Stdin s -> child.writeStdin(IpcMessage.decodeBytes(s.dataBase64()));
                case IpcMessage.CloseStdin c -> child.closeStdin();
                case IpcMessage.Terminate t -> child.terminate();
                case IpcMessage.Resize r -> { /* no-op：ConPTY 暂缓（设计 §1.2），帧合法仅忽略 */ }
                default -> { /* spawn_request/spawn_ready/output/exit/error：非输入帧，忽略 */ }
            }
        }
    }

    private static SpawnRequest readSpawnRequest(WinNT.HANDLE in, WinNT.HANDLE out,
            Object writeLock) {
        FramedMessage frame;
        try {
            frame = FrameCodec.readFrame(in);
        } catch (RuntimeException e) {
            sendError(out, writeLock, ErrorStage.READ_SPAWN_REQUEST, null,
                    "read spawn_request failed: " + e.getMessage());
            return null;
        }
        if (frame == null) {
            sendError(out, writeLock, ErrorStage.READ_SPAWN_REQUEST, null,
                    "runner pipe closed before spawn_request");
            return null;
        }
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
