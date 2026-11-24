package dev.everyagent.worker.os;

import dev.everyagent.plugin.api.spi.ExecResult;
import dev.everyagent.worker.config.WorkerProperties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 超时中止的进程树级联终止回归（工作流测试报告 BUG-07/BUG-03）。
 *
 * <p>旧实现只 {@code destroyForcibly()} 直接子进程（cmd.exe），孙进程（powershell 等）
 * 继续运行到自然结束并在实例终态后写盘——「已超时/已取消」仍产生副作用；且中止后
 * 固定等满 drain 5s×2，表现为 {@code durationMs ≈ timeoutMs + 10000}。
 *
 * <p>断言（报告附录 B）：①超时返回及时（≤ timeout + 2500ms）；②孙进程不存活——
 * 探针文件（sleep 后自写）在远超 timeout、小于 sleep 的等待窗口后不得出现。
 */
class OsSandboxKillTreeTest {

    @TempDir
    Path dir;

    @Test
    void timeoutKillsProcessTreeAndReturnsPromptly() throws Exception {
        OsSandbox sandbox = new OsSandbox(new WorkerProperties(), null);
        boolean win = System.getProperty("os.name").toLowerCase().contains("win");
        Path probe = dir.resolve("leak-probe.txt");
        Files.deleteIfExists(probe);
        // 孙进程 8 秒后自写探针文件；timeoutMs=1500 中止
        String[] argv = win
                ? new String[]{"cmd.exe", "/c", "powershell", "-NoProfile", "-ExecutionPolicy",
                        "Bypass", "-Command",
                        "Start-Sleep -Seconds 8; Set-Content -Path '" + probe + "' 'LEAKED'"}
                : new String[]{"/bin/sh", "-c", "sleep 8 && echo LEAKED > '" + probe + "'"};

        long t0 = System.currentTimeMillis();
        ExecResult r = sandbox.spawnNative(argv, dir, Map.of(), 1500);
        long cost = System.currentTimeMillis() - t0;

        assertTrue(r.aborted(), "超时应按 aborted=true 收口");
        assertTrue(r.stderr().contains("超时中止"), "stderr 标注超时,实际: " + r.stderr());
        // BUG-03：旧实现固定 +10s（destroy 后等满 drain 5s×2）
        assertTrue(cost < 4000, "中止应及时返回(≤~2.5s+余量),实际 " + cost + "ms");

        // BUG-07：孙进程必须随中止死亡——探针窗口(5s) > timeout(1.5s)、< sleep(8s)
        Thread.sleep(5000);
        assertFalse(Files.exists(probe), "孙进程不得在中止后写盘(进程树未级联终止)");
    }
}
