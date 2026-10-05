package dev.everyagent.plugin.sandbox.codex.win;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.BaseTSD;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.win32.W32APIOptions;

/**
 * kernel32 补齐映射（设计文档 §2.1/§3）。
 *
 * <p>jna-platform 5.16 的 {@link Kernel32} 已提供命名管道套件（CreateNamedPipeW/
 * ConnectNamedPipe/PeekNamedPipe/GetNamedPipeClientProcessId）、CreateProcessW、
 * CreateFile/CreateMutex、DuplicateHandle/SetHandleInformation、GetExitCodeProcess、
 * SetErrorMode、QueryFullProcessImageNameW、TerminateProcess、LocalFree——直接复用；
 * 本接口按 JNA 惯用法「扩展平台接口再 load」（对照 mic 的 Win32Ex）只补它尚未覆盖的部分：
 * Job Object 系列、PROC_THREAD_ATTRIBUTE 系列、CreateProcessAsUserW、CancelSynchronousIo。
 *
 * <p>仅 Windows 运行时被调用；接口在所有平台均可编译（INSTANCE 是运行时行为）。
 */
public interface Kernel32Ex extends Kernel32 {

    Kernel32Ex INSTANCE = Native.load("kernel32", Kernel32Ex.class, W32APIOptions.UNICODE_OPTIONS);

    /**
     * 创建 Job Object——对应 codex job.rs：KILL_ON_JOB_CLOSE 只做整树终止不做配额。
     * 对照 mic Win32Ex.SandboxKernel32（平台无此映射）。
     */
    WinNT.HANDLE CreateJobObjectW(WinBase.SECURITY_ATTRIBUTES lpJobAttributes, String lpName);

    /** 设置 Job Object 限额（infoClass 用 {@link #JobObjectExtendedLimitInformation}）。 */
    boolean SetInformationJobObject(WinNT.HANDLE hJob, int jobObjectInfoClass,
            Structure lpJobObjectInfo, int cbJobObjectInfoLength);

    /** 把进程挂进 Job——codex 走 PROC_THREAD_ATTRIBUTE_JOB_LIST 原子挂接时不用它，保留给修复/兜底路径。 */
    boolean AssignProcessToJobObject(WinNT.HANDLE hJob, WinNT.HANDLE hProcess);

    /** 终止 Job 内全部进程（看门狗超时/取消路径，失败回退 TerminateProcess）。 */
    boolean TerminateJobObject(WinNT.HANDLE hJob, int uExitCode);

    /** 查询 Job Object 信息（后续步骤排查用）。 */
    boolean QueryInformationJobObject(WinNT.HANDLE hJob, int jobObjectInfoClass,
            Structure lpJobObjectInfo, int cbJobObjectInfoLength, IntByReference lpReturnLength);

    /**
     * 以指定令牌创建进程——对应 codex process.rs::spawn：受限令牌 + lpDesktop（私有桌面）+
     * CREATE_NO_WINDOW|CREATE_UNICODE_ENVIRONMENT + 句柄白名单。
     * 命令行用 {@code char[]}（CreateProcess* 会就地修改，末尾须自带 NUL）。
     */
    boolean CreateProcessAsUserW(WinNT.HANDLE hToken, String lpApplicationName, char[] lpCommandLine,
            WinBase.SECURITY_ATTRIBUTES lpProcessAttributes, WinBase.SECURITY_ATTRIBUTES lpThreadAttributes,
            boolean bInheritHandles, int dwCreationFlags, Pointer lpEnvironment,
            String lpCurrentDirectory, WinBase.STARTUPINFO lpStartupInfo,
            WinBase.PROCESS_INFORMATION lpProcessInformation);

    /**
     * 初始化属性列表——对应 codex proc_thread_attr.rs。两次调用惯用法：
     * 先传 null 拿 cbSize（返回 FALSE 且 GetLastError=ERROR_INSUFFICIENT_BUFFER），
     * 再以 {@link Pointer}（Memory）作 lpAttributeList 二次调用。
     */
    boolean InitializeProcThreadAttributeList(Pointer lpAttributeList, int dwAttributeCount,
            int dwFlags, LongByReference lpSize);

    /**
     * 写入单个线程属性——codex 用 {@link #PROC_THREAD_ATTRIBUTE_JOB_LIST}（Job 原子挂接，
     * 失败即拒绝 spawn）与 {@link #PROC_THREAD_ATTRIBUTE_HANDLE_LIST}（只继承 stdio 管道）。
     * dwAttribute 为 DWORD_PTR（x64 上 8 字节），声明成 {@link BaseTSD.DWORD_PTR} 保证位宽。
     */
    boolean UpdateProcThreadAttribute(Pointer lpAttributeList, int dwFlags,
            BaseTSD.DWORD_PTR dwAttribute, Pointer lpValue, long cbSize,
            Pointer lpPreviousValue, LongByReference lpReturnSize);

    /** 释放属性列表（配对 {@link #InitializeProcThreadAttributeList}）。 */
    void DeleteProcThreadAttributeList(Pointer lpAttributeList);

    /**
     * 取消指定线程的阻塞 I/O——对应 codex runner_pipe.rs：ConnectNamedPipe 无超时参数，
     * 15s 超时后取消虚拟线程的阻塞连接；返回 ERROR_NOT_FOUND 表示恰好已完成，再收割一次。
     */
    boolean CancelSynchronousIo(WinNT.HANDLE hThread);

    // ---- 控制台（子进程码页锚点，见 ConsoleAnchor） ----
    // jna-platform 5.14 的 Kernel32 未收录这几个 API（javap 已核实），在此补齐。

    /** 当前进程是否挂着控制台；无控制台返回 NULL。 */
    WinDef.HWND GetConsoleWindow();

    /** 为无控制台的进程分配一个新控制台（会带窗口，调用方负责隐藏）。 */
    boolean AllocConsole();

    /**
     * 挂到指定进程的控制台——用 {@link #ATTACH_PARENT_PROCESS} 继承父进程（worker JVM）控制台，
     * 比 AllocConsole 更优：不新建窗口、码页调整顺带覆盖同控制台下的其他子进程。
     * 失败（父进程本无控制台/本进程已有控制台）返回 FALSE。
     */
    boolean AttachConsole(int dwFlags);

    /** 解除本进程与该控制台的关联（探测/回滚用）。 */
    boolean FreeConsole();

    /** 设置控制台输出码页——<b>影响后续 spawn 的子进程在启动时读到的码页</b>（正是乱码开关）。 */
    boolean SetConsoleOutputCP(int wCodePageID);

    /** 设置控制台输入码页。 */
    boolean SetConsoleCP(int wCodePageID);

    /** 读当前控制台输出码页（用于确认设置是否真的生效）。 */
    int GetConsoleOutputCP();

    /** 本机 OEM 码页（随系统语言而变：中文 936、日文 932、西欧 850…）——<b>只能问系统，不可假设</b>。 */
    int GetOEMCP();

    /** 本机 ANSI 码页（ACP）。同上，运行时查询。 */
    int GetACP();

    // ---- 常量（jna-platform 未收录的部分） ----

    /** AttachConsole 的伪 PID：挂到父进程的控制台。 */
    int ATTACH_PARENT_PROCESS = -1;

    /**
     * STARTUPINFO.dwFlags：使用 wShowWindow。配合 {@link #SW_HIDE} 使用——父进程若没有控制台,
     * 子进程(控制台子系统程序)会被系统新建一个<b>可见</b>控制台窗口;此标志把它压成隐藏。
     */
    int STARTF_USESHOWWINDOW = 0x00000001;
    /** ShowWindow / wShowWindow：隐藏窗口。 */
    int SW_HIDE = 0;

    /** CreateProcess：无窗口（WinBase 有 CREATE_UNICODE_ENVIRONMENT 等，但缺此值）。 */
    int CREATE_NO_WINDOW = 0x08000000;

    /**
     * Windows 对 UTF-8 的码页标识（winnls.h 的 {@code CP_UTF8}）——<b>API 常量，不是语言环境假设</b>：
     * 本机 OEM/ACP 一律用 {@link #GetOEMCP()}/{@link #GetACP()} 运行时查询，绝不写死
     * （中文 936、日文 932、西欧 850/1252 各不相同）。
     */
    int CP_UTF8 = 65001;

    /** Job Object 限额标志：关句柄杀整树（codex job.rs 唯一使用的 limit 位）。 */
    int JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x00002000;
    /** Job Object 限额标志：允许子进程突破（BREAKAWAY，与 CREATE_BREAKAWAY_FROM_JOB 配套）。 */
    int JOB_OBJECT_LIMIT_BREAKAWAY_OK = 0x00000800;

    /** SetInformationJobObject 信息类：扩展限额。 */
    int JobObjectExtendedLimitInformation = 9;

    /** PROC_THREAD_ATTRIBUTE_JOB_LIST：Job Object 原子挂接（设计文档 §2.6）。 */
    int PROC_THREAD_ATTRIBUTE_JOB_LIST = 0x0002000D;
    /** PROC_THREAD_ATTRIBUTE_HANDLE_LIST：句柄继承白名单（只继承 stdio 管道）。 */
    int PROC_THREAD_ATTRIBUTE_HANDLE_LIST = 0x00020002;

    /** PROC_THREAD_ATTRIBUTE 的 Number/Thread/Input 掩码（构造/解析属性值用）。 */
    int PROC_THREAD_ATTRIBUTE_NUMBER = 0x0000FFFF;
    int PROC_THREAD_ATTRIBUTE_THREAD = 0x00010000;
    int PROC_THREAD_ATTRIBUTE_INPUT = 0x00020000;
    int PROC_THREAD_ATTRIBUTE_ADDITIVE = 0x00040000;
}
