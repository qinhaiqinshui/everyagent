package dev.everyagent.worker.tools.permission;

import java.util.List;

/**
 * 权限责任链容器(架构 §5.5.1):以链折叠构造,首个 ALLOW/DENY 即短路返回;
 * 全部节点返回 SKIP 时整体返回 SKIP(调用方按「无法判定」兜底处理)。
 *
 * <p>折叠方式:从链尾向链首反向构造,每个节点拿到后续节点的 {@link PermissionChain}
 * 引用;链尾兜底为 {@link PermissionDecision#skip()}。
 */
public final class PermissionChainImpl implements PermissionChain {

    private final List<PermissionCheck> checks;

    public PermissionChainImpl(List<PermissionCheck> checks) {
        this.checks = List.copyOf(checks == null ? List.of() : checks);
    }

    @Override
    public PermissionDecision proceed(PermissionContext ctx) {
        PermissionChain chain = c -> PermissionDecision.skip(); // 链尾兜底
        for (int i = checks.size() - 1; i >= 0; i--) {
            PermissionCheck node = checks.get(i);
            PermissionChain inner = chain;
            chain = c -> node.invoke(c, inner);
        }
        return chain.proceed(ctx);
    }

    public List<PermissionCheck> checks() {
        return checks;
    }
}
