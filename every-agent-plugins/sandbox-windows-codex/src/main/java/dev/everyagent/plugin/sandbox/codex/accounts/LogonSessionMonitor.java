package dev.everyagent.plugin.sandbox.codex.accounts;

import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;

/**
 * 沙箱登录会话周期审计（会话泄漏可观测性）：默认每 30 分钟记一条 INFO——
 * <b>本机登录会话总数</b>（主指标，LsaEnumerateLogonSessions 计数，无需特权、准确）
 * + 沙箱账户明细（参考口径，非管理员运行时受 LSA 脱敏限制，见
 * {@link LogonSessions} 权限口径）；总数超阈值（默认 64）打 WARN 附处置提示
 * （重启 worker 可回收——池化 runner 的登录会话随 worker 进程消亡被系统回收）。
 *
 * <p>可测性切分：数据源经 {@code totalCounter} 注入（生产 =
 * {@link LogonSessions#totalSessionCount()}，单测给 fake）；阈值判定与文案全部走
 * {@link LogonSessions} 的纯函数。调度极薄（守护虚拟线程 + sleep 循环，
 * 随 JVM 退出消亡，可 interrupt）。
 */
public final class LogonSessionMonitor {

    private final String prefix;
    private final int threshold;
    private final System.Logger log;
    private final IntSupplier totalCounter;
    private final java.util.function.Function<List<String>, Map<String, Integer>> accountCounter;

    public LogonSessionMonitor(String prefix, int threshold) {
        this(prefix, threshold, System.getLogger(LogonSessionMonitor.class.getName()),
                LogonSessions::totalSessionCount, LogonSessions::countsByUsers);
    }

    LogonSessionMonitor(String prefix, int threshold, System.Logger log,
            IntSupplier totalCounter) {
        this(prefix, threshold, log, totalCounter, LogonSessions::countsByUsers);
    }

    LogonSessionMonitor(String prefix, int threshold, System.Logger log,
            IntSupplier totalCounter,
            java.util.function.Function<List<String>, Map<String, Integer>> accountCounter) {
        this.prefix = prefix;
        this.threshold = threshold;
        this.log = log;
        this.totalCounter = totalCounter;
        this.accountCounter = accountCounter;
    }

    /**
     * 一轮审计：总数枚举 → INFO（总数 + 账户明细 best-effort）→ 超阈值 WARN。
     * 返回观测到的会话总数；枚举失败返回 -1（打 WARNING，不中断周期）。
     */
    public int auditOnce() {
        int total;
        try {
            total = totalCounter.getAsInt();
        } catch (RuntimeException e) {
            log.log(System.Logger.Level.WARNING, "登录会话枚举失败(本轮跳过): {0}",
                    e.toString());
            return -1;
        }
        Map<String, Integer> accountCounts = null;
        try {
            accountCounts = accountCounter.apply(List.of(
                    SandboxAccounts.offlineUsername(prefix),
                    SandboxAccounts.onlineUsername(prefix)));
        } catch (RuntimeException e) {
            log.log(System.Logger.Level.WARNING, "登录会话账户明细枚举失败(仅跳过明细): {0}",
                    e.toString());
        }
        log.log(System.Logger.Level.INFO, "{0}", LogonSessions.infoLine(total, accountCounts));
        if (LogonSessions.overThreshold(total, threshold)) {
            log.log(System.Logger.Level.WARNING, "{0}",
                    LogonSessions.warnLine(total, threshold));
        }
        return total;
    }

    /** 启动周期审计守护线程（intervalMs&lt;=0 返回 null = 审计关闭）。 */
    public Thread startDaemon(long intervalMs) {
        if (intervalMs <= 0) {
            return null;
        }
        return Thread.ofVirtual().name("codex-logon-session-audit").start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    auditOnce();
                } catch (RuntimeException e) {
                    log.log(System.Logger.Level.WARNING, "登录会话审计轮异常: {0}",
                            e.toString());
                }
                try {
                    Thread.sleep(intervalMs);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
    }
}
