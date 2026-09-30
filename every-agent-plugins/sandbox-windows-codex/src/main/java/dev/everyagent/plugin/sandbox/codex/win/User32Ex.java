package dev.everyagent.plugin.sandbox.codex.win;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.win32.W32APIOptions;

/**
 * user32 补齐映射：桌面对象系列（设计文档 §2.1 Kernel32Ex 行注明的「挂 user32」部分；
 * 因 {@code Native.load("kernel32")} 无法解析 user32 导出，独立成接口）。
 *
 * <p>对应 codex desktop.rs：受限令牌 spawn 不设 lpDesktop 时 PowerShell 会
 * STATUS_DLL_INIT_FAILED——每会话以随机名 {@code EveryAgentCodexDesktop-<hex>}
 * 新建私有桌面，经 STARTUPINFOEXW.lpDesktop（"\\…\\winsta\\desktop" 形态）传入。
 */
public interface User32Ex extends User32 {

    User32Ex INSTANCE = Native.load("user32", User32Ex.class, W32APIOptions.UNICODE_OPTIONS);

    /**
     * 创建桌面——desktop.rs：随机名、不继承（不传 SECURITY_ATTRIBUTES 则默认 DACL），
     * 建成后以同 logon SID 授 DESKTOP_ALL_ACCESS；dwDesiredAccess 用
     * {@code DESKTOP_ALL_ACCESS} 组合位。返回 HDESK（失败 null）。
     */
    WinNT.HANDLE CreateDesktopW(String lpszDesktop, String lpszDevice, Pointer pDevmode,
            int dwFlags, int dwDesiredAccess, WinBase.SECURITY_ATTRIBUTES lpdwSecurityAttributes);

    /** 打开既有桌面（复用/校验路径；本插件首期每会话新建不复用）。 */
    WinNT.HANDLE OpenDesktopW(String lpszDesktop, int dwFlags, boolean fInherit,
            int dwDesiredAccess);

    /** 关闭桌面句柄（会话收束时）。 */
    boolean CloseDesktopW(WinNT.HANDLE hDesktop);

    // ---- 桌面访问位（winuser.h；DESKTOP_ALL_ACCESS = 全部按位或，后续步骤组合） ----

    int DESKTOP_READOBJECTS = 0x0001;
    int DESKTOP_CREATEWINDOW = 0x0002;
    int DESKTOP_CREATEMENU = 0x0004;
    int DESKTOP_HOOKCONTROL = 0x0008;
    int DESKTOP_JOURNALRECORD = 0x0010;
    int DESKTOP_JOURNALPLAYBACK = 0x0020;
    int DESKTOP_ENUMERATE = 0x0040;
    int DESKTOP_WRITEOBJECTS = 0x0080;
    int DESKTOP_SWITCHDESKTOP = 0x0100;
}
