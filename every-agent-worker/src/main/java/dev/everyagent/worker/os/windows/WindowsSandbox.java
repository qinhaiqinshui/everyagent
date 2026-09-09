package dev.everyagent.worker.os.windows;

import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.os.OsSandbox.ExecResult;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.BaseTSD;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Windows 原生进程沙箱:Restricted Token + Job Object(FFI 经 jna-platform + {@link Win32Ex})。
 *
 * <p>执行链:
 * <ol>
 *   <li>取当前进程 token → {@code CreateRestrictedToken} 去掉全部特权 / SID(disable-max-privilege);</li>
 *   <li>设 token 完整性级别为 Low({@code SECURITY_MANDATORY_LOW_RID}),写不进 Medium 以上对象;</li>
 *   <li>{@code CreateJobObject} + {@code SetInformationJobObject} 设:
 *       ActiveProcessLimit(默认 32,允许 shell 内 rg/git 等有限子进程,防失控进程树)、
 *       JobMemoryLimit(内存上限)、KillOnJobClose(父死子亡);</li>
 *   <li>{@code CreatePipe}×2(可继承,stdout 与 stderr 各一条独立管道)→
 *       {@code CreateProcessAsUserW}(CREATE_SUSPENDED,继承两个写端)→
 *       {@code AssignProcessToJobObject} → {@code ResumeThread};</li>
 *   <li>{@code WaitForSingleObject} 带超时;超时 {@code TerminateJobObject} 强杀整个 job;</li>
 *   <li>两条管道并发读取、各自截断,分流回传(stdout 只含命令数据,
 *       PowerShell 的 CLIXML 流记录只落 stderr),取真实退出码。</li>
 * </ol>
 *
 * <p><b>环境块契约</b>:传给 CreateProcessAsUserW 的环境块是 UTF-16,必须同时带
 * {@link WinBase#CREATE_UNICODE_ENVIRONMENT} 标志——该 API 对环境块校验比
 * {@code CreateProcessW} 严,缺标志会按 ANSI 解析并直接拒收 ERROR_INVALID_PARAMETER(87)。
 *
 * <p>已知边界(与语言无关):Job Object 管不了网络;本实现靠 {@code deny-all}
 * 剥离代理 env(在 OsSandbox 已处理),真网络隔离需本地代理(本版 TODO)。
 * Low IL 进程写不了默认 Medium 的用户文件(NO_WRITE_UP)——工作区/授权根须先经
 * {@link WindowsIntegrity#ensureWritable} 标注 Low 完整性(§13.6),否则工作区内也只能读不能写。
 */
public final class WindowsSandbox {

    private static final Logger log = LoggerFactory.getLogger(WindowsSandbox.class);

    private static final Win32Ex.SandboxKernel32 K = Win32Ex.SandboxKernel32.I;
    private static final Win32Ex.SandboxAdvapi32 A = Win32Ex.SandboxAdvapi32.I;

    private WindowsSandbox() {
    }

    public static ExecResult run(String command, Path cwd, Map<String, String> extraEnv,
            WorkerProperties.Sandbox cfg, ExecutorService exec, int maxOut, String shell,
            boolean allowNetwork, boolean allowPrivilege) {
        return runWithCmdLine(buildCommandLine(shell, command), cwd, extraEnv, cfg, exec, maxOut,
                allowNetwork, allowPrivilege);
    }

    /**
     * String 命令入口的公共实现:直接以给定命令行启动沙箱进程并收集输出。
     */
    private static ExecResult runWithCmdLine(String cmdLineStr, Path cwd, Map<String, String> extraEnv,
            WorkerProperties.Sandbox cfg, ExecutorService exec, int maxOut, boolean allowNetwork,
            boolean allowPrivilege) {
        WinNT.HANDLE hJob = null;
        WinNT.HANDLE hRestricted = null;
        WinNT.HANDLE readOut = null;
        WinNT.HANDLE writeOut = null;
        WinNT.HANDLE readErr = null;
        WinNT.HANDLE writeErr = null;
        WinNT.HANDLE hProc = null;
        WinNT.HANDLE hThread = null;
        try {
            hRestricted = allowPrivilege
                    ? buildCurrentToken() // 提权档:保留当前 token(Medium IL + 既有特权),不降权
                    : buildRestrictedToken();
            if (hRestricted == null) {
                return new ExecResult("", "[sandbox] 构建进程 token 失败,拒绝执行", 1, false);
            }
            if (!allowPrivilege && !applyLowIntegrity(hRestricted)) {
                log.warn("[sandbox] 设 Low IL 失败,继续(降权仍生效)");
            }

            hJob = K.CreateJobObjectW(null, null);
            if (hJob == null) {
                return new ExecResult("", "[sandbox] 创建 Job Object 失败,拒绝执行", 1, false);
            }
            Win32Ex.JOBOBJECT_EXTENDED_LIMIT_INFORMATION jobInfo = buildJobLimits(cfg);
            if (!K.SetInformationJobObject(hJob, Win32Ex.JobObjectExtendedLimitInformation,
                    jobInfo, jobInfo.size())) {
                log.warn("[sandbox] SetInformationJobObject(ExtendedLimit) 失败 err={}", K.GetLastError());
            }
            // CPU 硬上限:真实生效,避免失控进程占满核
            if (cfg.getCpuHardCapPercent() > 0) {
                Win32Ex.JOBOBJECT_CPU_RATE_HARD_CAP_INFORMATION cpu =
                        new Win32Ex.JOBOBJECT_CPU_RATE_HARD_CAP_INFORMATION(cfg.getCpuHardCapPercent());
                if (!K.SetInformationJobObject(hJob, Win32Ex.JobObjectCpuRateHardCapInformation,
                        cpu, cpu.size())) {
                    log.warn("[sandbox] SetInformationJobObject(CpuRateHardCap) 失败 err={}", K.GetLastError());
                }
            }

            // 可继承管道 ×2:stdout 与 stderr 各一条独立管道。混流会把 PowerShell 的
            // CLIXML 流记录(#< CLIXML + 进度 XML,见 stderr 序列化)混进真实输出,
            // 分流后 stdout 永远只有命令本身的数据。
            WinBase.SECURITY_ATTRIBUTES sa = new WinBase.SECURITY_ATTRIBUTES();
            sa.bInheritHandle = true;
            WinNT.HANDLEByReference rhOut = new WinNT.HANDLEByReference();
            WinNT.HANDLEByReference whOut = new WinNT.HANDLEByReference();
            if (!K.CreatePipe(rhOut, whOut, sa, 0)) {
                return new ExecResult("", "[sandbox] CreatePipe(stdout) 失败 err=" + K.GetLastError(), 1, false);
            }
            readOut = rhOut.getValue();
            writeOut = whOut.getValue();
            WinNT.HANDLEByReference rhErr = new WinNT.HANDLEByReference();
            WinNT.HANDLEByReference whErr = new WinNT.HANDLEByReference();
            if (!K.CreatePipe(rhErr, whErr, sa, 0)) {
                return new ExecResult("", "[sandbox] CreatePipe(stderr) 失败 err=" + K.GetLastError(), 1, false);
            }
            readErr = rhErr.getValue();
            writeErr = whErr.getValue();
            // 子进程只继承两个写端;父进程读端不继承,防泄漏给孙进程导致 EOF 永不到来
            K.SetHandleInformation(readOut, Win32Ex.HANDLE_FLAG_INHERIT, 0);
            K.SetHandleInformation(readErr, Win32Ex.HANDLE_FLAG_INHERIT, 0);
            K.SetHandleInformation(writeOut, Win32Ex.HANDLE_FLAG_INHERIT, Win32Ex.HANDLE_FLAG_INHERIT);
            K.SetHandleInformation(writeErr, Win32Ex.HANDLE_FLAG_INHERIT, Win32Ex.HANDLE_FLAG_INHERIT);

            WinBase.STARTUPINFO si = new WinBase.STARTUPINFO();
            si.dwFlags = WinBase.STARTF_USESTDHANDLES;
            si.hStdOutput = writeOut;
            si.hStdError = writeErr;
            si.hStdInput = new WinNT.HANDLE(); // NULL:沙箱命令不从 stdin 读

            WinBase.PROCESS_INFORMATION pi = new WinBase.PROCESS_INFORMATION();
            Pointer env = buildEnvBlock(extraEnv, cfg, allowNetwork);
            // 命令行 char[] 必须显式 NUL 终止:JNA 对原生数组参数不自动补终止符,
            // 缺失会让 CreateProcess 读到缓冲区外的堆垃圾拼进命令尾
            boolean ok = K.CreateProcessAsUserW(hRestricted, null, (cmdLineStr + "\0").toCharArray(),
                    null, null, true,
                    new WinDef.DWORD(WinBase.CREATE_SUSPENDED | WinBase.CREATE_NO_WINDOW
                            | WinBase.CREATE_UNICODE_ENVIRONMENT),
                    env, cwd.toString(), si, pi);
            if (!ok) {
                return new ExecResult("", "[sandbox] CreateProcessAsUser 失败 err=" + K.GetLastError(), 1, false);
            }
            hProc = pi.hProcess;
            hThread = pi.hThread;
            if (!K.AssignProcessToJobObject(hJob, hProc)) {
                log.warn("[sandbox] AssignProcessToJobObject 失败 err={}", K.GetLastError());
            }
            // 父进程不再需要两个写端(关闭后子进程退出即触发读端 EOF)
            K.CloseHandle(writeOut);
            writeOut = null;
            K.CloseHandle(writeErr);
            writeErr = null;
            K.ResumeThread(hThread);

            // 两条管道并发读:任一条只写不读都会写满缓冲卡死子进程
            // (readOut/readErr 非 effectively final,须经 final 局部变量转交 lambda)
            Win32HandleInputStream outIn = new Win32HandleInputStream(readOut);
            Win32HandleInputStream errIn = new Win32HandleInputStream(readErr);
            Future<String> outTask = exec.submit(() -> drain(outIn));
            Future<String> errTask = exec.submit(() -> drain(errIn));

            int waitMs = (int) Math.min(cfg.getTimeoutMs(), Integer.MAX_VALUE);
            int wr = K.WaitForSingleObject(hProc, waitMs);
            boolean aborted = (wr == Win32Ex.WAIT_TIMEOUT);
            String outText = awaitQuiet(outTask, aborted ? 2 : 5);
            String errText = awaitQuiet(errTask, aborted ? 2 : 5);
            int code;
            if (aborted) {
                K.TerminateJobObject(hJob, 1);
                errText += "\n[exec 超时中止: >" + cfg.getTimeoutMs() + "ms,已强杀 job]";
                code = -1;
            } else {
                IntByReference ec = new IntByReference(0);
                if (K.GetExitCodeProcess(hProc, ec)) {
                    code = ec.getValue();
                } else {
                    code = 0;
                }
            }
            return new ExecResult(cap(outText, maxOut), cap(errText, maxOut), code, aborted);
        } catch (Exception e) {
            return new ExecResult("", "[sandbox] 异常: " + e.getMessage(), 1, false);
        } finally {
            if (hProc != null) {
                K.CloseHandle(hProc);
            }
            if (hThread != null) {
                K.CloseHandle(hThread);
            }
            if (readOut != null) {
                K.CloseHandle(readOut);
            }
            if (writeOut != null) {
                K.CloseHandle(writeOut);
            }
            if (readErr != null) {
                K.CloseHandle(readErr);
            }
            if (writeErr != null) {
                K.CloseHandle(writeErr);
            }
            if (hJob != null) {
                K.CloseHandle(hJob);
            }
            if (hRestricted != null) {
                K.CloseHandle(hRestricted);
            }
        }
    }

    /** 构建降权 token:去全部特权 + SID,禁用 max privilege。 */
    private static WinNT.HANDLE buildRestrictedToken() {
        WinNT.HANDLEByReference phToken = new WinNT.HANDLEByReference();
        if (!A.OpenProcessToken(K.GetCurrentProcess(), WinNT.TOKEN_ALL_ACCESS, phToken)) {
            log.warn("[sandbox] OpenProcessToken 失败 err={}", K.GetLastError());
            return null;
        }
        WinNT.HANDLEByReference phNew = new WinNT.HANDLEByReference();
        boolean ok = A.CreateRestrictedToken(phToken.getValue(), Win32Ex.DISABLE_MAX_PRIVILEGE,
                0, null, 0, null, 0, null, phNew);
        K.CloseHandle(phToken.getValue());
        if (!ok) {
            log.warn("[sandbox] CreateRestrictedToken 失败 err={}", K.GetLastError());
            return null;
        }
        // CreateRestrictedToken 产出的是 impersonation token;CreateProcessAsUser 要求
        // primary token,故 DuplicateTokenEx 转成 TokenPrimary。
        WinNT.HANDLEByReference phPrimary = new WinNT.HANDLEByReference();
        boolean okDup = A.DuplicateTokenEx(phNew.getValue(), WinNT.TOKEN_ALL_ACCESS, null,
                Win32Ex.SecurityImpersonation, Win32Ex.TokenPrimary, phPrimary);
        K.CloseHandle(phNew.getValue());
        if (!okDup) {
            log.warn("[sandbox] DuplicateTokenEx 失败 err={}", K.GetLastError());
            return null;
        }
        return phPrimary.getValue();
    }

    /**
     * 构建当前进程的 primary token 副本(不做任何降权):allow-privilege-escalation=true
     * 时替代 restricted token,保留 Medium IL 与既有特权。仍走 CreateProcessAsUserW,
     * 只跳过 restricted/Low IL 两步;Job Object 的资源护栏(进程数/内存/CPU/超时)不受影响。
     */
    private static WinNT.HANDLE buildCurrentToken() {
        WinNT.HANDLEByReference phToken = new WinNT.HANDLEByReference();
        if (!A.OpenProcessToken(K.GetCurrentProcess(), WinNT.TOKEN_ALL_ACCESS, phToken)) {
            log.warn("[sandbox] OpenProcessToken 失败 err={}", K.GetLastError());
            return null;
        }
        WinNT.HANDLEByReference phPrimary = new WinNT.HANDLEByReference();
        boolean okDup = A.DuplicateTokenEx(phToken.getValue(), WinNT.TOKEN_ALL_ACCESS, null,
                Win32Ex.SecurityImpersonation, Win32Ex.TokenPrimary, phPrimary);
        K.CloseHandle(phToken.getValue());
        if (!okDup) {
            log.warn("[sandbox] DuplicateTokenEx 失败 err={}", K.GetLastError());
            return null;
        }
        return phPrimary.getValue();
    }

    /** 设 token 完整性级别为 Low。 */
    private static boolean applyLowIntegrity(WinNT.HANDLE hToken) {
        PointerByReference pSid = new PointerByReference();
        // SECURITY_MANDATORY_LABEL_AUTHORITY = {0,0,0,0,0,16} → S-1-16;
        // 配合 subAuthority[0]=SECURITY_MANDATORY_LOW_RID(0x1000) 得 S-1-16-4096 (Low IL)。
        // 注意:不能用 WORLD authority {0}(S-1-1),否则产生无效 label,SetTokenInformation 静默失败。
        Memory auth = new Memory(6);
        auth.write(0, new byte[] { 0, 0, 0, 0, 0, 16 }, 0, 6);
        boolean okSid = A.AllocateAndInitializeSid(auth, (byte) 1,
                Win32Ex.SECURITY_MANDATORY_LOW_RID, 0, 0, 0, 0, 0, 0, 0, pSid);
        if (!okSid) {
            log.warn("[sandbox] AllocateAndInitializeSid 失败 err={}", K.GetLastError());
            return false;
        }
        Win32Ex.TOKEN_MANDATORY_LABEL tml = new Win32Ex.TOKEN_MANDATORY_LABEL();
        tml.Label = new Win32Ex.SID_AND_ATTRIBUTES();
        tml.Label.Sid = pSid.getValue();
        tml.Label.Attributes = Win32Ex.SE_GROUP_INTEGRITY;
        boolean ok = A.SetTokenInformation(hToken, Win32Ex.TokenIntegrityLevel, tml, tml.size());
        if (!ok) {
            log.warn("[sandbox] SetTokenInformation(TokenIntegrityLevel) 失败 err={}", K.GetLastError());
        }
        A.FreeSid(pSid.getValue());
        return ok;
    }

    private static Win32Ex.JOBOBJECT_EXTENDED_LIMIT_INFORMATION buildJobLimits(WorkerProperties.Sandbox cfg) {
        Win32Ex.JOBOBJECT_EXTENDED_LIMIT_INFORMATION info = new Win32Ex.JOBOBJECT_EXTENDED_LIMIT_INFORMATION();
        int flags = Win32Ex.JOB_OBJECT_LIMIT_ACTIVE_PROCESS | Win32Ex.JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
        int activeLimit = cfg.getActiveProcessLimit();
        if (activeLimit > 0) {
            // 注意:不能固定为 1。沙箱 shell 需要在内部再启 rg/git 等有限子进程
            // (rgt 已移除,模型直接在 shell 中调 rg);这里放行有限进程树,防失控。
            info.BasicLimitInformation.ActiveProcessLimit = new WinDef.DWORD(activeLimit);
        } else {
            flags &= ~Win32Ex.JOB_OBJECT_LIMIT_ACTIVE_PROCESS; // 0 = 不限制进程数
        }
        if (cfg.getMemoryLimitMb() > 0) {
            flags |= Win32Ex.JOB_OBJECT_LIMIT_JOB_MEMORY;
            info.JobMemoryLimit = new BaseTSD.SIZE_T(cfg.getMemoryLimitMb() * 1024L * 1024L);
        }
        info.BasicLimitInformation.LimitFlags = new WinDef.DWORD(flags);
        return info;
    }

    /**
     * 构建子进程环境块(UTF-16LE,以父进程环境为基底)。
     *
     * <p>先复制 {@link System#getenv()} 的完整父环境,再以 {@code extra} 覆盖
     * (extra 为 null 时仅父环境)。这样注入 PATH 等变量时不丢失 SystemRoot /
     * SYSTEMDRIVE 等父进程关键路径变量,避免子进程找不到系统组件。
     * 当 {@code !allowNetwork}(任务禁网:选了 /禁用网络 或 worker 全局拒网)时剥除代理变量
     * (Job Object 管不了网络,此为 advisory 边界)。
     * 合并后为空才返回 {@link Pointer#NULL}(CreateProcess* 视作继承父环境);
     * 返回 UTF-16 块时调用方必须带 CREATE_UNICODE_ENVIRONMENT(见类注释)。
     */
    private static Pointer buildEnvBlock(Map<String, String> extra, WorkerProperties.Sandbox cfg,
            boolean allowNetwork) {
        Map<String, String> env = new HashMap<>(System.getenv());
        if (extra != null) {
            env.putAll(extra);
        }
        if (!allowNetwork) {
            List.of("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy",
                    "ALL_PROXY", "all_proxy", "NO_PROXY", "no_proxy").forEach(env::remove);
        }
        if (env.isEmpty()) {
            return Pointer.NULL;
        }
        StringBuilder sb = new StringBuilder();
        env.forEach((k, v) -> sb.append(k).append('=').append(v).append('\0'));
        sb.append('\0');
        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_16LE);
        Memory mem = new Memory(bytes.length);
        mem.write(0, bytes, 0, bytes.length);
        return mem;
    }

    /**
     * 按 MSVCRT 规则把一个参数包成带引号的 Windows 命令行片段:外层双引号定界,
     * 内层 {@code "} 转义为 {@code \"},引号前的反斜杠加倍(2n 个反斜杠+引号=定界,
     * 2n+1 个=字面引号)。子进程(Cygwin/MSYS bash 及一切 CRT 程序)按同一规则还原出
     * 完整单参数,内层引号不被 argv 分词吃掉。空串包成 {@code ""} 以保住空参数。
     */
    private static String quoteArg(String s) {
        StringBuilder sb = new StringBuilder("\"");
        int pendingBackslashes = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                pendingBackslashes++;
            } else if (c == '"') {
                sb.append("\\".repeat(pendingBackslashes * 2 + 1)).append('"');
                pendingBackslashes = 0;
            } else {
                sb.append("\\".repeat(pendingBackslashes));
                pendingBackslashes = 0;
                sb.append(c);
            }
        }
        if (pendingBackslashes > 0) {
            sb.append("\\".repeat(pendingBackslashes * 2)); // 收尾引号前的反斜杠须加倍,防被当作转义
        }
        return sb.append('"').toString();
    }

    /**
     * 合成完整子进程命令行(auto 默认 cmd.exe)。
     *
     * <p>注意:Select-String 等 PowerShell cmdlet 在 cmd.exe 中不存在,必须走
     * powershell.exe(os.name 判定由调用方完成,本方法只负责 shell 名 → 命令行的映射)。
     *
     * <p>PowerShell 用 {@code -Command} + {@link #quoteArg}(MSVCRT 规则,与 bash 分支一致):
     * powershell.exe 解析自身命令行时按 Windows argv 规则分词(双引号被当参数定界符剥掉),
     * 命令内层引号会被吃掉——如 {@code python -c "import x; f()"} 会被拆成 {@code ;} 分隔的
     * 多条语句。quoteArg 把整条命令包成单个无歧义 argv,由 CommandLineToArgvW 按 MSVCRT 规则
     * 还原出完整命令串,内层引号不被吃掉。
     *
     * <p>注意:不走 {@code -EncodedCommand}:实验确认该模式在 stdout/stderr 被管道重定向时会把
     * 非成功流(progress/information/error)序列化为 {@code #< CLIXML ...>} 噪声写进 stderr;
     * 而 {@code -Command} 模式下 Write-Host / Write-Output / 2>&1 / 原生 stderr 均以纯文本输出,
     * 无 CLIXML。故弃用 EncodedCommand(其当初动机——避免引号被吃——已由 quoteArg 同等解决)。
     *
     * <p>bash 分支同理:Cygwin/MSYS 的 argv 解析也按 MSVCRT 规则吃双引号,故命令须经
     * {@link #quoteArg} 包成单个完整参数再拼到 {@code -c} 后,否则 {@code -c} 只拿到
     * 第一个词,空格后全部散落成多余 argv。
     */
    private static String buildCommandLine(String shell, String command) {
        return switch (shell == null ? "auto" : shell.trim().toLowerCase(Locale.ROOT)) {
            case "powershell" -> "powershell.exe -NoProfile -Command " + quoteArg(command);
            // Windows 上的 Git Bash;未安装时子进程启动失败,由调用方兜底
            case "bash" -> "bash -c " + quoteArg(command);
            default -> "cmd.exe /c " + command;
        };
    }

    private static String drain(Win32HandleInputStream in) throws IOException {
        try (java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return bos.toString(StandardCharsets.UTF_8);
        }
    }

    /** 等待管道读取任务,超时/异常一律回退空串(输出采集非关键路径,宁丢勿挂)。 */
    private static String awaitQuiet(Future<String> task, int seconds) {
        try {
            return task.get(seconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "";
        }
    }

    /** 单流输出截断(stdout / stderr 各自适用)。 */
    private static String cap(String s, int max) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) + "\n[输出已截断至 " + max + " 字符]" : s;
    }

    /** 从原生管道读端句柄读字节(阻塞到 EOF)。 */
    private static final class Win32HandleInputStream extends java.io.InputStream {
        private final WinNT.HANDLE handle;
        private final byte[] one = new byte[1];

        Win32HandleInputStream(WinNT.HANDLE handle) {
            this.handle = handle;
        }

        @Override
        public int read() throws java.io.IOException {
            int n = read(one, 0, 1);
            return n <= 0 ? -1 : (one[0] & 0xFF);
        }

        @Override
        public int read(byte[] b, int off, int len) throws java.io.IOException {
            // jna-platform 的 ReadFile(byte[]) 固定从数组头写起;off 非 0 时经临时数组转拷
            byte[] target = off == 0 ? b : new byte[len];
            IntByReference nread = new IntByReference(0);
            boolean ok = K.ReadFile(handle, target, len, nread, null);
            int n = nread.getValue();
            if (!ok && n == 0) {
                return -1; // EOF / 管道关闭
            }
            if (n > 0 && off != 0) {
                System.arraycopy(target, 0, b, off, n);
            }
            return n;
        }
    }
}
