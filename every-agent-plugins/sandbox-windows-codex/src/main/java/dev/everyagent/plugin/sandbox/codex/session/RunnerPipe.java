package dev.everyagent.plugin.sandbox.codex.session;

import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.PointerByReference;

import dev.everyagent.plugin.sandbox.codex.runner.RunnerPaths;
import dev.everyagent.plugin.sandbox.codex.runner.Win32Exception;
import dev.everyagent.plugin.sandbox.codex.win.Advapi32Ex;
import dev.everyagent.plugin.sandbox.codex.win.Kernel32Ex;
import dev.everyagent.plugin.sandbox.codex.win.WinErr;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 宿主侧命名管道服务端（设计文档 §2.7/§4.3，对齐 codex runner_pipe.rs）。
 *
 * <p>{@link #create}：CreateNamedPipeW 建单实例服务端管道——BYTE/READMODE_BYTE/WAIT、
 * 1 实例、出入缓冲各 {@link RunnerPaths#PIPE_BUFFER_BYTES}，DACL 由 SDDL
 * {@code D:(A;;GA;;;<sandbox_sid>)} 经 ConvertStringSecurityDescriptorToSecurityDescriptorW
 * 转换（只授沙箱账户 GENERIC_ALL，防预占/枚举，与 128 位 nonce 双因子之一）。
 *
 * <p>{@link #connect(int, long)}：ConnectNamedPipe 无超时参数——辅助<b>平台线程</b>
 * 先 DuplicateHandle 发布自身可取消句柄再阻塞连接，父侧限时等待，超时
 * CancelSynchronousIo 打断（ERROR_NOT_FOUND = 恰好已完成，再收割一次；取消成功
 * 后不 join，靠 close() 关管道句柄解阻塞）——逐句对齐
 * runner_client.rs::connect_pipe_with_timeout。连接后
 * GetNamedPipeClientProcessId 必须 == spawn 返回的 PID（双因子之二）。
 *
 * <p>仅 Windows 运行时使用；跨平台单测不触达本类。
 */
final class RunnerPipe implements AutoCloseable {

    /** ERROR_OPERATION_ABORTED(995)：CancelSynchronousIo 打断后的 ConnectNamedPipe 返回。 */
    private static final int ERROR_OPERATION_ABORTED = 995;
    /** DUPLICATE_SAME_ACCESS。 */
    private static final int DUPLICATE_SAME_ACCESS = 2;

    private final String name;
    private volatile WinNT.HANDLE handle;
    private volatile Thread parkedConnect; // 被取消后仍可能阻塞在 ConnectNamedPipe 的线程

    private RunnerPipe(String name, WinNT.HANDLE handle) {
        this.name = name;
        this.handle = handle;
    }

    /** CreateNamedPipeW 服务端管道（access = INBOUND/OUTBOUND，见 {@link RunnerPaths}）。 */
    static RunnerPipe create(String name, int access, String sandboxSid) {
        String sddl = String.format(RunnerPaths.PIPE_SDDL_FORMAT, sandboxSid);
        PointerByReference sd = new PointerByReference();
        if (!Advapi32Ex.INSTANCE.ConvertStringSecurityDescriptorToSecurityDescriptorW(
                sddl, Advapi32Ex.SDDL_REVISION_1, sd, null)) {
            throw new Win32Exception("ConvertStringSecurityDescriptorToSecurityDescriptorW",
                    Kernel32Ex.INSTANCE.GetLastError());
        }
        WinBase.SECURITY_ATTRIBUTES sa = new WinBase.SECURITY_ATTRIBUTES();
        sa.lpSecurityDescriptor = sd.getValue();
        sa.bInheritHandle = false;
        sa.write();
        try {
            WinNT.HANDLE h = Kernel32.INSTANCE.CreateNamedPipe(name, access,
                    WinBase.PIPE_TYPE_BYTE | WinBase.PIPE_READMODE_BYTE | WinBase.PIPE_WAIT,
                    RunnerPaths.PIPE_INSTANCES,
                    RunnerPaths.PIPE_BUFFER_BYTES, RunnerPaths.PIPE_BUFFER_BYTES,
                    0 /* 默认连接超时（仅 WaitNamedPipe 客户端轮询用） */, sa);
            if (h == null || WinBase.INVALID_HANDLE_VALUE.equals(h)) {
                throw new Win32Exception("CreateNamedPipeW(" + name + ")",
                    Kernel32Ex.INSTANCE.GetLastError());
            }
            return new RunnerPipe(name, h);
        } finally {
            Kernel32.INSTANCE.LocalFree(sd.getValue());
        }
    }

    String name() {
        return name;
    }

    WinNT.HANDLE handle() {
        return handle;
    }

    /** 阻塞 ConnectNamedPipe + 客户端 PID 校验（codex connect_pipe；535 已连接容忍）。 */
    private static void connectBlocking(WinNT.HANDLE pipe, int expectedRunnerPid)
            throws IOException {
        if (!Kernel32.INSTANCE.ConnectNamedPipe(pipe, null)) {
            int err = Kernel32.INSTANCE.GetLastError();
            if (err != WinErr.ERROR_PIPE_CONNECTED && err != ERROR_OPERATION_ABORTED) {
                throw new IOException("ConnectNamedPipe(" + pipe + ") failed: " + err);
            }
            if (err == ERROR_OPERATION_ABORTED) {
                throw new IOException("ConnectNamedPipe cancelled");
            }
        }
        verifyClientPid(pipe, expectedRunnerPid);
    }

    /** GetNamedPipeClientProcessId == expected，否则 PermissionDenied（对齐 codex）。 */
    static void verifyClientPid(WinNT.HANDLE pipe, int expectedRunnerPid) throws IOException {
        WinDef.ULONGByReference clientPid = new WinDef.ULONGByReference();
        if (!Kernel32.INSTANCE.GetNamedPipeClientProcessId(pipe, clientPid)) {
            throw new IOException("GetNamedPipeClientProcessId failed: "
                    + Kernel32.INSTANCE.GetLastError());
        }
        if (clientPid.getValue().intValue() != expectedRunnerPid) {
            throw new IOException("named pipe client pid " + clientPid.getValue()
                    + " did not match runner pid " + expectedRunnerPid);
        }
    }

    /**
     * 限时连接：辅助平台线程阻塞 connect + 超时 CancelSynchronousIo
     * （connect_pipe_with_timeout 语义；超时/失败由调用方 TerminateProcess 收尸）。
     */
    void connect(int expectedRunnerPid, long timeoutMs) throws IOException {
        ConnectTask task = new ConnectTask();
        Thread worker = Thread.ofPlatform()
                .name("codex-pipe-connect-" + name).unstarted(() -> task.run(handle,
                        expectedRunnerPid));
        worker.start();
        try {
            task.published.await(); // 等句柄发布（或 DuplicateHandle 失败上报）
            if (task.dupFailure != null) {
                throw new IOException("DuplicateHandle for pipe connect failed: "
                        + task.dupFailure);
            }
            if (!task.completed.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                // 竞态收割：等待期内恰好完成（对齐 try_take_completed_connect_result）
                if (task.completed.getCount() == 0) {
                    task.rethrowFailure();
                    return;
                }
                if (!Kernel32Ex.INSTANCE.CancelSynchronousIo(task.cancellableHandle)) {
                    int err = Kernel32Ex.INSTANCE.GetLastError();
                    if (err == WinErr.ERROR_NOT_FOUND) {
                        // 取消时恰好已完成：再收割一次
                        if (!task.completed.await(200, TimeUnit.MILLISECONDS)) {
                            throw new IOException("timed out after " + timeoutMs
                                    + "ms connecting " + name);
                        }
                        task.rethrowFailure();
                        return;
                    }
                    throw new IOException("CancelSynchronousIo failed for " + name
                            + ": " + err);
                }
                // 取消成功：不 join（防二次无限等待），靠 close() 关管道解阻塞
                parkedConnect = worker;
                throw new IOException("timed out after " + timeoutMs + "ms connecting " + name);
            }
            task.rethrowFailure();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while connecting " + name, e);
        }
    }

    /** 连接任务：发布可取消句柄 → 阻塞连接 → 上报结果。 */
    private static final class ConnectTask {
        final CountDownLatch published = new CountDownLatch(1);
        final CountDownLatch completed = new CountDownLatch(1);
        volatile WinNT.HANDLE cancellableHandle;
        volatile Integer dupFailure;
        volatile IOException failure; // null = 连接成功

        void run(WinNT.HANDLE pipe, int expectedRunnerPid) {
            WinNT.HANDLE current = Kernel32.INSTANCE.GetCurrentProcess();
            WinNT.HANDLEByReference dup = new WinNT.HANDLEByReference();
            if (!Kernel32.INSTANCE.DuplicateHandle(current,
                    Kernel32.INSTANCE.GetCurrentThread(), current, dup, 0, false,
                    DUPLICATE_SAME_ACCESS)) {
                dupFailure = Kernel32.INSTANCE.GetLastError();
                published.countDown();
                completed.countDown();
                return;
            }
            cancellableHandle = dup.getValue();
            published.countDown();
            try {
                connectBlocking(pipe, expectedRunnerPid);
            } catch (IOException e) {
                failure = e; // 上报给父侧（connect 结果通道语义）
            } finally {
                Kernel32Ex.INSTANCE.CloseHandle(dup.getValue());
                completed.countDown();
            }
        }

        /** 连接已结束：失败即抛（成功静默返回）。 */
        void rethrowFailure() throws IOException {
            IOException e = failure;
            if (e != null) {
                throw e;
            }
        }
    }

    /** 关闭管道句柄（同时为被取消的连接线程解阻塞）；幂等。 */
    @Override
    public void close() {
        WinNT.HANDLE h = handle;
        handle = null;
        if (h != null) {
            Kernel32Ex.INSTANCE.CloseHandle(h);
        }
        Thread parked = parkedConnect;
        if (parked != null) {
            try {
                parked.join(200); // 关句柄后阻塞 connect 通常立即失败返回
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
