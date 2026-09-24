package dev.everyagent.plugin.api.permission;

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

    record AuthorizationRequest(
            TaskInfo task, String agentId, String grantKey, String prompt) {}

    record AuthorizationDecision(Type type, String reason) {
        public enum Type { ALLOW, DENY, PASS }
    }
}
