package dev.everyagent.plugin.sandbox.codex.runner;

/**
 * Win32 调用失败异常：携带 {@code com.sun.jna.platform.win32.Kernel32#GetLastError}
 * 错误码，供 {@link CodexRunnerMain} 组装 IPC error 帧的 {@code windows_error_code}
 * （对齐 codex windows_error_code(&amp;err) 提取链）。
 */
public final class Win32Exception extends RuntimeException {

    private final int lastError;

    public Win32Exception(String operation, int lastError) {
        super(operation + " failed: " + lastError + " (0x"
                + Integer.toUnsignedString(lastError, 16).toUpperCase() + ")");
        this.lastError = lastError;
    }

    /** Win32 错误码（GetLastError 快照，调用失败瞬间捕获）。 */
    public int lastError() {
        return lastError;
    }

    /** 兼容 Kernel32/Kernel32Ex 两个 INSTANCE 的取码快捷构造。 */
    static Win32Exception of(String operation) {
        return new Win32Exception(operation,
                dev.everyagent.plugin.sandbox.codex.win.Kernel32Ex.INSTANCE.GetLastError());
    }
}
