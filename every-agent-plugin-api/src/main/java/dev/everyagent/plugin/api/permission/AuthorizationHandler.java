package dev.everyagent.plugin.api.permission;

/**
 * 授权决议链节点 SPI。
 *
 * 核心只遍历 handler 列表（按 order 排序），不感知任何具体节点。
 * 每个节点自行判断 applies，返回 ALLOW/DENY/PASS。
 * 零节点或全部 PASS → 直接放行。
 */
public interface AuthorizationHandler {

    int order();

    boolean applies(AuthorizationRequest req);

    AuthorizationDecision decide(AuthorizationRequest req);

    record AuthorizationRequest(
            TaskInfo task, String agentId, String grantKey, String prompt) {}

    record AuthorizationDecision(Type type, String reason) {
        public enum Type { ALLOW, DENY, PASS }
    }
}
