package dev.everyagent.plugin.sandbox.codex.win;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.W32APIOptions;

/**
 * secur32 补齐映射（LSA 登录会话枚举家族）。
 *
 * <p><b>为什么不在 {@link Advapi32Ex}</b>：{@code LsaEnumerateLogonSessions} 等
 * Lsa* 导出在 <b>Secur32.dll</b>（advapi32 无此符号，实测 UnsatisfiedLinkError
 * 「找不到指定的程序」），与 NetApi32Ex 的 netapi32 同理需要独立 Native.load。
 */
public interface Secur32Ex extends com.sun.jna.Library {

    Secur32Ex INSTANCE = Native.load("secur32", Secur32Ex.class, W32APIOptions.UNICODE_OPTIONS);

    /** NTSTATUS 成功（STATUS_SUCCESS = 0）。 */
    int STATUS_SUCCESS = 0;

    /**
     * 枚举本机全部登录会话（返回 NTSTATUS）。{@code logonSessionList} 指向
     * {@code PLUID} 数组（{@code logonSessionCount} 项），产物必须
     * {@link #LsaFreeReturnBuffer} 释放。无 TCB 特权的调用方对该函数本身可用；
     * 逐会话详情经 {@link #LsaGetLogonSessionData}（对非自身会话部分字段可能受限，
     * 计数应视为下界，见 LogonSessions javadoc）。
     */
    int LsaEnumerateLogonSessions(IntByReference logonSessionCount,
            PointerByReference logonSessionList);

    /** 按 LUID 取登录会话详情（返回 NTSTATUS；产物须 LsaFreeReturnBuffer 释放）。 */
    int LsaGetLogonSessionData(Pointer logonId, PointerByReference sessionData);

    /** 释放 LSA 返回缓冲（枚举列表 / 会话详情）。 */
    int LsaFreeReturnBuffer(Pointer returnBuffer);
}
