package dev.everyagent.worker.authreview;

/**
 * AI 安全审议的一次性结论(plan-unattended-ai-auth 步骤 5)。
 *
 * <p>三态判断:<ul>
 * <li>{@code ALLOW}:安全,自动按 RUN 档授权放行(步骤 6 PermissionGate 直接 record);</li>
 * <li>{@code DENY}:拒绝,fail-closed(模型返回非 JSON / 缺 decision / 超时/异常且 deny-on-error=true);</li>
 * <li>{@code ESCALATE}:不确定,不在此拒绝——落到下一拦截环节(步骤 6 PermissionGate 的
 *     无人值守/人工弹窗):无人值守开启则拒绝,未开则正常弹窗人工授权;仅审计 trace 区分 decision。</li>
 * </ul>
 *
 * <p>回退信号:{@link #fallback()}=true 表示<b>审议失败且 review-deny-on-error=false</b>,
 * 调用方(步骤 6 PermissionGate)应回退人工弹窗(askUser),绝不因此放行——这是给步骤 6 用的
 * 信号约定:审议组件不抛异常,统一以 {@code ReviewDecision.fallback() == true} 表达「请回退人工」;
 * 审议异常/超时的具体原因附在 {@link #reason()} 中(如 {@code timeout}/{@code error: ...}),审计 trace 照发。
 */
public record ReviewDecision(Verdict verdict, double confidence, String reason, boolean fallback) {

    /** 审议三态。 */
    public enum Verdict {
        ALLOW, DENY, ESCALATE
    }

    /** 正常审议结论(无回退标记)。 */
    public static ReviewDecision of(Verdict verdict, double confidence, String reason) {
        return new ReviewDecision(verdict, confidence, reason == null ? "" : reason, false);
    }

    /** fail-closed 拒绝:模型不可信/解析失败/超时/异常且 deny-on-error=true。 */
    public static ReviewDecision deny(String reason) {
        return new ReviewDecision(Verdict.DENY, 0.0, reason == null ? "" : reason, false);
    }

    /** 审议失败且 deny-on-error=false:回退人工弹窗标记(步骤 6 据此调 askUser)。 */
    public static ReviewDecision fallback(String reason) {
        return new ReviewDecision(Verdict.DENY, 0.0, reason == null ? "" : reason, true);
    }

    /** 审计 scope 语义:ALLOW = "run"(按 RUN 档授权);DENY/ESCALATE = "deny"。 */
    public String scope() {
        return verdict == Verdict.ALLOW ? "run" : "deny";
    }
}