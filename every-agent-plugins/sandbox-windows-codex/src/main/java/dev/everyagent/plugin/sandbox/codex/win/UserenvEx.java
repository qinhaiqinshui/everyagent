package dev.everyagent.plugin.sandbox.codex.win;

import com.sun.jna.Native;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

/**
 * userenv.dll 自映射（jna-platform 无 userenv 接口；设计文档 §2.1 UserenvEx）。
 *
 * <p>对应 codex uninstall_windows/users.rs：卸载第二阶段 DeleteProfileW 清 profile
 * 目录（LoadUserProfile 仅卸载路径需要，暂缓——设计文档 §2.1）。
 * 返回非零即失败（Win32 错误码，GetLastError）。
 */
public interface UserenvEx extends StdCallLibrary {

    UserenvEx INSTANCE = Native.load("userenv", UserenvEx.class, W32APIOptions.UNICODE_OPTIONS);

    /**
     * 删除账户 profile——users.rs::remove_users：sidString 为账户 SID 字符串，
     * profilePath 传 null 由系统按 SID 定位，computerName 传 null 表本机；
     * profile 不存在返回非零（ERROR_FILE_NOT_FOUND 视作已清）。
     */
    int DeleteProfileW(String sidString, String profilePath, String computerName);
}
