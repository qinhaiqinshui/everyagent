package dev.everyagent.worker.tools.permission;

/**
 * 权限责任链节点(架构 §5.5.1):一个节点只负责一个判定,与「一个 Advisor 只负责一个
 * 功能」同精神。filter 形态:节点自行决定是短路(ALLOW/DENY)还是委托后续节点
 * (SKIP = 调用 {@code next.proceed(ctx)} 继续链)。新增授权规则 = 新增一个
 * Check 节点插到链的合适位置,不改 PermissionGate 主体。
 */
@FunctionalInterface
public interface PermissionCheck {

    /**
     * filter 形态判定:ALLOW/DENY = 短路;SKIP = {@code return next.proceed(ctx)} 委托后续节点。
     */
    PermissionDecision invoke(PermissionContext ctx, PermissionChain next);
}

