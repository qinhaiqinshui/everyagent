package dev.everyagent.plugin.sandbox.codex;

import dev.everyagent.plugin.api.spi.SandboxBackend;
import dev.everyagent.plugin.api.spi.ToolContext;
import dev.everyagent.plugin.api.spi.WorkspaceManager;

import java.nio.file.Path;

/**
 * SPI 适配层测试共用的最小 fake（ToolContext + 假后端）。
 */
final class TestFixtures {

    private TestFixtures() {
    }

    /** 最小 ToolContext（sandbox 可指定 id；workspaces/interaction 为 null）。 */
    record FakeToolContext(String taskId, String agentId, Path workspaceRoot,
            SandboxBackend sandbox, WorkspaceManager workspaces, Path rgBinary)
            implements ToolContext {
    }

    /** 只有 id 的假后端（appliesTo 判定用）。 */
    record FakeBackend(String id) implements SandboxBackend {
    }

    static ToolContext ctx(String sandboxId, Path workspaceRoot, Path rgBinary) {
        return new FakeToolContext("t1", "main", workspaceRoot,
                sandboxId == null ? null : new FakeBackend(sandboxId), null, rgBinary);
    }
}
