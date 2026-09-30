package dev.everyagent.plugin.sandbox.codex.setup;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

import dev.everyagent.plugin.sandbox.codex.win.Advapi32Ex;
import dev.everyagent.plugin.sandbox.codex.win.WinErr;

/**
 * {@code Global\EveryAgentCodexSetup} 跨进程互斥（对应 codex setup_mutex.rs，
 * 分析文档 §5.2.1）。
 *
 * <p>命名对齐（codex 为 {@code Global\CodexSandboxSetup\}）：本插件自有锁名。
 * SDDL {@code D:P(A;;GA;;;SY)(A;;GA;;;BA)}——仅 SYSTEM/管理员可持有，非提权进程
 * 创建/打开即被拒（fail-closed）。持有等待接受 WAIT_ABANDONED（崩溃者自动释放，
 * setup 自带账户修复）。guard 只在 {@link #close} 释放锁，不回滚不推进。
 *
 * <p>setup / 卸载 / 修复全程串行化账户与网络变更；线程绑定（同线程获取/释放）。
 */
public final class SetupLock implements AutoCloseable {

    /** 互斥体名（本插件自有命名空间）。 */
    public static final String MUTEX_NAME = "Global\\EveryAgentCodexSetup";

    /** 持锁等待：无限（setup 主路径）。 */
    public static final int WAIT_INFINITE = -1; // 0xFFFFFFFF

    private static final int WAIT_OBJECT_0 = 0x00000000;
    private static final int WAIT_ABANDONED = 0x00000080;
    private static final int WAIT_FAILED = 0xFFFFFFFF;

    private final WinNT.HANDLE handle;

    private SetupLock(WinNT.HANDLE handle) {
        this.handle = handle;
    }

    /**
     * 获取全局 setup 锁（对齐 acquire_sandbox_setup_lock）。
     *
     * @param timeoutMs 等待毫秒（{@link #WAIT_INFINITE} 或卸载路径的 5_000）
     * @throws SetupErrorReport.SetupException 创建/等待失败（HELPER_SETUP_LOCK_FAILED）
     */
    public static SetupLock acquire(long timeoutMs) {
        PointerByReference sdRef = new PointerByReference();
        if (!Advapi32Ex.INSTANCE.ConvertStringSecurityDescriptorToSecurityDescriptorW(
                "D:P(A;;GA;;;SY)(A;;GA;;;BA)", Advapi32Ex.SDDL_REVISION_1, sdRef,
                new IntByReference())) {
            throw new SetupErrorReport.SetupException(
                    SetupErrorReport.HELPER_SETUP_LOCK_FAILED,
                    "create sandbox setup mutex security failed: "
                            + com.sun.jna.Native.getLastError());
        }
        Pointer sd = sdRef.getValue();
        WinBase.SECURITY_ATTRIBUTES sa = new WinBase.SECURITY_ATTRIBUTES();
        sa.dwLength = new com.sun.jna.platform.win32.WinDef.DWORD(sa.size());
        sa.lpSecurityDescriptor = sd;
        sa.bInheritHandle = false;
        sa.write();
        WinNT.HANDLE handle = Kernel32.INSTANCE.CreateMutex(sa, false, MUTEX_NAME);
        int createError = com.sun.jna.Native.getLastError();
        Kernel32.INSTANCE.LocalFree(sd);
        if (handle == null) {
            throw new SetupErrorReport.SetupException(SetupErrorReport.HELPER_SETUP_LOCK_FAILED,
                    "open sandbox setup mutex failed (error " + createError
                            + "); elevation required");
        }
        int wait = Kernel32.INSTANCE.WaitForSingleObject(handle, (int) timeoutMs);
        if (wait == WAIT_OBJECT_0 || wait == WAIT_ABANDONED) {
            // ABANDONED：崩溃者释放，setup 侧有账户修复兜底（对齐 codex）。
            return new SetupLock(handle);
        }
        Kernel32.INSTANCE.CloseHandle(handle);
        if (wait == WinErr.WAIT_TIMEOUT) {
            throw new SetupErrorReport.SetupException(SetupErrorReport.HELPER_SETUP_LOCK_FAILED,
                    "timed out waiting for sandbox setup mutex after " + timeoutMs + " ms");
        }
        throw new SetupErrorReport.SetupException(SetupErrorReport.HELPER_SETUP_LOCK_FAILED,
                "wait for sandbox setup mutex failed: " + wait);
    }

    /** 释放锁并关句柄（guard Drop：仅释放，不回滚）。 */
    @Override
    public void close() {
        Kernel32.INSTANCE.ReleaseMutex(handle);
        Kernel32.INSTANCE.CloseHandle(handle);
    }
}
