package dev.everyagent.plugin.sandbox.codex.setup;

import com.sun.jna.platform.win32.Advapi32;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.W32Errors;
import com.sun.jna.platform.win32.WinReg;

import java.nio.file.Path;

/**
 * 沙箱账户隐藏（对应 codex hide_users.rs，分析文档 §4.3/§5.2.2）。
 *
 * <p>两件事：① Winlogon 注册表
 * {@code HKLM\SOFTWARE\Microsoft\Windows NT\CurrentVersion\Winlogon\SpecialAccounts\UserList}
 * 下按用户名写 {@code REG_DWORD 0}（登录界面不显示；setup 侧 best-effort，失败只记日志）；
 * ② profile 目录 {@code FILE_ATTRIBUTE_HIDDEN|SYSTEM}（runner 侧首次登录产生 profile 后，
 * 此处提供静态方法供其调用）。卸载时 {@link #unhide} 删除这些注册表值。
 */
public final class HideUsers {

    /** Winlogon UserList 注册表路径。 */
    public static final String USERLIST_KEY_PATH =
            "SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion\\Winlogon\\SpecialAccounts\\UserList";

    private static final int REG_OPTION_NON_VOLATILE = 0;
    private static final int KEY_WRITE = 0x00020006;
    private static final int KEY_SET_VALUE = 0x0002;
    private static final int REG_DWORD = 4;

    private static final int FILE_ATTRIBUTE_HIDDEN = 0x00000002;
    private static final int FILE_ATTRIBUTE_SYSTEM = 0x00000004;
    private static final int INVALID_FILE_ATTRIBUTES = -1;

    private HideUsers() {
    }

    /**
     * 在 Winlogon UserList 下把各用户写 0（对齐 hide_newly_created_users /
     * hide_users_in_winlogon：best-effort，单值失败不中断）。
     *
     * @throws IllegalStateException 仅当键创建整体失败（调用方记日志后继续）
     */
    public static void hide(String[] usernames) {
        if (usernames.length == 0) {
            return;
        }
        WinReg.HKEYByReference keyRef = new WinReg.HKEYByReference();
        int status = Advapi32.INSTANCE.RegCreateKeyEx(WinReg.HKEY_LOCAL_MACHINE,
                USERLIST_KEY_PATH, 0, null, REG_OPTION_NON_VOLATILE, KEY_WRITE, null, keyRef,
                null);
        if (status != 0) {
            throw new IllegalStateException("RegCreateKeyExW UserList failed: " + status);
        }
        WinReg.HKEY key = keyRef.getValue();
        try {
            for (String username : usernames) {
                byte[] data = { 0, 0, 0, 0 };
                int set = Advapi32.INSTANCE.RegSetValueEx(key, username, 0, REG_DWORD, data,
                        data.length);
                if (set != 0) {
                    // best-effort：单值失败只留痕（对齐 log_note 语义）
                    System.getLogger(HideUsers.class.getName()).log(System.Logger.Level.WARNING,
                            "hide users: failed to set UserList value for {0}: {1}", username,
                            set);
                }
            }
        } finally {
            Advapi32.INSTANCE.RegCloseKey(key);
        }
    }

    /** 删除 UserList 隐藏值（对齐 unhide_sandbox_users：键缺失视为已清，错误聚合）。 */
    public static void unhide(String[] usernames) {
        WinReg.HKEYByReference keyRef = new WinReg.HKEYByReference();
        int status = Advapi32.INSTANCE.RegOpenKeyEx(WinReg.HKEY_LOCAL_MACHINE,
                USERLIST_KEY_PATH, 0, KEY_SET_VALUE, keyRef);
        if (status == W32Errors.ERROR_FILE_NOT_FOUND
                || status == W32Errors.ERROR_PATH_NOT_FOUND) {
            return;
        }
        if (status != 0) {
            throw new IllegalStateException("open sandbox hidden-user registry key: " + status);
        }
        WinReg.HKEY key = keyRef.getValue();
        try {
            StringBuilder errors = null;
            for (String username : usernames) {
                int del = Advapi32.INSTANCE.RegDeleteValue(key, username);
                if (del != 0 && del != W32Errors.ERROR_FILE_NOT_FOUND) {
                    if (errors == null) {
                        errors = new StringBuilder();
                    } else {
                        errors.append("; ");
                    }
                    errors.append("remove hidden sandbox user ").append(username)
                            .append(": ").append(del);
                }
            }
            if (errors != null) {
                throw new IllegalStateException(errors.toString());
            }
        } finally {
            Advapi32.INSTANCE.RegCloseKey(key);
        }
    }

    /**
     * 给目录打 HIDDEN|SYSTEM（对齐 hide_directory / hide_current_user_profile_dir）。
     * 已带属性返回 false（一次性）；失败抛 IllegalStateException 由调用方降级记日志。
     */
    public static boolean hideDirectory(Path dir) {
        int attrs = Kernel32.INSTANCE.GetFileAttributes(dir.toString());
        if (attrs == INVALID_FILE_ATTRIBUTES) {
            throw new IllegalStateException("GetFileAttributesW failed for " + dir + ": "
                    + com.sun.jna.Native.getLastError());
        }
        int newAttrs = attrs | FILE_ATTRIBUTE_HIDDEN | FILE_ATTRIBUTE_SYSTEM;
        if (newAttrs == attrs) {
            return false;
        }
        if (!Kernel32.INSTANCE.SetFileAttributes(dir.toString(),
                new com.sun.jna.platform.win32.WinDef.DWORD(newAttrs))) {
            throw new IllegalStateException("SetFileAttributesW failed for " + dir + ": "
                    + com.sun.jna.Native.getLastError());
        }
        return true;
    }
}
