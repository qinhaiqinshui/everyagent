package dev.everyagent.worker.proto;

import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 短 ID 生成(仿 n 仓方案):{前缀}_{2 位盐}{base36 序号},如 t_k3f、a_k3x、sub_m2q。
 * 盐每次启动随机(跨重启去相关),序号每前缀独立自增;base36 小写保证频道/文件名安全
 * (hub 频道字符集 [a-z0-9._-])。taskId 全局唯一性由调用方对 tasks/diskTasks 查重兜底。
 */
public final class ShortIds {

    private static final String DIGITS = "0123456789abcdefghijklmnopqrstuvwxyz";
    private static final String SALT = newSalt();
    private static final Map<String, AtomicLong> COUNTERS = new ConcurrentHashMap<>();

    private ShortIds() {
    }

    public static String taskId() {
        return next("t");
    }

    public static String mainAgentId() {
        return next("a");
    }

    public static String subAgentId() {
        return next("sub");
    }

    public static String askId() {
        return next("q");
    }

    public static String cfgId() {
        return next("cfg");
    }

    public static String mid() {
        return next("m");
    }

    public static String next(String prefix) {
        long n = COUNTERS.computeIfAbsent(prefix, p -> new AtomicLong()).incrementAndGet();
        return prefix + "_" + SALT + base36(n);
    }

    private static String base36(long n) {
        if (n <= 0) {
            return "0";
        }
        StringBuilder sb = new StringBuilder(8);
        while (n > 0) {
            sb.append(DIGITS.charAt((int) (n % 36)));
            n /= 36;
        }
        return sb.reverse().toString();
    }

    private static String newSalt() {
        SecureRandom r = new SecureRandom();
        char[] c = new char[2];
        for (int i = 0; i < 2; i++) {
            c[i] = DIGITS.charAt(r.nextInt(36));
        }
        return new String(c);
    }
}
