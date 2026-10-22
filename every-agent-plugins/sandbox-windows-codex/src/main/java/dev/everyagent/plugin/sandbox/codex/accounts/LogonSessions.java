package dev.everyagent.plugin.sandbox.codex.accounts;

import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

import dev.everyagent.plugin.sandbox.codex.win.Secur32Ex;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 登录会话枚举工具（LsaEnumerateLogonSessions / LsaGetLogonSessionData）——会话泄漏
 * 可观测性的数据源：沙箱 runner 由 CreateProcessWithLogonW 拉起、每进程一个登录会话，
 * runner 从不注销即泄漏（实测一天 1700+）。
 *
 * <p><b>权限口径（本机实测实证）</b>：
 * <ul>
 *   <li>{@link #totalSessionCount()}（枚举计数）<b>无需特权、准确</b>——这是泄漏监控
 *       的主指标：泄漏一天 1700+ 时总数必然爆表，天然盖过系统基线会话（几十个）；</li>
 *   <li>按账户归因（{@link #countsByUsers}）依赖 LsaGetLogonSessionData 的明细——
 *       无 SeTcbPrivilege（普通用户 worker 常态）时 LSA 对<b>所有</b> LUID 都返回
 *       <b>调用者自身</b>的会话数据（本机实测 153 个会话全部显示当前账户名），
 *       明细仅提权运行时可信，非提权口径下当作参考。</li>
 * </ul>
 *
 * <p><b>结构布局策略</b>：不依赖 SECURITY_LOGON_SESSION_DATA 的字段偏移（本机实测
 * 返回体 Size=272 与文档 144 不符、UserName 位于 0x10——LSA 返回的是自有扩展形态），
 * 而是按 8 字节步长扫描「UNICODE_STRING 形态」的槽位并解引用候选字符串，再与目标
 * 账户名匹配——对布局变化鲁棒，坏槽位一律跳过（best-effort）。
 */
public final class LogonSessions {

    /** 防御性枚举上限（异常态 LSA 返回天文数字时不至于转上天文次数）。 */
    static final int MAX_SESSIONS = 4096;
    /** 单会话扫描结构体的窗口上限（字符串都在结构体头部）。 */
    private static final int MAX_SCAN_BYTES = 160;
    /** 候选字符串长度上限（字节；账户名 ≤ 20 字符 = 40 字节，宽限到 512）。 */
    private static final int MAX_STRING_BYTES = 512;

    private LogonSessions() {
    }

    /** 本机登录会话总数（LsaEnumerateLogonSessions 计数；准确、无需特权）。 */
    public static int totalSessionCount() {
        if (!Platform.isWindows()) {
            throw new IllegalStateException("LsaEnumerateLogonSessions 仅 Windows");
        }
        IntByReference count = new IntByReference();
        PointerByReference list = new PointerByReference();
        if (Secur32Ex.INSTANCE.LsaEnumerateLogonSessions(count, list)
                != Secur32Ex.STATUS_SUCCESS) {
            throw new IllegalStateException("LsaEnumerateLogonSessions failed");
        }
        try {
            return Math.min(count.getValue(), MAX_SESSIONS);
        } finally {
            Secur32Ex.INSTANCE.LsaFreeReturnBuffer(list.getValue());
        }
    }

    /**
     * 按账户名统计登录会话数（best-effort：逐会话取明细并扫描匹配目标名）。
     * 非提权调用方受 LSA 脱敏限制——明细恒为调用者自身口径，仅提权运行时准确
     * （见类注释权限口径）。
     */
    public static Map<String, Integer> countsByUsers(List<String> usernames) {
        Map<String, Integer> out = new LinkedHashMap<>();
        Set<String> targets = new LinkedHashSet<>();
        for (String u : usernames) {
            out.put(u, 0);
            if (u != null && !u.isBlank()) {
                targets.add(u.toLowerCase(Locale.ROOT));
            }
        }
        if (targets.isEmpty() || !Platform.isWindows()) {
            return out;
        }
        IntByReference count = new IntByReference();
        PointerByReference list = new PointerByReference();
        if (Secur32Ex.INSTANCE.LsaEnumerateLogonSessions(count, list)
                != Secur32Ex.STATUS_SUCCESS) {
            throw new IllegalStateException("LsaEnumerateLogonSessions failed");
        }
        Pointer entries = list.getValue();
        try {
            int n = Math.min(count.getValue(), MAX_SESSIONS);
            for (int i = 0; i < n; i++) {
                // 列表是连续平铺的 LUID 结构数组(8 字节/项,MSDN 样例取 &sessions[i]),
                // 不是指针数组——按指针解引用会把 LUID 字节当指针传给 LSA,直接崩
                Set<String> strings = stringsOfSession(entries.share((long) i * 8L));
                Set<String> lower = new LinkedHashSet<>();
                for (String s : strings) {
                    lower.add(s.toLowerCase(Locale.ROOT));
                }
                for (Map.Entry<String, Integer> e : out.entrySet()) {
                    if (lower.contains(e.getKey().toLowerCase(Locale.ROOT))) {
                        e.setValue(e.getValue() + 1);
                    }
                }
            }
        } finally {
            Secur32Ex.INSTANCE.LsaFreeReturnBuffer(entries);
        }
        return out;
    }

    /** 取单个会话结构体里全部可读的 UNICODE_STRING 值（布局无关扫描；失败返回空集）。 */
    static Set<String> stringsOfSession(Pointer luid) {
        Set<String> out = new LinkedHashSet<>();
        PointerByReference dataRef = new PointerByReference();
        if (Secur32Ex.INSTANCE.LsaGetLogonSessionData(luid, dataRef)
                != Secur32Ex.STATUS_SUCCESS) {
            return out;
        }
        Pointer data = dataRef.getValue();
        if (data == null) {
            return out;
        }
        try {
            for (int off = 8; off + 16 <= MAX_SCAN_BYTES; off += 8) {
                int len = data.getShort(off) & 0xFFFF;
                int max = data.getShort(off + 2) & 0xFFFF;
                if (len <= 0 || len > MAX_STRING_BYTES || (len & 1) != 0 || max < len) {
                    continue; // 非 UNICODE_STRING 形态
                }
                Pointer buf = data.getPointer(off + 8);
                if (buf == null) {
                    continue;
                }
                try {
                    out.add(new String(buf.getCharArray(0, len / 2)));
                } catch (RuntimeException | Error ignored) {
                    // 坏指针/不可读:跳过该候选(JNA 原生访问违例以 Error 形态抛出)
                }
            }
        } catch (RuntimeException | Error ignored) {
            // 整体扫描失败:按空集处理(计数 best-effort)
        } finally {
            Secur32Ex.INSTANCE.LsaFreeReturnBuffer(data);
        }
        return out;
    }

    // ---- 纯逻辑（跨平台可单测；打点文案与阈值判定集中在此） ----

    /** 会话总数。 */
    public static int total(Map<String, Integer> counts) {
        int t = 0;
        for (int v : counts.values()) {
            t += Math.max(0, v);
        }
        return t;
    }

    /** 超阈值判定（严格大于：阈值=64 时 64 不告警、65 告警）。 */
    public static boolean overThreshold(int total, int threshold) {
        return total > threshold;
    }

    /** INFO 打点文案：会话总数（主指标）+ 逐账户明细（参考口径）。 */
    public static String infoLine(int totalSessions, Map<String, Integer> accountCounts) {
        StringBuilder sb = new StringBuilder("登录会话审计: 本机会话总数=" + totalSessions);
        if (accountCounts != null && !accountCounts.isEmpty()) {
            sb.append(" 沙箱账户明细(非管理员口径下为调用者归因,仅供参考):");
            for (Map.Entry<String, Integer> e : accountCounts.entrySet()) {
                sb.append(' ').append(e.getKey()).append('=').append(Math.max(0, e.getValue()));
            }
        }
        return sb.toString();
    }

    /** WARN 打点文案：泄漏判定 + 处置提示（重启 worker 回收池化 runner 的登录会话）。 */
    public static String warnLine(int total, int threshold) {
        return String.format(Locale.ROOT,
                "登录会话总数 %d 超过阈值 %d——疑似登录会话泄漏(每命令一 runner 未池化/池失效?)。"
                        + "处置:重启 worker 可回收全部沙箱 runner 登录会话;持续增长请检查"
                        + " codex.runner-pool-enabled 配置",
                total, threshold);
    }

    /** 兼容保留：按单用户名计数（见 countsByUsers 的权限口径说明）。 */
    public static int countByUser(String username) {
        return countsByUsers(List.of(username)).getOrDefault(username, 0);
    }
}
