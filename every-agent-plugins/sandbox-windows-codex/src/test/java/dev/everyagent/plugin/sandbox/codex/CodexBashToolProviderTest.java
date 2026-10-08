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
        assertTrue(desc.contains("务必显式给出路径"), "§7.10 恢复项:无路径陷阱子句必须在场");
        assertFalse(desc.contains("rg 不可用"), "rg 可用时不得混入「不可用」提示");
    }

    /** rg 三档全未命中:描述严禁宣称 rg 可用——否则命令不存在会被误读成无匹配。 */
    @Test
    void rgUnavailableIsReportedTruthfully() {
        String desc = provider(new CodexRg.Rg(null, false))
                .createTools(TestFixtures.ctx("codex", tempDir, null))
                .get(0).getToolDefinition().description();
        assertFalse(desc.contains("已在 PATH"), "rg 不可用不得谎报可用");
        assertFalse(desc.contains("务必显式给出路径"), "rg 子句只随 rg 可用分支携带");
        assertTrue(desc.contains("rg 不可用"), "如实声明 rg 不可用");
    }

    /**
     * 2026-12 第三批（描述权移交提供者）后：stdin 语义、{@code -join}/{@code $OFS}、
     * 「非 ASCII 已正确解码」等条目已从描述删除（理由与残余风险见 ARCHITECTURE §7.10），
     * 提供者<b>不得把它们加回来</b>——否则已删内容会以「各后端自行补」的形式复活；
     * 恢复提示的前提是先读 §7.10 的处置优先级，不是在此新增。用途/工作目录两句随基线
     * 删除改由本后端自写，同样在此守护。唯一已恢复项：搜索显式路径子句（§7.10 恢复协议
     * 走完「核对→决策」后按用户决策恢复，由 createsSinglePowerShellTool 正向断言在场）；
     * 下两条断言防的是更早期的<b>整句形态</b>复活，恢复项用的是记录在案的紧凑措辞。
     */
    @Test
    void backendDoesNotReAddPrunedBaselineClaims() {
        String desc = provider(new CodexRg.Rg(tempDir.resolve("rg.exe"), true))
                .createTools(TestFixtures.ctx("codex", tempDir, null))
                .get(0).getToolDefinition().description();
        assertTrue(desc.startsWith("在系统上用 PowerShell 执行真实 OS 命令;"),
                "基线删除后,开头用途句由本后端自写");
        assertTrue(desc.contains("命令工作目录默认为任务工作区根;"), "工作目录事实保留");
        assertFalse(desc.contains("stdin 无输入可用"), "已删(第三批):stdin 语义");
        assertFalse(desc.contains("无法交互输入"), "已删(第三批):stdin 语义");
        assertFalse(desc.contains("-join"), "已删(第三批):-join/$OFS 条");
        assertFalse(desc.contains("$OFS"), "已删(第三批):-join/$OFS 条");
        assertFalse(desc.contains("非 ASCII"), "已删(第三批):编码已解码句");
        assertFalse(desc.contains("搜索请始终显式给出路径"), "早期整句形态不得回归(恢复项为 §7.10 紧凑措辞)");
        assertFalse(desc.contains("当搜索源"), "早期整句形态不得回归(恢复项为 §7.10 紧凑措辞)");
        assertFalse(desc.contains("Out-String"), "已删条目不得由后端加回");
        assertFalse(desc.contains("null 设备"), "旧措辞不得回归");
        assertFalse(desc.contains("静默过滤 null stdin"), "旧措辞不得回归");
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
