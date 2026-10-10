package dev.everyagent.worker.tools.permission;

/**
 * 权限责任链(架构 §5.5.1):filter 形态的链接口。节点通过 {@link #proceed} 委托
 * 后续节点;链尾兜底返回 SKIP(调用方按「无法判定」兜底处理)。
 *
 * <p>容器实现见 {@link PermissionChainImpl}:以链折叠构造,首个 ALLOW/DENY 即短路返回。
 */
@FunctionalInterface
public interface PermissionChain {

    /** 委托后续节点;链尾返回 {@link PermissionDecision#skip()}。 */
    PermissionDecision proceed(PermissionContext ctx);
}

