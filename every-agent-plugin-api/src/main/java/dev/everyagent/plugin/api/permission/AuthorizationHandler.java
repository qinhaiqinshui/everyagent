package dev.everyagent.plugin.api.permission;

import dev.everyagent.plugin.api.execution.ExecContext;

/**
 * 授权决议链节点 SPI（filter 形态，与任务洋葱 §3.1 同一范式）。
 * <ul>
 *   <li>不处理 = {@code return next.proceed(req)}（等价原 applies()=false 或 decide()=PASS）；</li>
 *   <li>决议短路：return ALLOW/DENY（等价原 decide 短路）；</li>
 *   <li>上行段（决议后包裹）= 审计/升级/宽限期——本范式新增的能力。</li>
 * </ul>
 */
public interface AuthorizationHandler {

    String id();

    /** 链上位置：升序 = 执行序。float 允许任意插位。 */
    float order();

    /**
     * @param req 授权请求
     * @param next 链的下一环
     * @return 授权决议（ALLOW/DENY=短路；PASS=继续）
     */
    AuthorizationDecision invoke(AuthorizationRequest req, AuthorizationChain next) throws Exception;

    /**
     * 授权请求：执行上下文 + 两个授权专属参数。
     *
     * <p>主体数据面已全部由 {@link ExecContext} 槽位携带（subjectId = 授权状态分区键 /
     * metadata = 主体策略标记 / dataDir = grants.json 落盘 / emitter = 审计 trace /
     * agentFactory / interaction = 绑定主体的交互口），授权请求只剩
     * {@code grantKey}（授权状态分区键，如 {@code p::write::<realpath>}）与
     * {@code prompt}（授权请求原文：人工弹窗/AI 审议的输入）。
     */
    record AuthorizationRequest(ExecContext context, String agentId,
                                String grantKey, String prompt) {}

    /**
     * 授权决议。
     *
     * <p>{@code scope} 仅对 {@code ALLOW} 有意义:节点自动放行(如 AI 审议、无人值守联动、
     * 第三方白名单)一律用默认 {@link Scope#RUN}(本轮运行内),不得替用户做「本任务全程」
     * 的持久化承诺;只有真正问过用户的节点(人工弹窗)才能返回 {@link Scope#TASK}。
     *
     * @param type   ALLOW=放行短路;DENY=拒绝短路;PASS=不处理,交下一环
     * @param reason 人类可读结论(审计/回灌文案)
     * @param scope  放行档位(仅 ALLOW 有意义)
     */
    record AuthorizationDecision(Type type, String reason, Scope scope) {

        public enum Type { ALLOW, DENY, PASS }

        /** 放行档位:RUN=本轮运行内(内存,下一条用户输入即清);TASK=本任务全程(落盘 grants.json)。 */
        public enum Scope { RUN, TASK }

        /** 默认档位 RUN 的便利构造(PASS/DENY 与自动放行节点用)。 */
        public AuthorizationDecision(Type type, String reason) {
            this(type, reason, Scope.RUN);
        }
    }
}
