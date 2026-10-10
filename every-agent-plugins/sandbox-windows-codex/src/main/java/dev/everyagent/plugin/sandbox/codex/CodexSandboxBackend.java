package dev.everyagent.plugin.sandbox.codex;

import dev.everyagent.plugin.api.spi.SandboxBackend;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * codex 沙箱后端（设计文档 §5）。
 *
 * <p>codex 模式命令跑在<b>宿主路径</b>上（无路径重写），故 {@link #toSandbox} /
 * {@link #toHost} 走默认<b>恒等</b>实现；效果侧只有一件事：把授权根登记进
 * {@link CodexSandboxManager}（READ_WRITE → 写根 = cap SID + preflight 刷 ACE；
 * READ_ONLY → 读根补充），供 {@link CodexCommandExecutor} 组装会话与 SpawnRequest。
 *
 * <p><b>回收</b>：{@link #revoke} 把根移出登记（并 prune 该根的 cap SID），
 * 使后续 SpawnRequest 不再包含它——记账由上层聚合决定，后端只按给定路径集合执行。
 * 磁盘上的历史 ACE 不即时撤销（{@link CodexSandboxManager} 的持久供给语义：
 * 陈旧 ACE 因 cap SID 不再进令牌而失效），此点与 codex 原生一致。
 */
public final class CodexSandboxBackend implements SandboxBackend {

    private final CodexSandboxManager manager;

    public CodexSandboxBackend(CodexSandboxManager manager) {
        this.manager = manager;
    }

    @Override
    public String id() {
        return "codex";
    }

    @Override
    public void grant(List<PathGrant> grants) {
        for (PathGrant g : grants) {
            if (g == null || g.hostPath() == null) {
                continue;
            }
            manager.register(g.hostPath(), g.access());
        }
    }

    @Override
    public void revoke(List<Path> hostPaths) {
        for (Path h : hostPaths == null ? List.<Path>of() : hostPaths) {
            if (h != null) {
                manager.unregister(h);
            }
        }
    }
}
