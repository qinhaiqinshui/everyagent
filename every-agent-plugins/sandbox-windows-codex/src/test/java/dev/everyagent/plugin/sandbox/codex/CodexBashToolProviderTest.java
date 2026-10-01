package dev.everyagent.plugin.sandbox.codex;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.ai.tool.ToolCallback;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CodexBashToolProvider}：appliesTo 只认 codex 后端；createTools 产出
 * powershell 工具（@Tool 注解形态，对照 wsl-ubuntu 惯例）。
 */
class CodexBashToolProviderTest {

    @TempDir
    Path tempDir;

    private CodexBashToolProvider provider() {
        return new CodexBashToolProvider(new CodexSandboxManager(
                new CodexSandboxOptions(tempDir, null, null, null, false, null), 30_000));
    }

    @Test
    void appliesOnlyWhenActiveBackendIsCodex() {
        assertTrue(provider().appliesTo(TestFixtures.ctx("codex", tempDir, null)),
                "当前后端 id==codex → 提供");
        assertFalse(provider().appliesTo(TestFixtures.ctx("wsl-ubuntu", tempDir, null)));
        assertFalse(provider().appliesTo(TestFixtures.ctx("windows-mic", tempDir, null)));
        assertFalse(provider().appliesTo(TestFixtures.ctx(null, tempDir, null)),
                "无后端(沙箱关闭/direct)不提供");
    }

    @Test
    void pluginIdMatchesPluginJson() {
        assertEquals("sandbox-windows-codex", provider().pluginId());
    }

    @Test
    void createsSinglePowerShellTool() {
        List<ToolCallback> tools = provider().createTools(TestFixtures.ctx("codex", tempDir, null));
        assertEquals(1, tools.size());
        assertEquals("powershell", tools.get(0).getToolDefinition().name());
        assertTrue(tools.get(0).getToolDefinition()
                .description().contains("codex 沙箱"), "描述注明 codex 隔离语境");
    }
}
