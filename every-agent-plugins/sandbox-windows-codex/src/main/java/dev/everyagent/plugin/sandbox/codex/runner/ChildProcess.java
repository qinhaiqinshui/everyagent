package dev.everyagent.plugin.sandbox.codex.runner;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.BaseTSD;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import dev.everyagent.plugin.sandbox.codex.win.Kernel32Ex;
import dev.everyagent.plugin.sandbox.codex.win.struct.JobStructs.JOBOBJECT_EXTENDED_LIMIT_INFORMATION;
import dev.everyagent.plugin.sandbox.codex.win.struct.StartupInfoExW;

/**
 * 受限令牌子进程执行器（设计文档 §2.6 ChildProcess，对齐 codex
 * process.rs::spawn_process_with_pipes + job.rs + proc_thread_attr.rs）。
 *
 * <p>CreateProcessAsUserW(受限令牌)：STARTUPINFOEXW +
 * PROC_THREAD_ATTRIBUTE_HANDLE_LIST（stdio 三管道句柄白名单，其余句柄不泄漏进沙箱）
 * + PROC_THREAD_ATTRIBUTE_JOB_LIST（Job 原子挂接，KILL_ON_JOB_CLOSE|BREAKAWAY_OK，
 * 只做整树终止不做配额；无法满足 job list 直接拒绝 spawn）；环境块走
 * {@link EnvBlock}（UTF-16 + CREATE_UNICODE_ENVIRONMENT）。
 *
 * <p>超时语义（command_runner/win.rs 同款）：WaitForSingleObject → WAIT_TIMEOUT(0x102)
 * → terminate（TerminateJobObject 优先，TerminateProcess 兜底）→
 * TERMINATION_WAIT_MS 二次确认 → exit_code=192（128+64）且 timed_out=true。
 * preserve_descendants 语义不需要：runner 不保留后代，树随会话由 Job 收束。
 * 仅 Windows 运行时调用。
 */
public final class ChildProcess {

    /** WaitForSingleObject 超时返回值（WAIT_TIMEOUT）。 */
    private static final int WAIT_TIMEOUT = 0x00000102;
    /** 超时终止后的二次等待（codex TERMINATION_WAIT_MS）。 */
    public static final int TERMINATION_WAIT_MS = 5_000;
    /** 超时合成退出码：128+64（POSIX SIGTERM 语义，codex capture 同款）。 */
    public static final int TIMED_OUT_EXIT_CODE = 128 + 64;
    /** 输出读取块大小（codex read_handle_loop 同款 8KiB）。 */
    private static final int READ_CHUNK = 8192;

    private final WinNT.HANDLE job;
    private final WinBase.PROCESS_INFORMATION pi;
    private WinNT.HANDLE stdinWrite;
    private final WinNT.HANDLE stdoutRead;
    private final WinNT.HANDLE stderrRead;
    private volatile CountDownLatch readersDone;

    private ChildProcess(WinNT.HANDLE job, WinBase.PROCESS_INFORMATION pi,
            WinNT.HANDLE stdinWrite, WinNT.HANDLE stdoutRead, WinNT.HANDLE stderrRead) {
        this.job = job;
        this.pi = pi;
        this.stdinWrite = stdinWrite;
        this.stdoutRead = stdoutRead;
        this.stderrRead = stderrRead;
    }

    /** 输出回调：{@code onOutput(chunk, stderr)}。 */
    public interface OutputSink {
        void onOutput(byte[] chunk, boolean stderr);
    }

    /** 退出结果：exitCode + timedOut + terminatedCleanly（终止后根进程是否退出）。 */
    public record ExitResult(int exitCode, boolean timedOut, boolean terminatedCleanly) {
    }

    /**
     * 以受限令牌启动子进程。
     *
     * @param desktop lpDesktop（如 {@code Winsta0\\EveryAgentCodexDesktop-…}）；null =
     *                不指定（受限令牌下部分程序如 PowerShell 会 STATUS_DLL_INIT_FAILED，
     *                PrivateDesktop 类就绪后应总是传私有桌面）
     */
    public static ChildProcess spawn(WinNT.HANDLE hToken, List<String> argv, String cwd,
            Map<String, String> env, String desktop) {
        if (argv == null || argv.isEmpty()) {
            throw new IllegalArgumentException("empty command");
        }
        WinNT.HANDLE job = createJob();
        WinNT.HANDLE inR = null;
        WinNT.HANDLE inW = null;
        WinNT.HANDLE outR = null;
        WinNT.HANDLE outW = null;
        WinNT.HANDLE errR = null;
        WinNT.HANDLE errW = null;
        ChildProcess child = null;
        try {
            WinNT.HANDLEByReference inRR = new WinNT.HANDLEByReference();
            WinNT.HANDLEByReference inWR = new WinNT.HANDLEByReference();
            WinNT.HANDLEByReference outRR = new WinNT.HANDLEByReference();
            WinNT.HANDLEByReference outWR = new WinNT.HANDLEByReference();
            WinNT.HANDLEByReference errRR = new WinNT.HANDLEByReference();
            WinNT.HANDLEByReference errWR = new WinNT.HANDLEByReference();
            if (!Kernel32Ex.INSTANCE.CreatePipe(inRR, inWR, null, 0)) {
                throw Win32Exception.of("CreatePipe(stdin)");
            }
            inR = inRR.getValue();
            inW = inWR.getValue();
            if (!Kernel32Ex.INSTANCE.CreatePipe(outRR, outWR, null, 0)) {
                throw Win32Exception.of("CreatePipe(stdout)");
            }
            outR = outRR.getValue();
            outW = outWR.getValue();
            if (!Kernel32Ex.INSTANCE.CreatePipe(errRR, errWR, null, 0)) {
                throw Win32Exception.of("CreatePipe(stderr)");
            }
            errR = errRR.getValue();
            errW = errWR.getValue();
            child = spawnWithPipes(hToken, argv, cwd, env, desktop, job,
                    inR, inW, outR, outW, errR, errW);
            return child;
        } catch (RuntimeException e) {
            closeQuietly(job); // KILL_ON_JOB_CLOSE：刚启动的子进程随之收束（fail-closed）
            throw e;
        } finally {
            // 子进程侧三端（inR/outW/errW）父进程不再持有：关掉，否则 stdout 永不 EOF；
            // 失败路径（child==null）六端全关。
            closeQuietly(inR);
            closeQuietly(outW);
            closeQuietly(errW);
            if (child == null) {
                closeQuietly(inW);
                closeQuietly(outR);
                closeQuietly(errR);
            }
        }
    }

    private static ChildProcess spawnWithPipes(WinNT.HANDLE hToken, List<String> argv, String cwd,
            Map<String, String> env, String desktop, WinNT.HANDLE job,
            WinNT.HANDLE inR, WinNT.HANDLE inW, WinNT.HANDLE outR, WinNT.HANDLE outW,
            WinNT.HANDLE errR, WinNT.HANDLE errW) {
        // stdio 三句柄白名单（子进程只继承这三支管道）
        for (WinNT.HANDLE h : new WinNT.HANDLE[] { inR, outW, errW }) {
            if (!Kernel32Ex.INSTANCE.SetHandleInformation(h,
                    WinBase.HANDLE_FLAG_INHERIT, WinBase.HANDLE_FLAG_INHERIT)) {
                throw Win32Exception.of("SetHandleInformation(stdio)");
            }
        }
        StartupInfoExW si = new StartupInfoExW();
        si.dwFlags |= WinBase.STARTF_USESTDHANDLES;
        si.hStdInput = inR;
        si.hStdOutput = outW;
        si.hStdError = errW;
        si.lpDesktop = desktop;

        // 属性列表：JOB_LIST（原子挂接，失败即拒绝 spawn）+ HANDLE_LIST
        LongByReference size = new LongByReference();
        Kernel32Ex.INSTANCE.InitializeProcThreadAttributeList(null, 2, 0, size);
        Memory attrList = new Memory(size.getValue());
        try {
            if (!Kernel32Ex.INSTANCE.InitializeProcThreadAttributeList(attrList, 2, 0, size)) {
                throw Win32Exception.of("InitializeProcThreadAttributeList");
            }
            Memory jobVal = new Memory(Native.POINTER_SIZE);
            jobVal.setPointer(0, job.getPointer());
            if (!Kernel32Ex.INSTANCE.UpdateProcThreadAttribute(attrList, 0,
                    new BaseTSD.DWORD_PTR(Kernel32Ex.PROC_THREAD_ATTRIBUTE_JOB_LIST),
                    jobVal, Native.POINTER_SIZE, null, null)) {
                throw Win32Exception.of("UpdateProcThreadAttribute(JOB_LIST)");
            }
            Memory handleList = new Memory(Native.POINTER_SIZE * 3);
            WinNT.HANDLE[] handles = { inR, outW, errW };
            for (int i = 0; i < handles.length; i++) {
                handleList.setPointer((long) i * Native.POINTER_SIZE, handles[i].getPointer());
            }
            if (!Kernel32Ex.INSTANCE.UpdateProcThreadAttribute(attrList, 0,
                    new BaseTSD.DWORD_PTR(Kernel32Ex.PROC_THREAD_ATTRIBUTE_HANDLE_LIST),
                    handleList, Native.POINTER_SIZE * 3, null, null)) {
                throw Win32Exception.of("UpdateProcThreadAttribute(HANDLE_LIST)");
            }
            si.lpAttributeList = attrList;

            int flags = WinBase.CREATE_UNICODE_ENVIRONMENT | WinBase.EXTENDED_STARTUPINFO_PRESENT
                    | WinBase.CREATE_NO_WINDOW;
            Pointer envBlock = EnvBlock.makeEnvBlock(env);
            char[] cmdline = (argvToCommandLine(argv) + "\0").toCharArray();
            WinBase.PROCESS_INFORMATION pi = new WinBase.PROCESS_INFORMATION();
            if (!Kernel32Ex.INSTANCE.CreateProcessAsUserW(hToken, null, cmdline,
                    null, null, true, flags, envBlock, cwd, si, pi)) {
                throw Win32Exception.of("CreateProcessAsUserW");
            }
            closeQuietly(pi.hThread);
            return new ChildProcess(job, pi, inW, outR, errR);
        } finally {
            Kernel32Ex.INSTANCE.DeleteProcThreadAttributeList(attrList);
        }
    }

    /** JobObject：KILL_ON_JOB_CLOSE|BREAKAWAY_OK，无配额。 */
    private static WinNT.HANDLE createJob() {
        WinNT.HANDLE job = Kernel32Ex.INSTANCE.CreateJobObjectW(null, null);
        if (job == null || job.getPointer() == null) {
            throw Win32Exception.of("CreateJobObjectW");
        }
        JOBOBJECT_EXTENDED_LIMIT_INFORMATION info = new JOBOBJECT_EXTENDED_LIMIT_INFORMATION();
        info.BasicLimitInformation.LimitFlags = new WinDef.DWORD(
                Kernel32Ex.JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE | Kernel32Ex.JOB_OBJECT_LIMIT_BREAKAWAY_OK);
        if (!Kernel32Ex.INSTANCE.SetInformationJobObject(job,
                Kernel32Ex.JobObjectExtendedLimitInformation, info, info.size())) {
            int err = Kernel32Ex.INSTANCE.GetLastError();
            closeQuietly(job);
            throw new Win32Exception("SetInformationJobObject", err);
        }
        return job;
    }

    /** 子进程 PID（spawn_ready 用）。 */
    public int processId() {
        return Kernel32Ex.INSTANCE.GetProcessId(pi.hProcess);
    }

    /** 写子进程 stdin；部分写推进，失败/零进展即关写端并停发（codex 输入循环语义）。 */
    public void writeStdin(byte[] data) {
        WinNT.HANDLE h = stdinWrite;
        if (h == null || data == null || data.length == 0) {
            return;
        }
        int off = 0;
        while (off < data.length) {
            int len = Math.min(data.length - off, 1 << 16);
            byte[] chunk = new byte[len];
            System.arraycopy(data, off, chunk, 0, len);
            IntByReference written = new IntByReference();
            if (!Kernel32Ex.INSTANCE.WriteFile(h, chunk, len, written, null)
                    || written.getValue() <= 0) {
                closeStdin();
                return;
            }
            off += written.getValue();
        }
    }

    /** 关闭子进程 stdin 写端（EOF）。 */
    public void closeStdin() {
        WinNT.HANDLE h = stdinWrite;
        stdinWrite = null;
        if (h != null) {
            closeQuietly(h);
        }
    }

    /** 终止整树：TerminateJobObject 优先，失败回退 TerminateProcess 根进程。 */
    public void terminate() {
        if (!Kernel32Ex.INSTANCE.TerminateJobObject(job, 1)) {
            Kernel32Ex.INSTANCE.TerminateProcess(pi.hProcess, 1);
        }
    }

    /** 超时语义见类注释；再次等待以确认根进程确实退出。 */
    public ExitResult waitForExit(Long timeoutMs) {
        int wait = timeoutMs == null ? WinBase.INFINITE
                : (int) Math.min(timeoutMs, Integer.MAX_VALUE);
        int r = Kernel32Ex.INSTANCE.WaitForSingleObject(pi.hProcess, wait);
        if (r == WAIT_TIMEOUT) {
            terminate();
            boolean stopped = Kernel32Ex.INSTANCE.WaitForSingleObject(
                    pi.hProcess, TERMINATION_WAIT_MS) != WAIT_TIMEOUT;
            return new ExitResult(TIMED_OUT_EXIT_CODE, true, stopped);
        }
        IntByReference code = new IntByReference(1);
        if (!Kernel32Ex.INSTANCE.GetExitCodeProcess(pi.hProcess, code)) {
            throw Win32Exception.of("GetExitCodeProcess");
        }
        return new ExitResult(code.getValue(), false, true);
    }

    /** 起 stdout/stderr 两条读线程 → Output 帧（8KiB 循环，EOF 收尾关句柄）。 */
    public void startOutputReaders(OutputSink sink) {
        CountDownLatch done = new CountDownLatch(2);
        readersDone = done;
        startReader(stdoutRead, false, sink, done);
        startReader(stderrRead, true, sink, done);
    }

    private void startReader(WinNT.HANDLE handle, boolean stderr, OutputSink sink,
            CountDownLatch done) {
        Runnable loop = () -> {
            try {
                byte[] buf = new byte[READ_CHUNK];
                while (true) {
                    IntByReference read = new IntByReference();
                    if (!Kernel32Ex.INSTANCE.ReadFile(handle, buf, READ_CHUNK, read, null)
                            || read.getValue() == 0) {
                        break;
                    }
                    int n = read.getValue();
                    byte[] chunk = new byte[n];
                    System.arraycopy(buf, 0, chunk, 0, n);
                    sink.onOutput(chunk, stderr);
                }
            } catch (RuntimeException ignored) {
                // 管道断开等：输出流提前收尾，退出码由 waitForExit 给出
            } finally {
                closeQuietly(handle);
                done.countDown();
            }
        };
        Thread.ofVirtual().name(stderr ? "codex-runner-stderr" : "codex-runner-stdout").start(loop);
    }

    /** 等输出读线程排空（有界，防孙进程持写端挂死）。 */
    public void awaitOutputReaders(long timeoutMs) {
        CountDownLatch done = readersDone;
        if (done == null) {
            return;
        }
        try {
            done.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 收尾：关 stdio/进程句柄与 Job（KILL_ON_JOB_CLOSE 兜底杀树）。 */
    public void close() {
        closeStdin();
        closeQuietly(pi.hProcess);
        closeQuietly(stdoutRead);
        closeQuietly(stderrRead);
        closeQuietly(job);
    }

    private static void closeQuietly(WinNT.HANDLE h) {
        if (h != null && h.getPointer() != null
                && Pointer.nativeValue(h.getPointer()) != 0
                && !h.equals(WinBase.INVALID_HANDLE_VALUE)) {
            Kernel32Ex.INSTANCE.CloseHandle(h);
        }
    }

    // ---- 命令行合成（winutil.rs::argv_to_command_line / quote_windows_arg） ----

    static String argvToCommandLine(List<String> argv) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < argv.size(); i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(quoteWindowsArg(argv.get(i)));
        }
        return sb.toString();
    }

    /** CommandLineToArgvW/CRT 规则：含空白/引号或空串才加外层引号，内部引号转义。 */
    static String quoteWindowsArg(String arg) {
        boolean needsQuotes = arg.isEmpty();
        for (int i = 0; i < arg.length() && !needsQuotes; i++) {
            char c = arg.charAt(i);
            needsQuotes = c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '"';
        }
        if (!needsQuotes) {
            return arg;
        }
        StringBuilder sb = new StringBuilder(arg.length() + 2);
        sb.append('"');
        int backslashes = 0;
        for (int i = 0; i < arg.length(); i++) {
            char c = arg.charAt(i);
            if (c == '\\') {
                backslashes++;
            } else if (c == '"') {
                sb.append("\\".repeat(backslashes * 2 + 1)).append('"');
                backslashes = 0;
            } else {
                if (backslashes > 0) {
                    sb.append("\\".repeat(backslashes));
                    backslashes = 0;
                }
                sb.append(c);
            }
        }
        if (backslashes > 0) {
            sb.append("\\".repeat(backslashes * 2));
        }
        return sb.append('"').toString();
    }
}
