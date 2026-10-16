package dev.everyagent.plugin.sandbox.wslubuntu;

import dev.everyagent.plugin.api.WorkerServices;
import dev.everyagent.plugin.api.agent.AgentContext;
import dev.everyagent.plugin.api.agent.AgentFactory;
import dev.everyagent.plugin.api.config.WorkerConfig;
import dev.everyagent.plugin.api.interaction.InteractionService;
import dev.everyagent.plugin.api.model.EventEmitter;
import dev.everyagent.plugin.api.model.ModelConfig;
import dev.everyagent.plugin.api.spi.SandboxBackend;
import dev.everyagent.plugin.api.spi.ToolContext;
import dev.everyagent.plugin.api.spi.WorkspaceManager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

/**
 * {@link WslUbuntuBashToolProvider}：appliesTo 只认 wsl-ubuntu 后端；createTools 产出
 * bash 工具。
 *
 * <p>描述口径(§7.10 谎报红线)：rg 工具由独立插件 ripgrep-tool 提供(其 appliesTo 排除
 * wsl-ubuntu 后端——wsl 语境的内容搜索归本 bash 工具)，故本工具描述<b>不提 rg，也不作
 * 任何搜索工具建议</b>：发行版内是否预装 rg 宿主侧无从静态判定，用什么命令搜索由模型
 * 自行决定。
 *
 * <p>约束(§14.9)：插件对 worker 零依赖——ToolContext/后端桩在本类内自建。
 */
class WslUbuntuBashToolProviderTest {

    @TempDir
    Path pluginDir;

    @TempDir
    Path workspaceRoot;

    // ---- 夹具 ----

    /** 最小装配：描述生成不读任何配置（rg 判据已随 rg 剥离一并删除）。 */
    private WslUbuntuBashToolProvider provider() {
        WorkerConfig props = mock(WorkerConfig.class, RETURNS_DEEP_STUBS);
        return new WslUbuntuBashToolProvider(props, null, pluginDir, mock(WorkerServices.class));
    }

    private String description(WslUbuntuBashToolProvider provider) {
        return provider.createTools(ctx("wsl-ubuntu")).get(0).getToolDefinition().description();
    }

    // ---- appliesTo / pluginId / 工具形态 ----

    @Test
    void appliesOnlyWhenActiveBackendIsWslUbuntu() {
        WslUbuntuBashToolProvider p = provider();
        assertTrue(p.appliesTo(ctx("wsl-ubuntu")), "当前后端 id==wsl-ubuntu → 提供");
        assertFalse(p.appliesTo(ctx("codex")));
        assertFalse(p.appliesTo(ctx("windows-mic")));
        assertFalse(p.appliesTo(ctx(null)), "无后端(DIRECT/沙箱关闭)不提供");
    }

    @Test
    void pluginIdMatchesPluginJson() {
        assertEquals("sandbox-wsl-ubuntu", provider().pluginId());
    }

    @Test
    void createsSingleBashTool() {
        List<ToolCallback> tools = provider().createTools(ctx("wsl-ubuntu"));
        assertEquals(1, tools.size());
        assertEquals("bash", tools.get(0).getToolDefinition().name());
    }

    // ---- 描述：不提 rg、不作搜索工具建议（§7.10 谎报红线）----

    /**
     * rg 工具由独立插件 ripgrep-tool 提供（appliesTo 排除本后端）；发行版内是否预装 rg
     * 宿主侧无从静态判定——描述既不提 rg，也不建议 grep 等任何搜索命令，用哪个由模型自行决定。
     */
    @Test
    void descriptionNeverMentionsSearchTools() {
        String desc = description(provider());
        assertTrue(desc.contains("在系统上用 bash 执行真实 OS 命令"), "用途句在场");
        assertTrue(desc.contains("命令工作目录默认为任务工作区根"), "工作目录句在场");
        assertFalse(desc.contains("rg"), "描述不提 rg(独立插件提供,本后端不再介入)");
        assertFalse(desc.contains("grep"), "不作搜索工具建议(是否在位无从静态判定)");
    }

    // ---- 桩 ----

    /** 最小 ToolContext（只提供 sandbox/workspaceRoot/subjectId；§14.9 不引用 worker 类型）。 */
    record FakeToolContext(String taskId, String agentId, Path workspaceRootPath,
            SandboxBackend sandbox) implements ToolContext {

        @Override
        public WorkspaceManager workspaces() {
            return null;
        }

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
            return "defaultworkspace";
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
            return workspaceRootPath;
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

    private ToolContext ctx(String sandboxId) {
        return new FakeToolContext("t1", "main", workspaceRoot,
                sandboxId == null ? null : new FakeBackend(sandboxId));
    }
}
