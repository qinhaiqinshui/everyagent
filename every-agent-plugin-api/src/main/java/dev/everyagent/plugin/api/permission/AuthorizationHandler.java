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

    record AuthorizationDecision(Type type, String reason) {
        public enum Type { ALLOW, DENY, PASS }
    }
}
