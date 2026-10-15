package dev.everyagent.plugin.ripgrepTool;

import dev.everyagent.plugin.api.agent.AgentContext;
import dev.everyagent.plugin.api.agent.AgentFactory;
import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.plugin.api.interaction.InteractionService;
import dev.everyagent.plugin.api.model.EventEmitter;
import dev.everyagent.plugin.api.model.ModelConfig;
import dev.everyagent.plugin.api.spi.CommandGate;
import dev.everyagent.plugin.api.spi.SandboxBackend;
import dev.everyagent.plugin.api.spi.ToolContext;
import dev.everyagent.plugin.api.spi.ToolOutcomeLog;
import dev.everyagent.plugin.api.spi.WorkspaceManager;

import java.nio.file.Path;
import java.util.Map;

/**
 * ripgrep-tool 测试共用的最小 fake（ToolContext + 假后端），形态对照
 * sandbox-windows-codex 的 {@code TestFixtures}。commandGate / toolOutcomes 可按用例
 * 注入（默认走接口缺省：放行门禁 / 未接入账本）。
 */
final class ToolContexts {

    private ToolContexts() {
    }

    /**
     * 最小 ToolContext（implements ExecContext）：record 字段持工具创建侧所需槽位，
     * 其余 ExecContext 槽位以最小假值实现（subjectId 固定 "t1"、workspaceRoot 取
     * workspaceRootPath，其余返回 null / 空集合）。
     */
    record FakeToolContext(String agentId, Path workspaceRootPath, SandboxBackend sandbox,
            CommandGate gate, ToolOutcomeLog outcomes) implements ToolContext {

        @Override
        public String subjectId() {
            return "t1";
        }

        @Override
        public String workspaceRoot() {
            return workspaceRootPath != null ? workspaceRootPath.toString() : null;
        }

        @Override
        public String workspaceId() {
            return "ws1";
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

        @Override
        public WorkspaceManager workspaces() {
            return null;
        }

        @Override
        public CommandGate commandGate() {
            return gate != null ? gate : ToolContext.super.commandGate();
        }

        @Override
        public ToolOutcomeLog toolOutcomes() {
            return outcomes != null ? outcomes : ToolContext.super.toolOutcomes();
        }
    }

    /** 只有 id 的假后端（appliesTo 判定用）。 */
    record FakeBackend(String id) implements SandboxBackend {
    }

    /** sandboxId 为 null 表示「无沙箱槽位」的极端形态；gate/outcomes 缺省。 */
    static ToolContext ctx(String sandboxId, Path workspaceRoot) {
        return new FakeToolContext("main", workspaceRoot,
                sandboxId == null ? null : new FakeBackend(sandboxId), null, null);
    }
}
