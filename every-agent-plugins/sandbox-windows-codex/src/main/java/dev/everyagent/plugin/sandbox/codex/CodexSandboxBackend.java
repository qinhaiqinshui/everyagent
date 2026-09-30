package dev.everyagent.plugin.sandbox.codex;

import dev.everyagent.plugin.api.spi.SandboxBackend;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * codex 沙箱后端（设计文档 §5）。
 *
 * <p>codex 模式命令跑在<b>宿主路径</b>上（无路径重写），{@link #mount} 返回
 * <b>恒等映射</b>（同 windows-mic，复用 {@code SandboxBackend.super.mount} 默认实现），
 * 同时把每个 {@link MountRequest} 登记进 {@link CodexSandboxManager}：
 * READ_WRITE → 写根（cap SID + preflight 刷 ACE），READ_ONLY → 读根补充——
 * 供 {@link CodexCommandExecutor} 组装会话与 SpawnRequest。
 *
 * <p>{@link #onWorkspaceRemoved} 刻意 no-op：账户/ACL/防火墙是<b>持久</b>供给，
 * 由 setup marker 与 deny-read state 文件对账，不随工作区删除回滚；过期根在
 * preflight（{@code ProvisioningAcl} 缺失根幂等跳过）与 cap SID 选择
 * （per-root 键不再进新令牌）两侧天然失效，无需在此清理。
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
    public Map<Path, String> mount(List<MountRequest> requests) {
        for (MountRequest req : requests) {
            manager.register(req.hostPath(), req.access());
        }
        // 恒等映射：codex 命令跑在宿主路径上，路径翻译由核心 SandboxPathRegistry 兜底
        return SandboxBackend.super.mount(requests);
    }

    // onWorkspaceRemoved() 使用默认 no-op 实现（见类 Javadoc：ACL 持久供给不回收）
}
