package dev.everyagent.plugin.sandbox.codex.runner;

import com.sun.jna.platform.win32.Advapi32;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinReg;

/**
 * HKCU 健康探测（runner 以沙箱账户身份运行时的启动自检，design.md §4.3.1 #8）。
 *
 * <p><b>为什么需要</b>：沙箱账户被删重建（RID 更换）后，{@code C:\Users\<account>} 的旧
 * NTUSER.DAT 仍残留——其 hive 内部 ACL 只授权已删除的旧 SID。{@code LOGON_WITH_PROFILE}
 * 照常把它加载进登录会话（加载由 SYSTEM 完成，不看账户能否访问），但新账户对
 * {@code HKEY_CURRENT_USER} 的<b>任何读访问</b>都会 ACCESS_DENIED。后果分级：
 * PowerShell 5.1 启动期无条件读 GPO 策略键
 * {@code Software\Policies\Microsoft\Windows\PowerShell}（OpenSubKey 把拒绝映射成
 * {@code SecurityException: Requested registry access is not allowed}）→ 进程
 * 0xE0434352 崩溃，powershell 工具全灭；cmd/java/git 不读该链，静默无症状——所以必须
 * 由 runner 主动探测，把「隐形损坏」变成可自愈的显式失败信号（error 帧 stage=
 * {@code hkcu_profile_check}）。
 *
 * <p>探测动作 = {@code RegOpenKeyEx(HKEY_CURRENT_USER, "Software", 0, KEY_READ)}：
 * 单次注册表读，亚毫秒级，不在任何启动预算的关键路径上（pipes opened 之后、主循环之前）。
 * 返回 0 = 健康；非 0 = Win32 错误码（典型 5=ERROR_ACCESS_DENIED），原样随 error 帧
 * 上报，worker 侧按 stage（而非错误码——5 过于通用）判定可自愈。
 */
public final class HkcuHealth {

    /** 探测子键：PS 5.1 GPO 读取链的祖先键，任何 HKCU 用途都绕不开它。 */
    static final String PROBE_SUBKEY = "Software";

    private HkcuHealth() {
    }

    /**
     * @return 0 = 当前账户可读 HKCU；非 0 = RegOpenKeyEx 的 LSTATUS（Win32 错误码）
     */
    public static int probeSoftwareKeyRead() {
        WinReg.HKEYByReference keyRef = new WinReg.HKEYByReference();
        int status = Advapi32.INSTANCE.RegOpenKeyEx(
                WinReg.HKEY_CURRENT_USER, PROBE_SUBKEY, 0, WinNT.KEY_READ, keyRef);
        if (status != 0) {
            return status;
        }
        Advapi32.INSTANCE.RegCloseKey(keyRef.getValue());
        return 0;
    }
}
