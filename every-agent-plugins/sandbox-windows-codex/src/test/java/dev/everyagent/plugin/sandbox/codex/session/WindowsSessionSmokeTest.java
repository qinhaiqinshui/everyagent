package dev.everyagent.plugin.sandbox.codex.session;

import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinNT;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import dev.everyagent.plugin.sandbox.codex.accounts.SandboxAccounts;
import dev.everyagent.plugin.sandbox.codex.runner.FrameCodec;
import dev.everyagent.plugin.sandbox.codex.runner.IpcMessage;
import dev.everyagent.plugin.sandbox.codex.runner.IpcMessage.SpawnReady;
import dev.everyagent.plugin.sandbox.codex.runner.RunnerPaths;
import dev.everyagent.plugin.sandbox.codex.win.Kernel32Ex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Windows 专属冒烟（assumeTrue 守卫，非 Windows 跳过）：宿主侧命名管道创建/限时
 * 连接/PID 校验/帧往返。真 runner 进程（CreateProcessWithLogonW）端到端属
 * windows-admin 档（需已 setup 的沙箱账户），见设计 §8。
 */
class WindowsSessionSmokeTest {

    @Test
    void namedPipeHandshakeRoundTripWithPidCheck() throws Exception {
        Assumptions.assumeTrue(Platform.isWindows(), "Windows 专属");
        String name = RunnerPaths.outPipeName(RunnerPaths.newNonce()); // INBOUND：客户端写
        String selfSid = SandboxAccounts.sidString(Advapi32Util.getUserName());
        int selfPid = (int) ProcessHandle.current().pid();
        try (RunnerPipe server = RunnerPipe.create(name, RunnerPaths.PIPE_ACCESS_INBOUND,
                selfSid)) {
            Thread client = Thread.ofPlatform().name("codex-smoke-pipe-client").start(() -> {
                WinNT.HANDLE h = openPipeClient(name, WinNT.FILE_GENERIC_WRITE);
                FrameCodec.writeFrame(h, new SpawnReady(4242));
                Kernel32Ex.INSTANCE.CloseHandle(h);
            });
            try {
                server.connect(selfPid, 15_000); // 限时连接 + GetNamedPipeClientProcessId 校验
                FrameCodec.FramedMessage frame = FrameCodec.readFrame(server.handle());
                assertNotNull(frame);
                assertTrue(frame.message() instanceof SpawnReady, frame.message().toString());
                assertEquals(4242, ((SpawnReady) frame.message()).processId());
                assertEquals(IpcMessage.IPC_PROTOCOL_VERSION, frame.version());
            } finally {
                client.join(5_000);
            }
        }
    }

    @Test
    void timedOutConnectIsCancelledWithoutLeaking() throws Exception {
        Assumptions.assumeTrue(Platform.isWindows(), "Windows 专属");
        String name = RunnerPaths.inPipeName(RunnerPaths.newNonce());
        String selfSid = SandboxAccounts.sidString(Advapi32Util.getUserName());
        try (RunnerPipe server = RunnerPipe.create(name, RunnerPaths.PIPE_ACCESS_OUTBOUND,
                selfSid)) {
            long start = System.nanoTime();
            try {
                server.connect(1 /* 不可能有客户端 */, 500);
                throw new AssertionError("无客户端连接必须超时");
            } catch (java.io.IOException expected) {
                assertTrue(expected.getMessage().contains("timed out"), expected.getMessage());
            }
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertTrue(elapsedMs < 5_000, "超时经 CancelSynchronousIo 快速返回: " + elapsedMs);
        } // close()：句柄清理 + 为被取消线程解阻塞
    }

    /** CreateFileW 连接命名管道（runner 侧同款客户端形态；ERROR_PIPE_BUSY 短退避重试）。 */
    private static WinNT.HANDLE openPipeClient(String name, int desiredAccess) {
        for (int i = 0; i < 100; i++) {
            WinNT.HANDLE h = Kernel32.INSTANCE.CreateFile(name, desiredAccess, 0, null,
                    WinNT.OPEN_EXISTING, 0, null);
            if (h != null && !WinBase.INVALID_HANDLE_VALUE.equals(h)) {
                return h;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new IllegalStateException("CreateFileW(pipe " + name + ") failed: "
                + Kernel32.INSTANCE.GetLastError());
    }
}
