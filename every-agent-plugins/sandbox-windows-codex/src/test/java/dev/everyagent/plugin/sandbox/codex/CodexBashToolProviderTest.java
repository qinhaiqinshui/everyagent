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
        assertTrue(provider().appliesTo(TestFixtures.ctx("codex", tempDir)),
                "当前后端 id==codex → 提供");
        assertFalse(provider().appliesTo(TestFixtures.ctx("wsl-ubuntu", tempDir)));
        assertFalse(provider().appliesTo(TestFixtures.ctx("windows-mic", tempDir)));
        assertFalse(provider().appliesTo(TestFixtures.ctx(null, tempDir)),
                "无后端(沙箱关闭/direct)不提供");
    }

    @Test
    void pluginIdMatchesPluginJson() {
        assertEquals("sandbox-windows-codex", provider().pluginId());
    }

    @Test
    void createsSinglePowerShellTool() {
        List<ToolCallback> tools = provider()
                .createTools(TestFixtures.ctx("codex", tempDir));
        assertEquals(1, tools.size());
        assertEquals("powershell", tools.get(0).getToolDefinition().name());
        String desc = tools.get(0).getToolDefinition().description();
        assertTrue(desc.contains("执行真实 OS 命令"), "用途句在场");
        assertTrue(desc.contains("命令工作目录默认为任务工作区根"), "工作目录事实保留");
    }

    /**
     * rg 归属已整体剥离（由独立插件 ripgrep-tool 提供）：本后端描述<b>不得出现任何
     * rg 字样</b>，也不得加 Select-String 建议——描述谎报红线：不宣称任何本后端管不了
     * 的搜索工具。曾经的「已在path」「rg 不可用」条件化子句随剥离一并删除。
     */
    @Test
    void descriptionDoesNotMentionRg() {
        String desc = provider()
                .createTools(TestFixtures.ctx("codex", tempDir))
                .get(0).getToolDefinition().description();
        assertFalse(desc.contains("rg"), "rg 工具由独立插件提供,后端描述不提 rg: " + desc);
        assertFalse(desc.contains("Select-String"), "不得建议本后端管不了的搜索工具");
        assertFalse(desc.contains("内容搜索"), "内容搜索引导句随 rg 剥离删除");
    }

    /**
     * 2026-12 第三批（描述权移交提供者）后：stdin 语义、{@code -join}/{@code $OFS}、
     * 「非 ASCII 已正确解码」等条目已从描述删除（理由与残余风险见 ARCHITECTURE §7.10），
     * 提供者<b>不得把它们加回来</b>——否则已删内容会以「各后端自行补」的形式复活；
     * 恢复提示的前提是先读 §7.10 的处置优先级，不是在此新增。用途/工作目录两句随基线
     * 删除改由本后端自写，同样在此守护。内容搜索/rg 子句已随 rg 归属剥离整体删除
     * （见 descriptionDoesNotMentionRg）。
     */
    @Test
    void backendDoesNotReAddPrunedBaselineClaims() {
        String desc = provider()
                .createTools(TestFixtures.ctx("codex", tempDir))
                .get(0).getToolDefinition().description();
        assertTrue(desc.startsWith("在系统上用 " + CodexCommandExecutor.shellDisplay() + " 执行真实 OS 命令;"),
                "首句含探测得到的 shell 名称+版本(第五批:cmd 兜底时如实报 cmd,不硬编码)");
        assertTrue(desc.contains("命令工作目录默认为任务工作区根;"), "工作目录事实保留");
        assertFalse(desc.contains("stdin 无输入可用"), "已删(第三批):stdin 语义");
        assertFalse(desc.contains("无法交互输入"), "已删(第三批):stdin 语义");
        assertFalse(desc.contains("-join"), "已删(第三批):-join/$OFS 条");
        assertFalse(desc.contains("$OFS"), "已删(第三批):-join/$OFS 条");
        assertFalse(desc.contains("非 ASCII"), "已删(第三批):编码已解码句");
        assertFalse(desc.contains("搜索请始终显式给出路径"), "搜索引导句已随 rg 剥离删除,不得回归");
        assertFalse(desc.contains("当搜索源"), "搜索引导句已随 rg 剥离删除,不得回归");
        assertFalse(desc.contains("提交须带"), "已退役(第四批):git 身份由执行器 env 注入,描述不再指引 -c");
        assertFalse(desc.contains("user.name"), "已退役(第四批):描述不再含 git 身份字样");
        assertFalse(desc.contains("实际执行 shell"), "已退役(第五批):shell 身份(名称+版本)并入首句");
        assertFalse(desc.contains("Out-String"), "已删条目不得由后端加回");
        assertFalse(desc.contains("null 设备"), "旧措辞不得回归");
        assertFalse(desc.contains("静默过滤 null stdin"), "旧措辞不得回归");
    }

    /** 描述首句报出探测得到的 shell 身份(名称+版本):版本相关语法(&& / ||)能否用交给模型判断;
     * 独立子句「实际执行 shell=<exe>」已随第五批退役。 */
    @Test
    void descriptionReportsShellIdentityInOpening() {
        String desc = provider()
                .createTools(TestFixtures.ctx("codex", tempDir))
                .get(0).getToolDefinition().description();
        assertTrue(desc.contains("在系统上用 " + CodexCommandExecutor.shellDisplay() + " 执行真实 OS 命令"),
                "首句含探测展示名(PowerShell <版本> 或 cmd)");
        assertFalse(desc.contains("实际执行 shell="), "独立 shell 子句已退役(第五批)");
    }
}
