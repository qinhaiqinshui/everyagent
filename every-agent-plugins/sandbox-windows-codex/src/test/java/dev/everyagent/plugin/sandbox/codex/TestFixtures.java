package dev.everyagent.plugin.sandbox.codex;

import dev.everyagent.plugin.api.agent.AgentContext;
import dev.everyagent.plugin.api.agent.AgentFactory;
import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.plugin.api.interaction.InteractionService;
import dev.everyagent.plugin.api.model.EventEmitter;
import dev.everyagent.plugin.api.model.ModelConfig;
import dev.everyagent.plugin.api.spi.SandboxBackend;
import dev.everyagent.plugin.api.spi.ToolContext;
import dev.everyagent.plugin.api.spi.WorkspaceManager;

import java.nio.file.Path;
import java.util.Map;

/**
 * SPI 适配层测试共用的最小 fake（ToolContext + 假后端）。
 */
final class TestFixtures {

    private TestFixtures() {
    }

    /**
     * 最小 ToolContext（implements ExecContext）：record 字段仅持工具创建侧所需信息
     * （workspaceRootPath 与接口的 {@code String workspaceRoot()} 槽位不同名），
     * ExecContext 槽位以最小假值实现（subjectId=taskId、workspaceRoot=workspaceRootPath?.toString()，
     * 其余返回 null / 空集合）。
     */
    record FakeToolContext(String taskId, String agentId, Path workspaceRootPath,
            SandboxBackend sandbox, WorkspaceManager workspaces, Path rgBinary)
            implements ToolContext {

        @Override
        public String subjectId() {
            return taskId;
        }

        @Override
        public String workspaceRoot() {
            return workspaceRootPath != null ? workspaceRootPath.toString() : null;
        }

        @Override
        public String workspaceId() {
            return null;
        }

        @Override
        public ModelConfig snapshot() {
            return null;
        }

        @Override
        public EventEmitter emitter() {
            return null;
        }

        @Override
        public AgentFactory agentFactory() {
            return null;
        }

        @Override
        public Map<String, Object> metadata() {
            return Map.of();
        }

        @Override
        public Path dataDir() {
            return null;
        }

        @Override
        public boolean terminal() {
            return false;
        }

        @Override
        public InteractionService interaction() {
            return null;
        }

        @Override
        public Map<String, AgentContext> agents() {
            return Map.of();
        }
    }

    /** 只有 id 的假后端（appliesTo 判定用）。 */
    record FakeBackend(String id) implements SandboxBackend {
    }

    static ToolContext ctx(String sandboxId, Path workspaceRoot, Path rgBinary) {
        return new FakeToolContext("t1", "main", workspaceRoot,
                sandboxId == null ? null : new FakeBackend(sandboxId), null, rgBinary);
    }
}
