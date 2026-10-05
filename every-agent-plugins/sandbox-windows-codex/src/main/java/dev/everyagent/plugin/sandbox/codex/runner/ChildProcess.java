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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
 * PROC_THREAD_ATTRIBUTE_HANDLE_LIST（stdio 三句柄白名单，其余句柄不泄漏进沙箱）
 * + PROC_THREAD_ATTRIBUTE_JOB_LIST（Job 原子挂接，KILL_ON_JOB_CLOSE|BREAKAWAY_OK，
 * 只做整树终止不做配额；无法满足 job list 直接拒绝 spawn）；环境块走
 * {@link EnvBlock}（UTF-16 + CREATE_UNICODE_ENVIRONMENT）。
 *
 * <p><b>stdout/stderr 首选文件承载,管道只作回退</b>——PowerShell 5.1 的 stdout 指向
 * <b>管道</b>时 {@code [Console]::OutputEncoding} 取系统 OEM 码页(中文 Windows=936/GBK),
 * 而沙箱账户在 CLM 下被策略禁止改该属性、{@code chcp 65001} 也不同步到它;于是原生子进程
 *（rg/git/npm）写出的 UTF-8 字节被 PS 先按 GBK 解码(非法序列当场变 U+FFFD,不可逆丢失)再按
 * GBK 编码送回管道,读端任何"智能解码"都救不回来。改为文件承载后,PS 把该文件句柄原样交给
 * 原生子进程,子进程写原始字节,PS 完全不参与转码:实测同一文件里 cmdlet 中文与原生 UTF-8
 * 输出<b>同为合法 UTF-8</b>,stderr 也不再被包成 CLIXML(错误文本因此不再静默丢失)。
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
    /** 文件承载模式的下一次轮询间隔(ms)——保持输出接近实时,不因等待进程退出才回传。 */
    private static final long TAIL_POLL_MS = 20;

    private final WinNT.HANDLE job;
    private final WinBase.PROCESS_INFORMATION pi;
    private WinNT.HANDLE stdinWrite;
    /** 管道承载模式下的读句柄;文件承载模式为 null。 */
    private final WinNT.HANDLE stdoutRead;
    private final WinNT.HANDLE stderrRead;
    /** 非 null = stdout/stderr 走文件承载。 */
    private final OutputFiles files;
    /** 进程是否已退出(含被终止)——tail 线程据此排空收尾。 */
    private volatile boolean exitObserved;
    /** 主动要求 tail 线程收尾(close / awaitOutputReaders)。 */
    private volatile boolean stopTailing;
    private volatile CountDownLatch readersDone;

    private ChildProcess(WinNT.HANDLE job, WinBase.PROCESS_INFORMATION pi,
            WinNT.HANDLE stdinWrite, WinNT.HANDLE stdoutRead, WinNT.HANDLE stderrRead,
            OutputFiles files) {
        this.job = job;
        this.pi = pi;
        this.stdinWrite = stdinWrite;
        this.stdoutRead = stdoutRead;
        this.stderrRead = stderrRead;
        this.files = files;
    }

    /** 输出回调：{@code onOutput(chunk, stderr)}。 */
    public interface OutputSink {
        void onOutput(byte[] chunk, boolean stderr);
    }

    /** 退出结果：exitCode + timedOut + terminatedCleanly（终止后根进程是否退出）。 */
    public record ExitResult(int exitCode, boolean timedOut, boolean terminatedCleanly) {
    }

    /**
     * 子进程是否继承 runner 控制台(= 不加 {@code CREATE_NO_WINDOW})。<b>默认 false</b>,即修复前
     * 行为;只有码页探测(默认启用,EA_CONPROBE=0 可关)通过复测后,才由 {@link CodexRunnerMain} 调
     * {@link #setInheritConsoleMode} 置真。
     *
     * <p>刻意<b>不在这里引用 {@code ConsoleProbe}</b>:引用其静态方法会让该类在第一次 spawn 时
     * 完成加载与 {@code <clinit>},而 runner 的精简 classpath(物化目录里的 9 个 jar)不含 slf4j
     * ——2026-10-05 实测后果:{@code NoClassDefFoundError: org/slf4j/LoggerFactory} 直接掀掉
     * 整条命令链路(broker 侧表现为 {@code PeekNamedPipe failed: 109}),整个沙箱不可用。
     * 诊断类不得进入主路径的类加载图,哪怕它"看起来只是读一个布尔字段"。
     */
    private static volatile boolean inheritConsoleMode;

    /** 由启动期的码页探测设置(默认启用,EA_CONPROBE=0 可关;见 CodexRunnerMain)。 */
    public static void setInheritConsoleMode(boolean on) {
        inheritConsoleMode = on;
    }

    /**
     * 以受限令牌启动子进程:先试文件承载(非 ASCII 正确性所需),建不出来才回退管道。
     *
     * @param desktop lpDesktop（如 {@code Winsta0\\EveryAgentCodexDesktop-…}）；null =
     *                不指定（受限令牌下部分程序如 PowerShell 会 STATUS_DLL_INIT_FAILED，
     *                PrivateDesktop 类就绪后应总是传私有桌面）
     */
    public static ChildProcess spawn(WinNT.HANDLE hToken, List<String> argv, String cwd,
            Map<String, String> env, String desktop) {
        return spawnWithConsoleMode(hToken, argv, cwd, env, desktop, inheritConsoleMode);
    }

    /**
     * 供 {@link ConsoleProbe} 显式选模式——探测必须在"加 / 不加 {@code CREATE_NO_WINDOW}"两种形态下
     * 各测一次,才能确认改动有无净收益。生产路径走 {@link #spawn},模式由探测结论决定。
     */
    public static ChildProcess spawnForProbe(WinNT.HANDLE hToken, List<String> argv, String cwd,
            Map<String, String> env, String desktop, boolean inheritConsole) {
        return spawnWithConsoleMode(hToken, argv, cwd, env, desktop, inheritConsole);
    }

    private static ChildProcess spawnWithConsoleMode(WinNT.HANDLE hToken, List<String> argv,
            String cwd, Map<String, String> env, String desktop, boolean inheritConsole) {
        if (argv == null || argv.isEmpty()) {
            throw new IllegalArgumentException("empty command");
        }
        boolean inherit = inheritConsole; // 生产由 spawn(...) 传入 ConsoleProbe 的探测结论
        OutputFiles files = OutputFiles.tryCreate(cwd);
        if (files != null) {
            try {
                ChildProcess child = spawnViaFiles(hToken, argv, cwd, env, desktop, files, inherit);
                files.closeParentWriteHandles(); // 子进程已持有自己的副本,父侧不必留
                return child;
            } catch (RuntimeException e) {
                files.deleteQuietly(); // 回退管道承载,不留半成品文件
            }
        }
        return spawnViaPipes(hToken, argv, cwd, env, desktop, inherit);
    }

    /** 文件承载:stdin 仍用管道(交互输入语义不变),stdout/stderr 用落盘文件句柄。 */
    private static ChildProcess spawnViaFiles(WinNT.HANDLE hToken, List<String> argv,
            String cwd, Map<String, String> env, String desktop, OutputFiles files,
            boolean inheritConsole) {
        WinNT.HANDLE job = createJob();
        WinNT.HANDLE inR = null;
        WinNT.HANDLE inW = null;
        ChildProcess child = null;
        try {
            WinNT.HANDLEByReference inRR = new WinNT.HANDLEByReference();
            WinNT.HANDLEByReference inWR = new WinNT.HANDLEByReference();
            if (!Kernel32Ex.INSTANCE.CreatePipe(inRR, inWR, null, 0)) {
                throw Win32Exception.of("CreatePipe(stdin)");
            }
            inR = inRR.getValue();
            inW = inWR.getValue();
            child = spawnWithStdio(hToken, argv, cwd, env, desktop, job,
                    inR, inW, files.outWrite, files.errWrite, null, null, files, inheritConsole);
            return child;
        } catch (RuntimeException e) {
            closeQuietly(job); // KILL_ON_JOB_CLOSE:刚启动的子进程随之收束(fail-closed)
            throw e;
        } finally {
            closeQuietly(inR); // 父进程不再持有子进程侧读端
            if (child == null) {
                closeQuietly(inW);
            }
        }
    }

    /** 管道承载(回退路径,历史行为):stdio 三支匿名管道。 */
    private static ChildProcess spawnViaPipes(WinNT.HANDLE hToken, List<String> argv,
            String cwd, Map<String, String> env, String desktop, boolean inheritConsole) {
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
            child = spawnWithStdio(hToken, argv, cwd, env, desktop, job,
                    inR, inW, outW, errW, outR, errR, null, inheritConsole);
            return child;
        } catch (RuntimeException e) {
            closeQuietly(job); // KILL_ON_JOB_CLOSE:刚启动的子进程随之收束(fail-closed)
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

    /**
     * 实际创建子进程:句柄白名单 = {@code inR/outW/errW}(三支都必须可继承),
     * Job 原子挂接,CREATE_UNICODE_ENVIRONMENT + EXTENDED_STARTUPINFO_PRESENT + CREATE_NO_WINDOW。
     *
     * @param outR {@code null} = 文件承载(由 tail 线程读落盘文件);否则为管道读端
     */
    private static ChildProcess spawnWithStdio(WinNT.HANDLE hToken, List<String> argv, String cwd,
            Map<String, String> env, String desktop, WinNT.HANDLE job,
            WinNT.HANDLE inR, WinNT.HANDLE inW, WinNT.HANDLE outW, WinNT.HANDLE errW,
            WinNT.HANDLE outR, WinNT.HANDLE errR, OutputFiles files, boolean inheritConsole) {
        // stdio 三句柄白名单（子进程只继承这三支）
        for (WinNT.HANDLE h : new WinNT.HANDLE[] { inR, outW, errW }) {
            if (!Kernel32Ex.INSTANCE.SetHandleInformation(h,
                    WinBase.HANDLE_FLAG_INHERIT, WinBase.HANDLE_FLAG_INHERIT)) {
                throw Win32Exception.of("SetHandleInformation(stdio)");
            }
        }
        StartupInfoExW si = new StartupInfoExW();
        si.dwFlags |= WinBase.STARTF_USESTDHANDLES | Kernel32Ex.STARTF_USESHOWWINDOW;
        si.wShowWindow = new WinDef.WORD(Kernel32Ex.SW_HIDE);
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
                    | (inheritConsole ? 0 : WinBase.CREATE_NO_WINDOW);
            Pointer envBlock = EnvBlock.makeEnvBlock(env);
            char[] cmdline = (argvToCommandLine(argv) + "\0").toCharArray();
            WinBase.PROCESS_INFORMATION pi = new WinBase.PROCESS_INFORMATION();
            if (!Kernel32Ex.INSTANCE.CreateProcessAsUserW(hToken, null, cmdline,
                    null, null, true, flags, envBlock, cwd, si, pi)) {
                throw Win32Exception.of("CreateProcessAsUserW");
            }
            closeQuietly(pi.hThread);
            return new ChildProcess(job, pi, inW, outR, errR, files);
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
            exitObserved = true;
            return new ExitResult(TIMED_OUT_EXIT_CODE, true, stopped);
        }
        IntByReference code = new IntByReference(1);
        if (!Kernel32Ex.INSTANCE.GetExitCodeProcess(pi.hProcess, code)) {
            throw Win32Exception.of("GetExitCodeProcess");
        }
        exitObserved = true;
        return new ExitResult(code.getValue(), false, true);
    }

    /**
     * 起 stdout/stderr 两条输出线程 → Output 帧。
     *
     * <p>管道模式:ReadFile 到 EOF;文件模式:轮询追加读(tail),既不阻塞也保持输出接近实时
     *（不等进程退出才回传）。两种模式都用同一个 latch 收口,由 {@link #awaitOutputReaders}
     * 或 {@link #close} 结束。
     */
    public void startOutputReaders(OutputSink sink) {
        CountDownLatch done = new CountDownLatch(2);
        readersDone = done;
        if (files != null) {
            startFileTailReader(files.outPath, false, sink, done);
            startFileTailReader(files.errPath, true, sink, done);
        } else {
            startReader(stdoutRead, false, sink, done);
            startReader(stderrRead, true, sink, done);
        }
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

    /** 文件承载模式的 tail 读取:每 {@link #TAIL_POLL_MS}ms 追加读,退出/收尾后排空即止。 */
    private void startFileTailReader(Path path, boolean stderr, OutputSink sink,
            CountDownLatch done) {
        Runnable loop = () -> {
            try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
                ByteBuffer bb = ByteBuffer.allocate(READ_CHUNK);
                while (true) {
                    int emitted = 0;
                    while (true) {
                        bb.clear();
                        int r = ch.read(bb);
                        if (r <= 0) {
                            break;
                        }
                        bb.flip();
                        byte[] chunk = new byte[bb.limit()];
                        bb.get(chunk);
                        sink.onOutput(chunk, stderr);
                        emitted += r;
                    }
                    // 进程已退出(句柄必已关闭,文件已完整)或被要求收尾,且无新增 → 收
                    if (emitted == 0 && (exitObserved || stopTailing)) {
                        break;
                    }
                    Thread.sleep(TAIL_POLL_MS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException | RuntimeException e) {
                // 读不到即提前收尾;退出码仍由 waitForExit 给出,不影响会话
            } finally {
                done.countDown();
            }
        };
        Thread.ofVirtual().name(stderr ? "codex-runner-stderr" : "codex-runner-stdout").start(loop);
    }

    /** 等输出读线程排空（有界，防孙进程持写端挂死）。 */
    public void awaitOutputReaders(long timeoutMs) {
        stopTailing = true;
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

    /** 收尾：关 stdio/进程句柄与 Job（KILL_ON_JOB_CLOSE 兜底杀树），并清承载文件。 */
    public void close() {
        stopTailing = true;
        closeStdin();
        closeQuietly(pi.hProcess);
        closeQuietly(stdoutRead);
        closeQuietly(stderrRead);
        closeQuietly(job);
        if (files != null) {
            awaitOutputReaders(1_000L); // 让 tail 线程先放下最后一段,再删文件
            files.deleteQuietly();
        }
    }

    private static void closeQuietly(WinNT.HANDLE h) {
        if (h != null && h.getPointer() != null
                && Pointer.nativeValue(h.getPointer()) != 0
                && !h.equals(WinBase.INVALID_HANDLE_VALUE)) {
            Kernel32Ex.INSTANCE.CloseHandle(h);
        }
    }

    // ---- 文件承载（非 ASCII 正确性所需） ----

    /**
     * stdout/stderr 的落盘承载:文件由 runner 建在工作区 {@code .everyagent/tmp}
     *（沙箱账户对已授权根本就有写权,不依赖系统 TEMP——受限账户下系统 TEMP 常被拒写）,
     * 再以<b>可继承句柄</b>交给子进程;runner 侧用 {@link FileChannel} 追加读。
     *
     * <p>关键:文件以 {@code FILE_SHARE_READ} 打开,子进程(及其原生孙进程 rg/git)直接写原始
     * 字节,PS 不参与转码——这就是中文不乱码的全部原因,不是"读端解码技巧"。
     */
    private static final class OutputFiles {

        private final Path outPath;
        private final Path errPath;
        private WinNT.HANDLE outWrite;
        private WinNT.HANDLE errWrite;

        private OutputFiles(Path outPath, Path errPath, WinNT.HANDLE outWrite,
                WinNT.HANDLE errWrite) {
            this.outPath = outPath;
            this.errPath = errPath;
            this.outWrite = outWrite;
            this.errWrite = errWrite;
        }

        /** 建不出来一律返回 null(由调用方回退管道承载),绝不把沙箱会话整个打挂。 */
        static OutputFiles tryCreate(String cwd) {
            Path dir = scratchDir(cwd);
            if (dir == null) {
                return null;
            }
            Path o = null;
            Path e = null;
            WinNT.HANDLE oh = null;
            WinNT.HANDLE eh = null;
            try {
                o = Files.createTempFile(dir, "ea-codex-out-", ".tmp");
                e = Files.createTempFile(dir, "ea-codex-err-", ".tmp");
                oh = openWriteHandle(o);
                eh = openWriteHandle(e);
                if (oh == null || eh == null) {
                    return null;
                }
                return new OutputFiles(o, e, oh, eh);
            } catch (IOException | RuntimeException ex) {
                closeQuietly(oh);
                closeQuietly(eh);
                delete(o);
                delete(e);
                return null;
            }
        }

        /** 落点:优先 {@code <cwd>/.everyagent/tmp},退系统 temp;都不可用返回 null。 */
        private static Path scratchDir(String cwd) {
            if (cwd != null && !cwd.isBlank()) {
                Path root = Path.of(cwd);
                if (Files.isDirectory(root)) {
                    try {
                        Path d = root.resolve(".everyagent").resolve("tmp");
                        Files.createDirectories(d);
                        return d;
                    } catch (IOException | RuntimeException ignored) {
                        // 工作区不可写(异常挂载)→ 继续退系统 temp
                    }
                }
            }
            try {
                Path t = Path.of(System.getProperty("java.io.tmpdir"));
                Files.createDirectories(t);
                return t;
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }

        /** 以可共享(读+写+删)方式打开写句柄,供子进程继承后直写原始字节。 */
        private static WinNT.HANDLE openWriteHandle(Path p) {
            WinNT.HANDLE h = Kernel32Ex.INSTANCE.CreateFile(p.toString(), WinNT.GENERIC_WRITE,
                    WinNT.FILE_SHARE_READ | WinNT.FILE_SHARE_WRITE | WinNT.FILE_SHARE_DELETE,
                    null, WinNT.CREATE_ALWAYS, WinNT.FILE_ATTRIBUTE_NORMAL, null);
            if (h == null || h.getPointer() == null
                    || Pointer.nativeValue(h.getPointer()) == -1) { // INVALID_HANDLE_VALUE
                return null;
            }
            return h;
        }

        /** 子进程已持有副本,父侧写句柄即可关(不关也不影响读,但白占句柄)。 */
        void closeParentWriteHandles() {
            closeQuietly(outWrite);
            outWrite = null;
            closeQuietly(errWrite);
            errWrite = null;
        }

        void deleteQuietly() {
            closeParentWriteHandles();
            delete(outPath);
            delete(errPath);
        }

        private static void delete(Path p) {
            if (p == null) {
                return;
            }
            try {
                Files.deleteIfExists(p);
            } catch (IOException ignored) {
                // 子进程尚持有则删不掉:留在 scratch 目录,不影响结果
            }
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
