package dev.everyagent.plugin.sandbox.codex.acl;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.win32.W32APIOptions;

/**
 * acl 包自带的 kernel32 微补齐（设计文档 §3「需自映射」清单项）。
 *
 * <p>jna-platform 5.16 的 {@link Kernel32} 未收录 {@code GetFinalPathNameByHandleW}；
 * 并行协作约束下不改既有 {@code win/Kernel32Ex}，按约定「缺原语在自己包内自建小接口」
 * 在 acl 包内自映射。对应 codex {@code acl.rs::ensure_handle_is_not_filesystem_root}
 * （{@code VOLUME_NAME_NONE} 解析句柄真实路径，识别文件系统根）。
 *
 * <p>仅 Windows 运行时被调用；接口在所有平台均可编译（{@code INSTANCE} 是运行时行为）。
 */
interface AclNative extends Kernel32 {

    AclNative INSTANCE = Native.load("kernel32", AclNative.class, W32APIOptions.UNICODE_OPTIONS);

    /**
     * 取句柄的最终路径——deny-read 根保护用。返回值为写入缓冲区的字符数（不含 NUL）；
     * 0 表示失败（GetLastError）。dwFlags 用 {@link AclPrimitives#VOLUME_NAME_NONE}
     * 时，文件系统根（如 {@code C:\}）的最终路径就是单个反斜杠。
     */
    int GetFinalPathNameByHandleW(WinNT.HANDLE hFile, char[] lpszFilePath, int cchFilePath,
            int dwFlags);
}
