package dev.everyagent.plugin.sandbox.codex.win.struct;

import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.WinBase;

/**
 * STARTUPINFOEXW（jna-platform 仅有 STARTUPINFO，未收录 EX 扩展）。
 *
 * <p>对应 codex process.rs/proc_thread_attr.rs：以 {@code lpDesktop} 指向私有桌面
 * （不设时受限令牌 spawn PowerShell 会 STATUS_DLL_INIT_FAILED），并以
 * {@code lpAttributeList} 携带 JOB_LIST/HANDLE_LIST 属性，创建标志须同时带
 * {@code EXTENDED_STARTUPINFO_PRESENT}。
 *
 * <p>使用约定：实例化后须把 {@code cb} 置为 {@link #size()}（而非父类尺寸），
 * 属性列表指针由调用方以两次 {@code InitializeProcThreadAttributeList} 惯用法在
 * {@link Pointer}（Memory）上构建后填入。
 */
@Structure.FieldOrder({ "lpAttributeList" })
public class StartupInfoExW extends WinBase.STARTUPINFO {

    /** LPPROC_THREAD_ATTRIBUTE_LIST：不透明句柄，按裸指针传递。 */
    public Pointer lpAttributeList;

    public StartupInfoExW() {
        super();
        // cb 必须覆盖扩展字段，CreateProcess 依据它判定这是 STARTUPINFOEXW。
        this.cb = new com.sun.jna.platform.win32.WinDef.DWORD(size());
    }
}
