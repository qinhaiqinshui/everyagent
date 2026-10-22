package dev.everyagent.plugin.sandbox.codex.runner;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 池化多命令会话的读线程↔执行线程交接逻辑（{@link CodexRunnerMain.RunnerState}）
 * ——纯 Java 跨平台，不依赖 Windows 管道/受限令牌（那些在 WindowsRunnerSmokeTest）。
 */
class CodexRunnerLoopTest {

    private static final IpcMessage.SpawnRequest REQ = new IpcMessage.SpawnRequest(
            List.of("cmd.exe"), "C:\\", Map.of(), List.of("S-1-5-21-1-2-3-4"),
            List.of(), List.of(), "offline", 1000L, false, false, null);

    @Test
    void offerThenTakeHandsCommandToExecutor() throws Exception {
        CodexRunnerMain.RunnerState state = new CodexRunnerMain.RunnerState();
        assertTrue(state.offerCommand(new FrameCodec.FramedMessage(6, REQ)));
        assertSame(REQ, state.takeCommand().message(), "命令按 FIFO 交给执行线程");
    }

    @Test
    void endPoisonsExecutorAndDropsUntakenCommand() {
        CodexRunnerMain.RunnerState state = new CodexRunnerMain.RunnerState();
        assertTrue(state.offerCommand(new FrameCodec.FramedMessage(6, REQ)));
        state.end(); // 父进程离开:队列里未取走的命令无执行意义,投毒必须落位
        assertNull(state.takeCommand(), "end 后 takeCommand 立即收场(不清队列会永久阻塞)");
    }

    @Test
    void offerAfterEndRejectedAsProtocolEnd() {
        CodexRunnerMain.RunnerState state = new CodexRunnerMain.RunnerState();
        state.end();
        assertFalse(state.offerCommand(new FrameCodec.FramedMessage(6, REQ)),
                "已终结后不得再接收命令(读线程据此按协议违规收场)");
        assertNull(state.takeCommand());
    }

    @Test
    void queueCapacityOneEnforcesExclusiveUse() {
        CodexRunnerMain.RunnerState state = new CodexRunnerMain.RunnerState();
        assertTrue(state.offerCommand(new FrameCodec.FramedMessage(6, REQ)));
        assertFalse(state.offerCommand(new FrameCodec.FramedMessage(6, REQ)),
                "容量 1:上一条尚未被取走时第二条即协议违规信号");
    }

    @Test
    void blockedTakeIsWokenByEnd() throws Exception {
        CodexRunnerMain.RunnerState state = new CodexRunnerMain.RunnerState();
        AtomicReference<Object> taken = new AtomicReference<>("unset");
        CountDownLatch started = new CountDownLatch(1);
        Thread executor = Thread.ofVirtual().name("test-executor").start(() -> {
            started.countDown();
            taken.set(state.takeCommand());
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        Thread.sleep(50); // 让执行线程先阻塞在 take 上
        state.end();
        executor.join(5_000);
        assertNull(taken.get(), "读线程终结必须唤醒阻塞中的执行线程(runner 不得挂死)");
    }
}
