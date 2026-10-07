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
        assertTrue(desc.contains("已在 PATH"), "rg 可用 → 描述声明 rg 可用");
        assertFalse(desc.contains("rg 不可用"), "rg 可用时不得混入「不可用」提示");
    }

    /** rg 三档全未命中:描述严禁宣称 rg 可用——否则命令不存在会被误读成无匹配。 */
    @Test
    void rgUnavailableIsReportedTruthfully() {
        String desc = provider(new CodexRg.Rg(null, false))
                .createTools(TestFixtures.ctx("codex", tempDir, null))
                .get(0).getToolDefinition().description();
        assertFalse(desc.contains("已在 PATH"), "rg 不可用不得谎报可用");
        assertTrue(desc.contains("rg 不可用"), "如实声明 rg 不可用");
    }

    /**
     * 2026-12 精简后：搜索无路径惯例、连接符版本细则、{@code Out-String} 框架收口提示等已从
     * {@link ShellTool} 基线移除（理由与残余风险见 ARCHITECTURE §7.10），后端追加层<b>同样不得
     * 把它们加回来</b>——否则已删内容会以「各后端自行补」的形式复活，正是当初要上收基线以避免的
     * 分散重复；恢复提示的前提是先读 §7.10 的处置优先级，不是在此新增。
     */
    @Test
    void backendDoesNotReAddPrunedBaselineClaims() {
        String desc = provider(new CodexRg.Rg(tempDir.resolve("rg.exe"), true))
                .createTools(TestFixtures.ctx("codex", tempDir, null))
                .get(0).getToolDefinition().description();
        assertTrue(desc.contains("stdin 无输入可用"), "保留项:stdin 语义仍由基线提供");
        assertEquals(1, countOf(desc, "stdin 无输入可用"), "stdin 语义只声明一次");
        assertFalse(desc.contains("搜索请始终显式给出路径"), "已删条目不得由后端加回");
        assertFalse(desc.contains("当搜索源"), "已删条目不得由后端加回");
        assertFalse(desc.contains("Out-String"), "已删条目不得由后端加回");
        assertFalse(desc.contains("null 设备"), "旧措辞不得回归");
        assertFalse(desc.contains("静默过滤 null stdin"), "旧措辞不得回归");
    }

    /** needle 在 hay 中出现次数。 */
    private static int countOf(String hay, String needle) {
        int n = 0;
        for (int i = hay.indexOf(needle); i >= 0; i = hay.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
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
