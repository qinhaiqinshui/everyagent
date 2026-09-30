package dev.everyagent.plugin.sandbox.codex.session;

import com.sun.jna.platform.win32.Advapi32;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;

import dev.everyagent.plugin.sandbox.codex.accounts.SandboxAccounts;
import dev.everyagent.plugin.sandbox.codex.accounts.SandboxSecrets;
import dev.everyagent.plugin.sandbox.codex.runner.EnvBlock;
import dev.everyagent.plugin.sandbox.codex.runner.FrameCodec;
import dev.everyagent.plugin.sandbox.codex.runner.IpcMessage;
import dev.everyagent.plugin.sandbox.codex.runner.RunnerPaths;
import dev.everyagent.plugin.sandbox.codex.runner.Win32Exception;
import dev.everyagent.plugin.sandbox.codex.setup.SandboxDirs;
import dev.everyagent.plugin.sandbox.codex.setup.SetupPayload;
import dev.everyagent.plugin.sandbox.codex.win.Kernel32Ex;
import dev.everyagent.plugin.sandbox.codex.win.WinErr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * runner 进程拉起与通道（设计文档 §4.1/§4.3，对齐 codex runner_client.rs）。
 *
 * <p>{@link CreateProcessWithLogonW}：账户=沙箱用户名、域 {@code "."}、密码 =
 * {@link SandboxSecrets#readPassword} 解出的明文（JNA UNICODE_OPTIONS 把 String/WString
 * 直接按 UTF-16LE 编组，全程不经平台默认 charset）；旗标
 * CREATE_NO_WINDOW|CREATE_UNICODE_ENVIRONMENT、STARTF_FORCEOFFFEEDBACK、不传
 * LOGON_WITH_PROFILE（无 execution alias，免建 profile）；runner 的 TEMP/TMP 显式指到
 * {@code <codexHome>/.sandbox/tmp}；spawn 前后 SetErrorMode(0x3) 保存恢复。
 *
 * <p>失败分类（对齐 runner_client.rs::retry_runner_spawn_once /
 * is_refreshable_windows_error）：1056（Secondary Logon 服务忙）原凭据重试一次；
 * 1326/1331/1387（+codex 同类的 1312）凭据类 → {@link CredentialMismatchException}
 * （上层引导重新 setup，不做首期自动密码轮换）。握手失败 TerminateProcess 收尸。
 */
public final class RunnerClient {

    /** 连接与 spawn_ready 等待上限（RUNNER_PIPE_CONNECT_TIMEOUT / RUNNER_SPAWN_READY_TIMEOUT = 15s）。 */
    public static final long PIPE_CONNECT_TIMEOUT_MS = 15_000;
    public static final long SPAWN_READY_TIMEOUT_MS = 15_000;
    /** codex RUNNER_ERROR_MODE_FLAGS = 0x1|0x2。 */
    private static final int RUNNER_ERROR_MODE_FLAGS = 0x0001 | 0x0002;
    /** 凭据类失败码（任务口径 1326/1331/1387 + codex is_refreshable 的 1312）。 */
    private static final List<Integer> CREDENTIAL_MISMATCH_CODES = List.of(
            WinErr.ERROR_LOGON_FAILURE, WinErr.ERROR_ACCOUNT_DISABLED,
            WinErr.ERROR_NO_SUCH_MEMBER, WinErr.ERROR_NO_SUCH_LOGON_SESSION);

    private RunnerClient() {
    }

    /** 沙箱账户凭据失配——引导重新 setup（对齐 SandboxAccountCredentialMismatch 语义）。 */
    public static final class CredentialMismatchException extends RuntimeException {
        private final int windowsErrorCode;

        public CredentialMismatchException(String username, int windowsErrorCode) {
            super("sandbox account credential mismatch for " + username
                    + " (Windows error " + windowsErrorCode + "); re-run setup");
            this.windowsErrorCode = windowsErrorCode;
        }

        public int windowsErrorCode() {
            return windowsErrorCode;
        }
    }

    /** runner 启动配置（凭据 + 已物化 classpath + java.home）。 */
    public record RunnerConfig(Path codexHome, String username, String password,
            String classpath, String javaHome, String workingDirectory) {
    }

    /** 凭据装载：DPAPI 解密码 → String（UTF-8 解码已在 readPassword 完成）。 */
    public static String loadPassword(Path codexHome, String username) throws IOException {
        return SandboxSecrets.readPassword(codexHome, SetupPayload.SETUP_VERSION, username);
    }

    /**
     * 拉起 runner 并建立双管道通道（不含 spawn_request 握手——由会话层做）。
     * 任何失败：TerminateProcess(hProcess,1) 收尸 + 关闭已建管道（fail-closed）。
     */
    public static Channel start(RunnerConfig cfg) throws IOException {
        String nonce = RunnerPaths.newNonce();
        String inName = RunnerPaths.inPipeName(nonce);
        String outName = RunnerPaths.outPipeName(nonce);
        String sandboxSid = SandboxAccounts.sidString(cfg.username());
        RunnerPipe in = null;
        RunnerPipe out = null;
        try {
            in = RunnerPipe.create(inName, RunnerPaths.PIPE_ACCESS_OUTBOUND, sandboxSid);
            out = RunnerPipe.create(outName, RunnerPaths.PIPE_ACCESS_INBOUND, sandboxSid);
            WinBase.PROCESS_INFORMATION pi = spawnWithLogon(cfg, inName, outName);
            try {
                in.connect(pi.dwProcessId.intValue(), PIPE_CONNECT_TIMEOUT_MS);
                out.connect(pi.dwProcessId.intValue(), PIPE_CONNECT_TIMEOUT_MS);
                return new Channel(in, out, pi);
            } catch (IOException | RuntimeException e) {
                Kernel32Ex.INSTANCE.TerminateProcess(pi.hProcess, 1); // 收尸
                throw e;
            } finally {
                closeQuietly(pi.hThread);
            }
        } catch (IOException | RuntimeException e) {
            if (in != null) {
                in.close();
            }
            if (out != null) {
                out.close();
            }
            throw e;
        }
    }

    /** CreateProcessWithLogonW + 1056 原凭据重试 + 凭据类失败分类。 */
    private static WinBase.PROCESS_INFORMATION spawnWithLogon(RunnerConfig cfg,
            String pipeInName, String pipeOutName) throws IOException {
        for (int attempt = 1; attempt <= 2; attempt++) {
            int previousErrorMode = Kernel32.INSTANCE.SetErrorMode(RUNNER_ERROR_MODE_FLAGS);
            WinBase.PROCESS_INFORMATION pi;
            int failure;
            try {
                pi = new WinBase.PROCESS_INFORMATION();
                List<String> argv = runnerArgv(cfg.javaHome(), cfg.classpath(),
                        pipeInName, pipeOutName);
                String cmdline = joinCommandLine(argv);
                boolean ok = Advapi32.INSTANCE.CreateProcessWithLogonW(
                        cfg.username(),
                        ".",
                        cfg.password(), // JNA unicode 映射 → UTF-16LE，不经默认 charset
                        0, // 不传 LOGON_WITH_PROFILE（无 execution alias）
                        argv.get(0), // lpApplicationName = java.exe 绝对路径（对齐 codex）
                        cmdline,
                        Kernel32Ex.CREATE_NO_WINDOW | WinBase.CREATE_UNICODE_ENVIRONMENT,
                        EnvBlock.makeEnvBlock(runnerEnvironment(cfg.codexHome())),
                        cfg.workingDirectory(),
                        startupInfo(),
                        pi);
                if (ok) {
                    return pi;
                }
                failure = Kernel32.INSTANCE.GetLastError();
                closeQuietly(pi.hThread);
                closeQuietly(pi.hProcess);
            } finally {
                Kernel32.INSTANCE.SetErrorMode(previousErrorMode);
            }
            classifyLogonFailure(cfg.username(), failure);
            if (failure == WinErr.ERROR_SERVICE_ALREADY_RUNNING && attempt == 1) {
                continue; // 1056：原凭据重试一次（对齐 retry_runner_spawn_once）
            }
            throw new IOException("CreateProcessWithLogonW failed for runner: " + failure);
        }
        throw new IOException("CreateProcessWithLogonW failed for runner (retried)");
    }

    /** STARTUPINFO：cb + STARTF_FORCEOFFFEEDBACK（对齐 runner_client.rs；无桌面字段）。 */
    private static WinBase.STARTUPINFO startupInfo() {
        WinBase.STARTUPINFO si = new WinBase.STARTUPINFO();
        si.cb = new com.sun.jna.platform.win32.WinDef.DWORD(si.size());
        si.dwFlags |= WinBase.STARTF_FORCEOFFFEEDBACK;
        return si;
    }

    /** 凭据失败分类：凭据类抛 CredentialMismatchException，其余留给调用方处理。 */
    static void classifyLogonFailure(String username, int code) {
        if (CREDENTIAL_MISMATCH_CODES.contains(code)) {
            throw new CredentialMismatchException(username, code);
        }
    }

    /** 纯函数（可测）：runner 命令行参数（JVM flags 对齐设计 §4.1）。 */
    public static List<String> runnerArgv(String javaHome, String classpath,
            String pipeInName, String pipeOutName) {
        // Windows 语义固定反斜杠（runner 仅在 Windows 上被拉起；跨平台单测断言此形态）
        String home = javaHome.endsWith("\\") || javaHome.endsWith("/")
                ? javaHome.substring(0, javaHome.length() - 1) : javaHome;
        String javaExe = home + "\\bin\\java.exe";
        return List.of(javaExe,
                "-XX:+UseSerialGC", "-Xshare:auto", "-Dfile.encoding=UTF-8",
                "-cp", classpath,
                RunnerMaterializer.RUNNER_MAIN,
                "--pipe-in=" + pipeInName,
                "--pipe-out=" + pipeOutName);
    }

    /** runner 环境变量：继承当前环境 + TEMP/TMP → {@code <codexHome>/.sandbox/tmp}。 */
    static Map<String, String> runnerEnvironment(Path codexHome) throws IOException {
        Path tmp = SandboxDirs.sandboxDir(codexHome).resolve("tmp");
        Files.createDirectories(tmp);
        Map<String, String> env = new LinkedHashMap<>(System.getenv());
        env.put("TEMP", tmp.toString());
        env.put("TMP", tmp.toString());
        return env;
    }

    /** 命令行合成（对齐 winutil.rs::argv_to_command_line）。 */
    static String joinCommandLine(List<String> argv) {
        StringBuilder sb = new StringBuilder();
        for (String arg : argv) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(quoteWindowsArg(arg));
        }
        return sb.toString();
    }

    /** 单参数 CRT 引号转义（对齐 winutil.rs::quote_windows_arg；与 runner/setup 同款实现）。 */
    static String quoteWindowsArg(String arg) {
        boolean needsQuotes = arg.isEmpty();
        for (int i = 0; i < arg.length() && !needsQuotes; i++) {
            char c = arg.charAt(i);
            needsQuotes = c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\"';
        }
        if (!needsQuotes) {
            return arg;
        }
        StringBuilder quoted = new StringBuilder(arg.length() + 2).append('\"');
        int backslashes = 0;
        for (int i = 0; i < arg.length(); i++) {
            char ch = arg.charAt(i);
            if (ch == '\\') {
                backslashes++;
            } else if (ch == '\"') {
                quoted.append("\\".repeat(backslashes * 2 + 1)).append('\"');
                backslashes = 0;
            } else {
                if (backslashes > 0) {
                    quoted.append("\\".repeat(backslashes));
                    backslashes = 0;
                }
                quoted.append(ch);
            }
        }
        if (backslashes > 0) {
            quoted.append("\\".repeat(backslashes * 2));
        }
        return quoted.append('\"').toString();
    }

    private static void closeQuietly(WinNT.HANDLE h) {
        if (h != null && h.getPointer() != null) {
            Kernel32Ex.INSTANCE.CloseHandle(h);
        }
    }

    /** 已连接的 runner 通道（AutoCloseable：进程句柄 + 双管道）。 */
    public static final class Channel implements AutoCloseable {
        private final RunnerPipe in; // 父写 runner 读
        private final RunnerPipe out; // runner 写父读
        private final WinBase.PROCESS_INFORMATION pi;

        Channel(RunnerPipe in, RunnerPipe out, WinBase.PROCESS_INFORMATION pi) {
            this.in = in;
            this.out = out;
            this.pi = pi;
        }

        /** runner 进程 PID（PID 校验与诊断用）。 */
        public int runnerPid() {
            return pi.dwProcessId.intValue();
        }

        /** 父→runner 写一帧。 */
        public void send(IpcMessage message) {
            FrameCodec.writeFrame(in.handle(), message);
        }

        /** runner→父 阻塞读一帧；帧边界 EOF 返回 null。 */
        public FrameCodec.FramedMessage readFrame() {
            return FrameCodec.readFrame(out.handle());
        }

        /**
         * 限时读一帧：PeekNamedPipe 5ms 轮询等<b>完整帧</b>就绪再读
         * （对齐 framed_io.rs::wait_for_complete_frame + 15s spawn_ready 上限）。
         */
        public FrameCodec.FramedMessage readFrame(long timeoutMs) throws IOException {
            waitForCompleteFrame(out.handle(), timeoutMs);
            return readFrame();
        }

        static void waitForCompleteFrame(WinNT.HANDLE pipe, long timeoutMs) throws IOException {
            long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
            byte[] head = new byte[4];
            while (true) {
                IntByReference read = new IntByReference();
                IntByReference avail = new IntByReference();
                if (!Kernel32.INSTANCE.PeekNamedPipe(pipe, head, head.length, read,
                        avail, null)) {
                    throw new IOException("PeekNamedPipe failed: "
                            + Kernel32.INSTANCE.GetLastError());
                }
                if (read.getValue() >= 4) {
                    int len = (head[0] & 0xFF) | ((head[1] & 0xFF) << 8)
                            | ((head[2] & 0xFF) << 16) | ((head[3] & 0xFF) << 24);
                    if (avail.getValue() >= 4 + len
                            && len <= FrameCodec.MAX_FRAME_LEN) {
                        return; // 完整帧已缓冲
                    }
                    if (len > FrameCodec.MAX_FRAME_LEN) {
                        throw new FrameCodec.FrameException("frame too large: " + len);
                    }
                }
                if (System.nanoTime() >= deadline) {
                    throw new IOException("timed out after " + timeoutMs
                            + "ms waiting for complete frame");
                }
                try {
                    Thread.sleep(5); // codex wait_for_complete_frame 轮询节奏
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted while waiting for frame", e);
                }
            }
        }

        /** TerminateProcess 收尸（握手失败/会话中止兜底）。 */
        public void terminateRunner() {
            Kernel32Ex.INSTANCE.TerminateProcess(pi.hProcess, 1);
        }

        /** 清理：进程句柄 + 双管道（管道关闭即 runner 退出信号）。 */
        @Override
        public void close() {
            in.close();
            out.close();
            closeQuietly(pi.hProcess);
        }
    }
}
