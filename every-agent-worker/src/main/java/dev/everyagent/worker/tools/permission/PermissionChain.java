package dev.everyagent.worker.tools.permission;

import java.util.List;

/**
 * 权限责任链容器(架构 §5.5.1):顺序执行节点,首个 ALLOW/DENY 即短路返回;
 * 全部节点返回 SKIP 时整体返回 SKIP(调用方按「无法判定」兜底处理)。
 */
public final class PermissionChain {

    private final List<PermissionCheck> checks;

    public PermissionChain(List<PermissionCheck> checks) {
        this.checks = List.copyOf(checks == null ? List.of() : checks);
    }

    public PermissionDecision evaluate(PermissionContext ctx) {
        for (PermissionCheck c : checks) {
            PermissionDecision d = c.check(ctx);
            if (d.verdict() != PermissionDecision.Verdict.SKIP) {
                return d;
            }
        }
        return PermissionDecision.skip();
    }

    public List<PermissionCheck> checks() {
        return checks;
    }
}