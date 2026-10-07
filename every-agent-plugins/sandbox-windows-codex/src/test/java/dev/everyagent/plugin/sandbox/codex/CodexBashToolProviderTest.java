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
        assertFalse(desc.contains("rg 二进制不可用"), "rg 可用时不得混入「不可用」提示");
    }

    /** rg 三档全未命中:描述严禁宣称「rg 已加入 PATH」——否则命令不存在会被误读成无匹配。 */
    @Test
    void rgUnavailableIsReportedTruthfully() {
        String desc = provider(new CodexRg.Rg(null, false))
                .createTools(TestFixtures.ctx("codex", tempDir, null))
                .get(0).getToolDefinition().description();
        assertFalse(desc.contains("rg 已加入 PATH"), "rg 不可用不得谎报已加入 PATH");
        assertTrue(desc.contains("rg 二进制不可用"), "如实声明 rg 不可用");
    }

    /**
     * stdin 语义与「搜索命令无路径→读空 stdin→空结果与无匹配同形」这条惯例，必须由
     * {@link ShellTool} 基线承担且<b>只出现一次</b>：后端各自追加正是历史上「stdin 为 null
     * 设备」「静默过滤 null stdin」两处不准确措辞的来源（rg 是把 stdin 当搜索源，不是
     * "过滤 null stdin"；且同类 findstr/grep 一样中招，只提 rg 会漏）。
     */
    @Test
    void stdinSearchConventionComesFromBaselineExactlyOnce() {
        String desc = provider(new CodexRg.Rg(tempDir.resolve("rg.exe"), true))
                .createTools(TestFixtures.ctx("codex", tempDir, null))
                .get(0).getToolDefinition().description();
        assertTrue(desc.contains("stdin 无输入可用"), "stdin 语义按效果口径(不绑定某一实现手段)");
        assertTrue(desc.contains("空输出 + exit 1"), "讲清后果(与无匹配同形)");
        assertTrue(desc.contains("搜索请始终显式给出路径"), "必须给正解,不只是警告");
        assertTrue(desc.contains("grep") && desc.contains("findstr"), "覆盖同类搜索命令,不只 rg");
        assertEquals(1, countOf(desc, "搜索请始终显式给出路径"), "该惯例只声明一次(基线唯一来源)");
        assertEquals(1, countOf(desc, "stdin 无输入可用"), "stdin 语义只声明一次");
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
