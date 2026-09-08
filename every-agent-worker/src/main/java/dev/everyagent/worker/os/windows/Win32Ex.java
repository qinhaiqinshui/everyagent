package dev.everyagent.worker.os.windows;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.Advapi32;
import com.sun.jna.platform.win32.BaseTSD;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.W32APIOptions;

/**
 * 在 jna-platform 之上补齐进程沙箱所需的少量 Win32 原语。
 *
 * <p>jna-platform(5.16)已提供绝大多数映射({@code Kernel32}/{@code Advapi32} 的
 * OpenProcessToken / DuplicateTokenEx / CreatePipe / ReadFile / SetNamedSecurityInfo /
 * GetNamedSecurityInfo,以及 {@code WinBase}/{@code WinNT} 的 STARTUPINFO /
 * PROCESS_INFORMATION / SECURITY_ATTRIBUTES / HANDLE / DWORD / SIZE_T 等类型与常量);
 * 本接口按 JNA 惯用法「扩展平台接口再 load」,只补它尚未覆盖的部分:
 * <ul>
 *   <li>kernel32:CreateProcessAsUserW、Job Object 系列(Create/Query/Assign/Terminate)、ResumeThread;</li>
 *   <li>advapi32:CreateRestrictedToken、SetTokenInformation、AllocateAndInitializeSid/FreeSid、
 *       SDDL ↔ SecurityDescriptor 双向转换;</li>
 *   <li>结构体:Job 限额信息与 TOKEN_MANDATORY_LABEL(jna-platform 未收录)。</li>
 * </ul>
 * 均以 {@link W32APIOptions#UNICODE_OPTIONS} 加载:String/char[] 参数映射为 UTF-16,
 * 与 jna-platform 自身接口的编码行为一致(实测 String 直传 *W API 无 BOM 问题)。
 * 仅 Windows 运行时被本包调用;本接口在所有平台均可编译。
 */
public interface Win32Ex {

    /** kernel32:平台映射 + 沙箱补充。 */
    interface SandboxKernel32 extends Kernel32 {
        SandboxKernel32 I = Native.load("kernel32", SandboxKernel32.class, W32APIOptions.UNICODE_OPTIONS);

        WinNT.HANDLE CreateJobObjectW(WinBase.SECURITY_ATTRIBUTES lpJobAttributes, String lpName);

        boolean SetInformationJobObject(WinNT.HANDLE hJob, int jobObjectInfoClass,
                Structure lpJobObjectInfo, int cbJobObjectInfoLength);

        boolean AssignProcessToJobObject(WinNT.HANDLE hJob, WinNT.HANDLE hProcess);

        boolean TerminateJobObject(WinNT.HANDLE hJob, int uExitCode);

        int ResumeThread(WinNT.HANDLE hThread);

        /**
         * 命令行用 {@code char[]}(可写 UTF-16 缓冲,CreateProcess* 会就地修改;注意 JNA
         * 对原生数组参数不自动补 NUL 终止符,调用方须在末尾自带 {@code \0});
         * 工作目录用 String(W 变体不修改该参数)。
         * lpEnvironment 为 UTF-16 环境块时,调用方必须同时传
         * {@link WinBase#CREATE_UNICODE_ENVIRONMENT},否则本 API 拒收(ERROR_INVALID_PARAMETER=87)。
         */
        boolean CreateProcessAsUserW(WinNT.HANDLE hToken, String lpApplicationName, char[] lpCommandLine,
                WinBase.SECURITY_ATTRIBUTES lpProcessAttributes, WinBase.SECURITY_ATTRIBUTES lpThreadAttributes,
                boolean bInheritHandles, WinDef.DWORD dwCreationFlags, Pointer lpEnvironment,
                String lpCurrentDirectory, WinBase.STARTUPINFO lpStartupInfo,
                WinBase.PROCESS_INFORMATION lpProcessInformation);
    }

    /** advapi32:平台映射 + 降权/SDDL 补充。 */
    interface SandboxAdvapi32 extends Advapi32 {
        SandboxAdvapi32 I = Native.load("advapi32", SandboxAdvapi32.class, W32APIOptions.UNICODE_OPTIONS);

        boolean CreateRestrictedToken(WinNT.HANDLE hExistingToken, int flags,
                int disableSidCount, Pointer sidToDisable,
                int deletePrivCount, Pointer privilegesToDelete,
                int restrictSidCount, Pointer sidsToRestrict,
                WinNT.HANDLEByReference phNewToken);

        /** 载荷声明为 Structure:JNA 对 Structure 参数自动 write/read 同步(声明为 Pointer
         *  则不会同步,未 write 的内存会让内核解引用垃圾指针,ERROR_NOACCESS=998)。 */
        boolean SetTokenInformation(WinNT.HANDLE tokenHandle, int tokenInformationClass,
                Structure tokenInformation, int tokenInformationLength);

        boolean AllocateAndInitializeSid(Pointer pIdentifierAuthority, byte nSubAuthority,
                int dwSubAuthority0, int dwSubAuthority1, int dwSubAuthority2, int dwSubAuthority3,
                int dwSubAuthority4, int dwSubAuthority5, int dwSubAuthority6, int dwSubAuthority7,
                PointerByReference pSid);

        void FreeSid(Pointer pSid);

        /** SDDL 字符串 → 自相对安全描述符(LocalAlloc 分配,用后 LocalFree)。 */
        boolean ConvertStringSecurityDescriptorToSecurityDescriptorW(String sddl,
                int sddlRevision, PointerByReference ppSecurityDescriptor,
                IntByReference pSecurityDescriptorSize);

        /** 自相对安全描述符 → SDDL 字符串(LocalAlloc 分配的宽字符串,用后 LocalFree)。 */
        boolean ConvertSecurityDescriptorToStringSecurityDescriptorW(Pointer pSecurityDescriptor,
                int requestedStringSDRevision, int securityInformation,
                PointerByReference ppStringSecurityDescriptor, IntByReference lpcchStringLength);
    }

    // ---- 常量(jna-platform 未收录的部分) ----

    /** WaitForSingleObject 超时返回码。 */
    int WAIT_TIMEOUT = 0x00000102;
    /** SetHandleInformation:继承标志位。 */
    int HANDLE_FLAG_INHERIT = 0x00000001;

    /** Job Object 限额标志位。 */
    int JOB_OBJECT_LIMIT_ACTIVE_PROCESS = 0x00000008;
    int JOB_OBJECT_LIMIT_JOB_MEMORY = 0x00000200;
    int JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x00002000;

    /** SetInformationJobObject 的信息类。 */
    int JobObjectExtendedLimitInformation = 9;
    int JobObjectCpuRateHardCapInformation = 12;

    /** CreateRestrictedToken:剥离全部特权。 */
    int DISABLE_MAX_PRIVILEGE = 0x1;

    /** DuplicateTokenEx 的 token 类型 / 模拟级别。 */
    int TokenPrimary = 1;
    int SecurityImpersonation = 2;

    /** SetTokenInformation 的信息类:完整性级别。 */
    int TokenIntegrityLevel = 25;
    /** S-1-16-4096:Low 完整性 RID。 */
    int SECURITY_MANDATORY_LOW_RID = 0x1000;
    /** TOKEN_MANDATORY_LABEL.Label.Attributes 必须为 SE_GROUP_INTEGRITY(其余位被忽略)。 */
    int SE_GROUP_INTEGRITY = 0x00000020;

    /** SDDL 版本。 */
    int SDDL_REVISION_1 = 1;

    // ---- 结构体(jna-platform 未收录;字段类型复用其 WinDef/WinNT/BaseTSD) ----

    /** Job Object 基础限额。 */
    @Structure.FieldOrder({ "PerProcessUserTimeLimit", "PerJobUserTimeLimit", "LimitFlags",
            "MinimumWorkingSetLimit", "MaximumWorkingSetLimit", "ActiveProcessLimit",
            "Affinity", "PriorityClass", "SchedulingClass" })
    final class JOBOBJECT_BASIC_LIMIT_INFORMATION extends Structure {
        public WinNT.LARGE_INTEGER PerProcessUserTimeLimit;
        public WinNT.LARGE_INTEGER PerJobUserTimeLimit;
        public WinDef.DWORD LimitFlags;
        public BaseTSD.SIZE_T MinimumWorkingSetLimit;
        public BaseTSD.SIZE_T MaximumWorkingSetLimit;
        public WinDef.DWORD ActiveProcessLimit;
        public BaseTSD.DWORD_PTR Affinity;
        public WinDef.DWORD PriorityClass;
        public WinDef.DWORD SchedulingClass;

        public JOBOBJECT_BASIC_LIMIT_INFORMATION() {
            PerProcessUserTimeLimit = new WinNT.LARGE_INTEGER();
            PerJobUserTimeLimit = new WinNT.LARGE_INTEGER();
        }
    }

    /** Job Object 扩展限额(含 IO 计数与内存上限)。 */
    @Structure.FieldOrder({ "BasicLimitInformation", "IoInfo", "ProcessMemoryLimit",
            "JobMemoryLimit", "PeakProcessMemoryUsed", "PeakJobMemoryUsed" })
    final class JOBOBJECT_EXTENDED_LIMIT_INFORMATION extends Structure {
        public JOBOBJECT_BASIC_LIMIT_INFORMATION BasicLimitInformation;
        public WinNT.IO_COUNTERS IoInfo;
        public BaseTSD.SIZE_T ProcessMemoryLimit;
        public BaseTSD.SIZE_T JobMemoryLimit;
        public BaseTSD.SIZE_T PeakProcessMemoryUsed;
        public BaseTSD.SIZE_T PeakJobMemoryUsed;

        public JOBOBJECT_EXTENDED_LIMIT_INFORMATION() {
            BasicLimitInformation = new JOBOBJECT_BASIC_LIMIT_INFORMATION();
            IoInfo = new WinNT.IO_COUNTERS();
        }
    }

    /**
     * CPU 硬上限信息。
     * <p>注意 Windows 文档:{@code CpuRateHardCap} 单位为 <b>1/100 百分比</b>
     * (10000=100%、5000=50%、50=0.5%),取值 1~10000。因此构造参数按「0~100
     * 的整百分比」入参,这里 ×100 换算成内核单位——不可直接塞百分比,否则
     * 默认 50 会被当成 0.5% 把整棵进程树压死。
     */
    @Structure.FieldOrder({ "CpuRateHardCap" })
    final class JOBOBJECT_CPU_RATE_HARD_CAP_INFORMATION extends Structure {
        public WinDef.DWORD CpuRateHardCap;

        public JOBOBJECT_CPU_RATE_HARD_CAP_INFORMATION() {
        }

        public JOBOBJECT_CPU_RATE_HARD_CAP_INFORMATION(int capPercent) {
            int clamped = Math.max(1, Math.min(100, capPercent));
            CpuRateHardCap = new WinDef.DWORD(clamped * 100);
        }
    }

    /**
     * token 完整性标签(SetTokenInformation/TokenIntegrityLevel 的载荷)。
     * 不复用 jna-platform 的 {@code WinNT.SID_AND_ATTRIBUTES}:其 Sid 字段是 PSID
     * 结构体类型,序列化时会先写 PSID 内容,覆盖 AllocateAndInitializeSid 产出的
     * SID 内存头部;此处 Sid 用裸 Pointer,只传指针不改写目标。
     */
    @Structure.FieldOrder({ "Label" })
    final class TOKEN_MANDATORY_LABEL extends Structure {
        public SID_AND_ATTRIBUTES Label;
    }

    /** 见 {@link TOKEN_MANDATORY_LABEL}:Sid 为裸指针版的 SID_AND_ATTRIBUTES。 */
    @Structure.FieldOrder({ "Sid", "Attributes" })
    final class SID_AND_ATTRIBUTES extends Structure {
        public Pointer Sid;
        public int Attributes;
    }
}
