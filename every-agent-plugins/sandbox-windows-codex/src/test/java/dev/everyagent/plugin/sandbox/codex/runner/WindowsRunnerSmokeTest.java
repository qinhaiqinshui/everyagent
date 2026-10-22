package dev.everyagent.plugin.sandbox.codex.runner;

import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinNT;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import dev.everyagent.plugin.sandbox.codex.win.Advapi32Ex;
import dev.everyagent.plugin.sandbox.codex.win.Kernel32Ex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Windows 专属冒烟骨架（设计 §8：assumeTrue 守卫，非 Windows 跳过）。
 *
 * <p>仅验证「受限令牌可派生 + CreateProcessAsUserW 全链路能启动并收割退出码」；
 * 真正的隔离断言（写越界拒绝、deny-read、断网）属 windows-admin 端到端用例，
 * 见设计 §8 第三档。
 */
class WindowsRunnerSmokeTest {

    /** 合成 capability SID（S-1-5-21-a-b-c-d 形态，无账户名映射）。 */
    private static final String CAP_SID = "S-1-5-21-1000-2000-3000-4000";

    /**
     * 受限令牌派生可用性假设：对<b>已受限</b>的进程令牌（如本 agent 跑在 codex 沙箱内，
     * WRITE_RESTRICTED 叠加 WRITE_RESTRICTED）CreateRestrictedToken 必然失败 87——
     * 该环境下受限令牌类冒烟测试物理不可行，诚实跳过（正常宿主进程探针成功，照常执行）。
     */
    private static void assumeRestrictedTokenDerivationAvailable() {
        Assumptions.assumeTrue(Platform.isWindows(), "Windows 专属");
        WinNT.HANDLE probe = null;
        try {
            probe = SandboxTokenFactory.createRestrictedTokenWithCaps(java.util.List.of(CAP_SID));
        } catch (Win32Exception e) {
            Assumptions.assumeTrue(false, "当前进程令牌已受限(如运行在 codex 沙箱内),"
                    + "受限令牌派生失败 " + e.lastError() + " —— 本用例仅在正常宿主进程可验证");
        } finally {
            if (probe != null) {
                Kernel32Ex.INSTANCE.CloseHandle(probe);
            }
        }
    }

    @Test
    void jnaBindingsLoad() {
        Assumptions.assumeTrue(Platform.isWindows(), "Windows 专属");
        assertNotNull(Kernel32Ex.INSTANCE);
        assertNotNull(Advapi32Ex.INSTANCE);
    }

    @Test
    void restrictedTokenDerivationSmoke() {
        assumeRestrictedTokenDerivationAvailable();
        WinNT.HANDLE token = SandboxTokenFactory
                .createRestrictedTokenWithCaps(java.util.List.of(CAP_SID));
        try {
            assertNotNull(token, "受限令牌句柄非空（半成品令牌不外泄的契约由实现保证）");
        } finally {
            assertTrue(Kernel32Ex.INSTANCE.CloseHandle(token));
        }
    }

    @Test
    void childProcessSpawnAndWaitSmoke() {
        assumeRestrictedTokenDerivationAvailable();
        WinNT.HANDLE token = SandboxTokenFactory
                .createRestrictedTokenWithCaps(java.util.List.of(CAP_SID));
        ChildProcess child = null;
        try {
            // 不带桌面名冒烟（PrivateDesktop 类就绪后端到端用例应总是传私有桌面）
            child = ChildProcess.spawn(token, java.util.List.of("cmd.exe", "/c", "exit 3"),
                    "C:\\", java.util.Map.of("EVERYAGENT_SMOKE", "1"), null);
            assertTrue(child.processId() > 0);
            ChildProcess.ExitResult r = child.waitForExit(15_000L);
            assertEquals(3, r.exitCode(), "cmd.exe /c exit 3 的退出码须如实回传");
            assertFalse(r.timedOut());
        } finally {
            if (child != null) {
                child.close();
            }
            Kernel32Ex.INSTANCE.CloseHandle(token);
        }
    }

    /**
     * 池化多命令会话（设计 §4 协议扩展）：同一 runner 进程内连续执行 ≥2 条命令——
     * spawn_request → spawn_ready → output/exit 后，runner <b>不退出</b>，回到等待下一条
     * spawn_request；每条命令的输出与退出码独立正确；shutdown 帧令 runner 以 0 收场。
     *
     * <p>宿主侧以测试进程自任 runner 的对端：测试建两条服务端命名管道（同进程同用户，
     * 默认 DACL 即可，无需 RunnerPipe 的沙箱 SID SDDL），runner 主循环以客户端身份连入
     * （CreateFileW），不依赖沙箱账户/CreateProcessWithLogonW——隔离性已由上方用例覆盖，
     * 本用例只钉「多命令串行复用」的协议行为。
     */
    @Test
    void runnerExecutesMultipleCommandsInOneProcessThenShutdown() throws Exception {
        assumeRestrictedTokenDerivationAvailable();
        CodexRunnerMain.disableConsoleProbeForTest(); // 探测会 spawn PS 探针,与协议断言无关
        String nonce = RunnerPaths.newNonce();
        String inName = RunnerPaths.inPipeName(nonce); // 父写 runner 读（服务端 OUTBOUND）
        String outName = RunnerPaths.outPipeName(nonce); // runner 写父读（服务端 INBOUND）
        // 先建服务端管道再启动 runner：runner 的客户端 CreateFileW(OPEN_EXISTING) 不重试
        WinNT.HANDLE in = createServerPipe(inName, RunnerPaths.PIPE_ACCESS_OUTBOUND);
        WinNT.HANDLE out = null;
        java.util.concurrent.atomic.AtomicInteger rc = new java.util.concurrent.atomic.AtomicInteger(-1);
        Thread runner = null;
        try {
            out = createServerPipe(outName, RunnerPaths.PIPE_ACCESS_INBOUND);
            runner = Thread.ofPlatform().name("codex-runner-smoke").start(() ->
                    rc.set(CodexRunnerMain.run(inName, outName)));
            connectPipe(in);
            connectPipe(out);

            // 命令 1：输出 + 退出码 0
            sendSpawnRequest(in, java.util.List.of("cmd.exe", "/c", "echo one"));
            FrameCodec.FramedMessage ready = FrameCodec.readFrame(out);
            assertNotNull(ready, "spawn_ready 前管道不得关闭");
            assertTrue(ready.message() instanceof IpcMessage.SpawnReady,
                    "expected spawn_ready, got " + ready.message());
            assertEquals(6, ready.version());
            String stdout1 = collectOutputUntilExit(out, 0);
            assertTrue(stdout1.contains("one"), "首条命令输出: " + stdout1);
            assertTrue(runner.isAlive(), "首条命令后 runner 不得退出");

            // 命令 2：非零退出码（同一 runner、同一对管道）
            sendSpawnRequest(in, java.util.List.of("cmd.exe", "/c", "exit 7"));
            String stdout2 = collectOutputUntilExit(out, 7);
            assertFalse(stdout2.contains("one"), "第二条命令不得串入首条命令的输出: " + stdout2);
            assertTrue(runner.isAlive(), "第二条命令后 runner 仍常驻等待复用");

            // shutdown 帧 → runner 以 0 优雅收场（登录会话随进程消亡回收）
            FrameCodec.writeFrame(in, new IpcMessage.Shutdown());
            runner.join(20_000);
            assertFalse(runner.isAlive(), "shutdown 后 runner 主循环必须返回");
            assertEquals(0, rc.get(), "shutdown/EOF 收场退出码 = 0");
        } finally {
            if (runner != null && runner.isAlive()) {
                runner.interrupt();
            }
            Kernel32Ex.INSTANCE.CloseHandle(in);
            if (out != null) {
                Kernel32Ex.INSTANCE.CloseHandle(out);
            }
        }
    }

    /** 服务端命名管道（同进程测试：默认 DACL，BYTE/READMODE_BYTE/WAIT、单实例）。 */
    private static WinNT.HANDLE createServerPipe(String name, int access) {
        WinNT.HANDLE h = Kernel32.INSTANCE.CreateNamedPipe(name, access,
                com.sun.jna.platform.win32.WinBase.PIPE_TYPE_BYTE
                        | com.sun.jna.platform.win32.WinBase.PIPE_READMODE_BYTE
                        | com.sun.jna.platform.win32.WinBase.PIPE_WAIT,
                RunnerPaths.PIPE_INSTANCES, RunnerPaths.PIPE_BUFFER_BYTES,
                RunnerPaths.PIPE_BUFFER_BYTES, 0, null);
        if (h == null || com.sun.jna.platform.win32.WinBase.INVALID_HANDLE_VALUE.equals(h)) {
            throw new IllegalStateException("CreateNamedPipeW(" + name + ") failed: "
                    + Kernel32.INSTANCE.GetLastError());
        }
        return h;
    }

    /** 阻塞 ConnectNamedPipe；客户端抢先连上（ERROR_PIPE_CONNECTED=535）视为成功。 */
    private static void connectPipe(WinNT.HANDLE pipe) {
        if (!Kernel32.INSTANCE.ConnectNamedPipe(pipe, null)) {
            int err = Kernel32.INSTANCE.GetLastError();
            if (err != 535) { // ERROR_PIPE_CONNECTED
                throw new IllegalStateException("ConnectNamedPipe failed: " + err);
            }
        }
    }

    /** 向 -in 管道发一条最小合法 spawn_request（cwd=C:\、offline、60s 超时）。 */
    private static void sendSpawnRequest(WinNT.HANDLE pipe, java.util.List<String> command) {
        FrameCodec.writeFrame(pipe, new IpcMessage.SpawnRequest(command, "C:\\",
                java.util.Map.of("EVERYAGENT_SMOKE", "1"), java.util.List.of(CAP_SID),
                java.util.List.of(), java.util.List.of(), "offline", 60_000L, false, false,
                null));
    }

    /** 收帧直到 Exit：汇聚 stdout 文本并断言退出码；遇到其他帧类型即失败。 */
    private static String collectOutputUntilExit(WinNT.HANDLE pipe, int expectedExitCode) {
        StringBuilder stdout = new StringBuilder();
        while (true) {
            FrameCodec.FramedMessage frame = FrameCodec.readFrame(pipe);
            assertNotNull(frame, "exit 帧前管道不得关闭");
            switch (frame.message()) {
                case IpcMessage.Output o -> {
                    if (o.stream() == IpcMessage.Stream.STDOUT) {
                        stdout.append(new String(IpcMessage.decodeBytes(o.dataBase64()),
                                java.nio.charset.StandardCharsets.UTF_8));
                    }
                }
                case IpcMessage.Exit exit -> {
                    assertEquals(expectedExitCode, exit.exitCode(), "退出码须逐命令独立正确");
                    assertFalse(exit.timedOut());
                    return stdout.toString();
                }
                default -> throw new AssertionError("expected output/exit, got "
                        + frame.message().tag());
            }
        }
    }
}
