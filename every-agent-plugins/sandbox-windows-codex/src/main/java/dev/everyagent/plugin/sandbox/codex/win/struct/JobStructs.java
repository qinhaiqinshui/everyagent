package dev.everyagent.plugin.sandbox.codex.win.struct;

import com.sun.jna.Structure;
import com.sun.jna.platform.win32.BaseTSD;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;

/**
 * Job Object 限额结构体（jna-platform 未收录；字段类型复用其 WinDef/WinNT/BaseTSD）。
 *
 * <p>对应 codex job.rs：本插件只设 {@code LimitFlags = KILL_ON_JOB_CLOSE|BREAKAWAY_OK}，
 * 其余字段清零（不做配额）；载荷经 {@code SetInformationJobObject(
 * JobObjectExtendedLimitInformation, …)} 写入。
 */
public final class JobStructs {

    private JobStructs() {
    }

    /** Job Object 基础限额。 */
    @Structure.FieldOrder({ "PerProcessUserTimeLimit", "PerJobUserTimeLimit", "LimitFlags",
            "MinimumWorkingSetLimit", "MaximumWorkingSetLimit", "ActiveProcessLimit",
            "Affinity", "PriorityClass", "SchedulingClass" })
    public static final class JOBOBJECT_BASIC_LIMIT_INFORMATION extends Structure {
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

    /** Job Object 扩展限额（含 IO 计数；codex 不用内存上限，全零）。 */
    @Structure.FieldOrder({ "BasicLimitInformation", "IoInfo", "ProcessMemoryLimit",
            "JobMemoryLimit", "PeakProcessMemoryUsed", "PeakJobMemoryUsed" })
    public static final class JOBOBJECT_EXTENDED_LIMIT_INFORMATION extends Structure {
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
}
