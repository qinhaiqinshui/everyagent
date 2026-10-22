package dev.everyagent.plugin.sandbox.codex.accounts;

import com.sun.jna.Platform;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话泄漏可观测性：{@link LogonSessions} 纯逻辑（文案/阈值/合计）跨平台全测；
 * LSA 枚举本体仅 Windows 集成环境可验（冒烟：本机至少一个登录会话、
 * countsByUsers 的扫描匹配在当前 LSA 脱敏口径下仍能归因到调用者自身账户）。
 */
class LogonSessionsTest {

    @Test
    void totalSumsNonNegativeCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("EACodexOffline", 3);
        counts.put("EACodexOnline", 0);
        assertEquals(3, LogonSessions.total(counts));
        counts.put("EACodexOnline", 5);
        assertEquals(8, LogonSessions.total(counts));
    }

    @Test
    void thresholdIsStrictlyGreater() {
        assertFalse(LogonSessions.overThreshold(64, 64), "恰好等于阈值不告警");
        assertTrue(LogonSessions.overThreshold(65, 64));
        assertFalse(LogonSessions.overThreshold(0, 64));
    }

    @Test
    void infoLineLeadsWithTotalAndCarriesAccountDetail() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("EACodexOffline", 2);
        counts.put("EACodexOnline", 1);
        String line = LogonSessions.infoLine(153, counts);
        assertTrue(line.contains("总数=153"), "主指标必须是会话总数: " + line);
        assertTrue(line.contains("EACodexOffline=2"), line);
        assertTrue(line.contains("EACodexOnline=1"), line);
        assertTrue(line.contains("参考"), "明细必须标注口径: " + line);
        // 无明细时也可用（null 容忍）
        assertTrue(LogonSessions.infoLine(42, null).contains("总数=42"));
    }

    @Test
    void warnLineCarriesThresholdAndRemediationHint() {
        String line = LogonSessions.warnLine(1700, 64);
        assertTrue(line.contains("1700"), line);
        assertTrue(line.contains("64"), line);
        assertTrue(line.contains("重启 worker"), "处置提示必须给出回收手段: " + line);
        assertTrue(line.contains("codex.runner-pool-enabled"), "提示指向池化配置: " + line);
    }

    // ---- Windows 枚举冒烟（集成环境验证；非 Windows 跳过） ----

    @Test
    void totalSessionCountSeesAtLeastOneSession() {
        Assumptions.assumeTrue(Platform.isWindows(), "LSA 枚举仅 Windows");
        int total = LogonSessions.totalSessionCount();
        assertTrue(total >= 1, "本进程自身至少占据一个登录会话: " + total);
        assertTrue(total <= LogonSessions.MAX_SESSIONS, "计数受防御上限约束: " + total);
    }

    /**
     * 账户归因冒烟：无 SeTcbPrivilege 时 LSA 对所有 LUID 返回调用者自身口径——
     * 本机实测全部会话显示当前进程账户名,故「进程账户计数 ≥ 1」是该脱敏口径下的
     * 不变量（提权运行时明细可信,本断言仅验证扫描匹配路径工作）。
     * 账户口径用 GetUserNameW（进程令牌）：继承 env 的 USERNAME 在沙箱内指向宿主
     * 用户，与 LSA 归因账户不一致。
     */
    @Test
    void countsByUsersAttributesUnderSanitizedLsa() {
        Assumptions.assumeTrue(Platform.isWindows(), "LSA 枚举仅 Windows");
        String self;
        try {
            self = com.sun.jna.platform.win32.Advapi32Util.getUserName();
        } catch (RuntimeException e) {
            Assumptions.assumeTrue(false, "GetUserNameW 不可用: " + e);
            return;
        }
        Assumptions.assumeTrue(self != null && !self.isBlank(), "进程账户名不可用");
        Map<String, Integer> counts = LogonSessions.countsByUsers(List.of(self));
        assertEquals(1, counts.size());
        assertTrue(counts.get(self) >= 1, "进程账户(" + self + ")至少一个会话: " + counts);
        int total = LogonSessions.totalSessionCount();
        if (counts.get(self) != total) {
            // 提权/TCB 口径（明细可信）或部分会话不可读:不视为失败,打印供人工核对
            System.out.println("[audit-smoke] total=" + total + " self=" + counts
                    + " (非脱敏口径或部分不可读)");
        }
    }

    // ---- Monitor 判定逻辑（计数器注入 fake；跨平台） ----

    /** 记录日志级别的最小 logger 桩（MessageFormat 展开参数，对齐 System.Logger 语义）。 */
    private static final class RecordingLogger implements System.Logger {
        final List<String> messages = new java.util.ArrayList<>();

        @Override
        public String getName() {
            return "test";
        }

        @Override
        public boolean isLoggable(Level level) {
            return true;
        }

        @Override
        public void log(Level level, java.util.ResourceBundle bundle, String format,
                Object... params) {
            String msg = params == null || params.length == 0 ? format
                    : java.text.MessageFormat.format(format, params);
            messages.add(level + "|" + msg);
        }

        @Override
        public void log(Level level, java.util.ResourceBundle bundle, String msg,
                Throwable thrown) {
            messages.add(level + "|" + msg + "|" + thrown);
        }

        @Override
        public void log(Level level, String msg) {
            messages.add(level + "|" + msg);
        }
    }

    @Test
    void auditOnceLogsInfoBelowThresholdAndWarnAbove() {
        RecordingLogger log = new RecordingLogger();
        AtomicInteger value = new AtomicInteger(10);
        LogonSessionMonitor monitor = new LogonSessionMonitor("EACodex", 64, log,
                value::get, accounts -> {
                    Map<String, Integer> m = new LinkedHashMap<>();
                    m.put("EACodexOffline", 2);
                    m.put("EACodexOnline", 3);
                    return m;
                });
        assertEquals(10, monitor.auditOnce(), "返回观测总数");
        assertTrue(log.messages.stream().anyMatch(m -> m.startsWith("INFO|")
                && m.contains("总数=10") && m.contains("EACodexOffline=2")),
                "INFO 主指标=总数+明细: " + log.messages);
        assertFalse(log.messages.stream().anyMatch(m -> m.startsWith("WARNING|")),
                "阈值内不得 WARN: " + log.messages);

        log.messages.clear();
        value.set(65); // 65 > 64
        assertEquals(65, monitor.auditOnce());
        assertTrue(log.messages.stream().anyMatch(m -> m.startsWith("WARNING|")
                && m.contains("65") && m.contains("重启 worker")),
                "超阈值必须 WARN 且带处置提示: " + log.messages);
    }

    @Test
    void auditOnceTreatsEnumerationFailureAsSkippedRound() {
        RecordingLogger log = new RecordingLogger();
        LogonSessionMonitor monitor = new LogonSessionMonitor("EACodex", 64, log,
                () -> {
                    throw new IllegalStateException("lsa unavailable");
                },
                accounts -> new LinkedHashMap<>());
        assertEquals(-1, monitor.auditOnce(), "枚举失败返回 -1");
        assertTrue(log.messages.stream().noneMatch(m -> m.contains("总数=")),
                "失败轮不得输出带总数的打点: " + log.messages);
        assertTrue(log.messages.stream().anyMatch(m -> m.startsWith("WARNING|")
                && m.contains("枚举失败")), "失败必须留痕: " + log.messages);
    }

    @Test
    void startDaemonDisabledWhenIntervalNonPositive() {
        LogonSessionMonitor monitor = new LogonSessionMonitor("EACodex", 64);
        assertEquals(null, monitor.startDaemon(0), "interval<=0 = 审计关闭");
        assertEquals(null, monitor.startDaemon(-1));
    }
}
