package dev.everyagent.worker.tools.permission;

/**
 * 权限责任链节点的一次判定结果(架构 §5.5.1 责任链):
 * <ul>
 *   <li>{@code ALLOW}:同意放行,链路立即终止;</li>
 *   <li>{@code DENY}:拒绝,链路立即终止;
 *       {@link #denial()} 非空时由调用方抛出该异常回灌模型;
 *       {@link #denial()} 为空表示「静默拒收」(如过度宽泛授权根:不弹窗、不报错,
 *       交由沙箱越界拦截兜底,与旧 PermissionGate 的 log.warn + return 语义一致);</li>
 *   <li>{@code SKIP}:本节点无法处理,交链上下一个节点继续;</li>
 * </ul>
 * 全链 SKIP 时由 {@link PermissionChain} 返回 SKIP。
 */
public final class PermissionDecision {

    public enum Verdict { ALLOW, DENY, SKIP }

    private final Verdict verdict;
    private final RuntimeException denial;
    private final String reason;

    private PermissionDecision(Verdict verdict, RuntimeException denial, String reason) {
        this.verdict = verdict;
        this.denial = denial;
        this.reason = reason == null ? "" : reason;
    }

    public static PermissionDecision allow() {
        return new PermissionDecision(Verdict.ALLOW, null, null);
    }

    public static PermissionDecision allow(String reason) {
        return new PermissionDecision(Verdict.ALLOW, null, reason);
    }

    /** 拒绝:携带要抛出的异常(调用方统一抛出)。 */
    public static PermissionDecision deny(RuntimeException denial) {
        return new PermissionDecision(Verdict.DENY, denial, null);
    }

    /** 拒绝:携带要抛出的异常与原因说明。 */
    public static PermissionDecision deny(RuntimeException denial, String reason) {
        return new PermissionDecision(Verdict.DENY, denial, reason);
    }

    /** 静默拒收:denial 为 null,调用方收到 DENY 且 denial==null 时直接返回不发异常。 */
    public static PermissionDecision denySilently(String reason) {
        return new PermissionDecision(Verdict.DENY, null, reason);
    }

    public static PermissionDecision skip() {
        return new PermissionDecision(Verdict.SKIP, null, null);
    }

    public static PermissionDecision skip(String reason) {
        return new PermissionDecision(Verdict.SKIP, null, reason);
    }

    public Verdict verdict() {
        return verdict;
    }

    public RuntimeException denial() {
        return denial;
    }

    public String reason() {
        return reason;
    }

    public boolean isAllow() {
        return verdict == Verdict.ALLOW;
    }

    public boolean isDeny() {
        return verdict == Verdict.DENY;
    }

    public boolean isSkip() {
        return verdict == Verdict.SKIP;
    }
}