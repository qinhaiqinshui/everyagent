package dev.everyagent.worker.os;

import dev.everyagent.plugin.api.spi.ExecResult;
import dev.everyagent.worker.config.WorkerProperties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OsSandbox#spawnNative(String[], Path, Map)} 的中断语义回归
 * (2026-11 修复,跨平台):任务取消 / 级联 stopAll 以 {@code cancel(true)} 打断
 * 正在执行的命令时,必须 ①恢复中断标记(供任务层按取消收口)②按
 * {@code aborted=true} + 「exec 被中断」收口——旧实现把 {@link InterruptedException}
 * 吞进 {@code catch (Exception)},伪装成 {@code rc=1} 的普通失败且丢中断标记,
 * 在日志里掩盖取消/挂起排查线索(worker.restart 瞬间的 {@code rc=1 aborted=false} 实锤)。
 */
class OsSandboxInterruptTest {

    @Test
    @Timeout(30)
    void interruptDuringWaitRestoresFlagAndReportsAborted() throws Exception {
        OsSandbox sandbox = new OsSandbox(new WorkerProperties(), null);
        boolean win = System.getProperty("os.name").toLowerCase().contains("win");
        // 跨平台长命令:Windows 用 ping(31s),POSIX 用 sleep
        String[] sleep = win
                ? new String[]{"ping", "-n", "31", "127.0.0.1"}
                : new String[]{"sleep", "31"};

        AtomicReference<ExecResult> result = new AtomicReference<>();
        Thread t = Thread.ofPlatform().start(() -> result.set(
                sandbox.spawnNative(sleep, Path.of("."), Map.of())));

        // 等子进程起来、执行线程阻塞在等待(out.get / waitFor)
        Thread.sleep(1500);
        t.interrupt();
        t.join(10_000);
        assertFalse(t.isAlive(), "中断后执行线程应退出");

        ExecResult r = result.get();
        assertNotNull(r, "中断路径必须返回 ExecResult(不抛出)");
        assertTrue(r.aborted(), "中断按 aborted=true 收口(而非 rc=1 普通失败),实际=" + r.aborted());
        assertEquals(-1, r.exitCode(), "中断退出码 -1");
        assertTrue(r.stderr().contains("exec 被中断"), "stderr 标注中断,实际: " + r.stderr());
        assertTrue(t.isInterrupted(), "中断标记必须恢复保留(供任务层按取消收口)");
    }
}
