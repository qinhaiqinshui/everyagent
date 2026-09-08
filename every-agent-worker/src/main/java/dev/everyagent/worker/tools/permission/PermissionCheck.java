package dev.everyagent.worker.tools.permission;

/**
 * 权限责任链节点(架构 §5.5.1):一个节点只负责一个判定,与「一个 Advisor 只负责一个
 * 功能」同精神。链上顺序执行,任一节点返回 ALLOW/DENY 即短路处理完成;
 * 返回 SKIP 表示本节点无法处理,继续交给下一个节点。新增授权规则 = 新增一个
 * Check 节点插到链的合适位置,不改 PermissionGate 主体。
 */
@FunctionalInterface
public interface PermissionCheck {

    PermissionDecision check(PermissionContext ctx);
}