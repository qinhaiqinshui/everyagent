package dev.everyagent.plugin.api.spi;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ToolOutcomeLog} 单测：它是 {@code tool.result} 事件 {@code data.status} 的唯一结构化
 * 判定源（前端据此把失败工具块标红）。这里钉死三件事——非零退出码 / 超时算失败、退出码 0 不算、
 * 同键重复调用按 FIFO 一一对应（不能错配到另一次调用）；以及查不到即「未记账」而非「失败」。
 */
class ToolOutcomeLogTest {

    @Test
    void 非零退出码与超时算失败() {
        assertTrue(new ToolOutcomeLog.ShellExit(3, false).failed());
        assertTrue(new ToolOutcomeLog.ShellExit(1, false).failed());
        assertTrue(new ToolOutcomeLog.ShellExit(-1, false).failed());
        assertTrue(new ToolOutcomeLog.ShellExit(0, true).failed(), "超时中止即便 rc=0 也算失败");
    }

    @Test
    void 退出码零不算失败() {
        assertFalse(new ToolOutcomeLog.ShellExit(0, false).failed());
    }

    @Test
    void 退出码记账按工具名加命令查回() {
        ToolOutcomeLog log = new ToolOutcomeLog();
        log.recordShellExit("powershell", "exit 3", 3, false);
        log.recordShellExit("powershell", "echo ok", 0, false);

        assertEquals(3, log.consumeShellExit("powershell", "exit 3").exitCode());
        assertEquals(0, log.consumeShellExit("powershell", "echo ok").exitCode());
        assertNull(log.consumeShellExit("powershell", "echo ok"), "消费后不得再命中(不跨轮复用)");
    }

    @Test
    void 同键重复调用按FIFO对应() {
        ToolOutcomeLog log = new ToolOutcomeLog();
        log.recordShellExit("bash", "make", 0, false);
        log.recordShellExit("bash", "make", 2, false);

        assertFalse(log.consumeShellExit("bash", "make").failed(), "第一次调用成功");
        assertTrue(log.consumeShellExit("bash", "make").failed(), "第二次调用失败");
    }

    @Test
    void 工具名不同或命令不同互不串号() {
        ToolOutcomeLog log = new ToolOutcomeLog();
        log.recordShellExit("powershell", "exit 3", 3, false);

        assertNull(log.consumeShellExit("bash", "exit 3"), "工具名不同不命中");
        assertNull(log.consumeShellExit("powershell", "exit 4"), "命令不同不命中");
        assertNotNull(log.consumeShellExit("powershell", "exit 3"));
    }

    @Test
    void 未记账返回未命中而非失败() {
        ToolOutcomeLog log = new ToolOutcomeLog();
        assertNull(log.consumeShellExit("powershell", "echo ok"));
        assertNull(log.consumeShellExit(null, "echo ok"));
        assertNull(log.consumeShellExit("powershell", null));
    }

    @Test
    void callId账按调用id查回() {
        ToolOutcomeLog log = new ToolOutcomeLog();
        log.recordCallOutcome("c1", true);
        log.recordCallOutcome("c2", false);

        assertEquals(Boolean.TRUE, log.callOutcome("c1"), "失败调用记 true");
        assertEquals(Boolean.FALSE, log.callOutcome("c2"), "成功调用记 false");
        assertNull(log.callOutcome("c3"), "无记账返回 null(非失败)");
        assertNull(log.callOutcome(null), "null callId 不记账不命中");
        // 非消费语义:后置消费者重复查同一 callId 结果一致
        assertEquals(Boolean.TRUE, log.callOutcome("c1"), "非消费查询可重复");
    }

    @Test
    void 记账入参为空不抛异常() {
        ToolOutcomeLog log = new ToolOutcomeLog();
        log.recordShellExit(null, "cmd", 1, false);
        log.recordShellExit("bash", null, 1, false);
        log.recordCallOutcome(null, true);

        assertNull(log.consumeShellExit(null, "cmd"));
        assertNull(log.callOutcome(null));
    }
}