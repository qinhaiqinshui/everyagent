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
        return provider(new CodexRg.Rg(null, false));
    }

    private CodexBashToolProvider provider(CodexRg.Rg rg) {
        return new CodexBashToolProvider(new CodexSandboxManager(
                new CodexSandboxOptions(tempDir, null, null, null, false, null), 30_000), rg);
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
        List<ToolCallback> tools = provider(new CodexRg.Rg(tempDir.resolve("rg.exe"), true))
                .createTools(TestFixtures.ctx("codex", tempDir, null));
        assertEquals(1, tools.size());
        assertEquals("powershell", tools.get(0).getToolDefinition().name());
        String desc = tools.get(0).getToolDefinition().description();
        assertTrue(desc.contains("rg 已加入 PATH"), "rg 可用 → 描述声明 rg 可用");
        assertFalse(desc.contains("Select-String"), "rg 可用时不得混入「不可用」提示");
    }

    /** rg 三档全未命中:描述严禁宣称「rg 已加入 PATH」——否则命令不存在会被误读成无匹配。 */
    @Test
    void rgUnavailableIsReportedTruthfully() {
        String desc = provider(new CodexRg.Rg(null, false))
                .createTools(TestFixtures.ctx("codex", tempDir, null))
                .get(0).getToolDefinition().description();
        assertFalse(desc.contains("rg 已加入 PATH"), "rg 不可用不得谎报已加入 PATH");
        assertTrue(desc.contains("Select-String"), "如实给出替代搜索手段");
    }

    /** 描述报出探测到的 shell 可执行名:版本相关语法(&& / ||)能否用交给模型判断。 */
    @Test
    void descriptionReportsActualShellBinary() {
        String desc = provider(new CodexRg.Rg(null, true))
                .createTools(TestFixtures.ctx("codex", tempDir, null))
                .get(0).getToolDefinition().description();
        assertTrue(desc.contains("实际执行 shell=" + CodexCommandExecutor.detectShell().exe),
                "描述含实际 shell 名");
    }
}
