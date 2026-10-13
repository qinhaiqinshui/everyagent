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
        assertFalse(log.consumeToolThrow("fs.read_file", "{}"), "查不到即视为成功");
    }

    @Test
    void 抛异常记账按工具名加入参查回() {
        ToolOutcomeLog log = new ToolOutcomeLog();
        log.recordToolThrow("fs.read_file", "{\"path\":\"C:/x\"}");

        assertTrue(log.consumeToolThrow("fs.read_file", "{\"path\":\"C:/x\"}"));
        assertFalse(log.consumeToolThrow("fs.read_file", "{\"path\":\"C:/x\"}"), "消费后不再命中");
        assertFalse(log.consumeToolThrow("fs.read_file", "{\"path\":\"D:/y\"}"));
    }

    @Test
    void 记账入参为空不抛异常() {
        ToolOutcomeLog log = new ToolOutcomeLog();
        log.recordShellExit(null, "cmd", 1, false);
        log.recordShellExit("bash", null, 1, false);
        log.recordToolThrow(null, "{}");

        assertNull(log.consumeShellExit(null, "cmd"));
        // 入参为 null 的抛异常记账按空串归一,查回时同样归一
        assertFalse(log.consumeToolThrow("bash", null));
    }
}