package dev.everyagent.worker.modules;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.everyagent.worker.os.pty.ShellWhitelist;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * term.open 审计日志单测(安全整改 M-7):[term.open] 审计行须包含
 * 谁(ownerKey 前 8 位)/ termId / shell / realpath / cwd,且全量 ownerKey 不入日志。
 * 经 logback ListAppender 捕获 TerminalService 类 logger 断言,无需真拉 PTY。
 */
class TerminalAuditLogTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(TerminalService.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void setUp() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    @Test
    void auditOpenLogsOwnerTermIdShellRealpathCwd() {
        TerminalService.auditOpen("abcdefgh12345678", "t-1",
                new ShellWhitelist.Checked("C:\\Windows\\System32\\cmd.exe",
                        "C:\\Windows\\System32\\cmd.exe"),
                Path.of("C:\\ws\\proj"));

        List<ILoggingEvent> events = appender.list;
        assertEquals(1, events.size());
        String msg = events.get(0).getFormattedMessage();
        assertTrue(msg.startsWith("[term.open] "), "审计行以 [term.open] 前缀: " + msg);
        assertTrue(msg.contains("owner=abcdefgh"), "ownerKey 取前 8 位: " + msg);
        assertFalse(msg.contains("abcdefgh12345678"), "全量 ownerKey 不得入审计日志: " + msg);
        assertTrue(msg.contains("termId=t-1"), msg);
        assertTrue(msg.contains("shell=C:\\Windows\\System32\\cmd.exe"), msg);
        assertTrue(msg.contains("realpath=C:\\Windows\\System32\\cmd.exe"), msg);
        assertTrue(msg.contains("cwd=C:\\ws\\proj"), msg);
    }

    @Test
    void auditOpenAnnotatesNameFormAndDefaultShell() {
        // 名称形态:realpath 标注 PATH 解析(不预解析)
        TerminalService.auditOpen("k12345678", "t-2",
                new ShellWhitelist.Checked("powershell", null), Path.of("/ws"));
        String msg = appender.list.get(0).getFormattedMessage();
        assertTrue(msg.contains("shell=powershell"), msg);
        assertTrue(msg.contains("realpath=(PATH解析)"), msg);

        // 未传 shell = 平台默认
        TerminalService.auditOpen("k12345678", "t-3", null, Path.of("/ws"));
        String msg2 = appender.list.get(1).getFormattedMessage();
        assertTrue(msg2.contains("shell=(平台默认)"), msg2);
        assertTrue(msg2.contains("realpath=(平台默认)"), msg2);
    }

    @Test
    void ownerPrefixTruncatesTo8Chars() {
        assertEquals("12345678", TerminalService.ownerPrefix("1234567890"));
        assertEquals("short", TerminalService.ownerPrefix("short"));
        assertEquals("-", TerminalService.ownerPrefix(null));
    }
}
