package dev.everyagent.plugin.api.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link ExecResults#buildPowerShellScript} 的脚本形态测试（PS-003 顶层对象输出断流
 * 修复的回归护栏：四行结构、用户命令原样独占一行、Out-String 收口、exit 尾部在末尾）。
 */
class ExecResultsScriptTest {

    @Test
    void fourLineShapePrefixBlockCommandOutStringTail() {
        String script = ExecResults.buildPowerShellScript("Get-Location");
        String[] lines = script.split("\n", -1);
        assertEquals(4, lines.length, "四行:prefix / & { / 用户命令 / } | Out-String + 尾部");
        assertEquals(ExecResults.POWERSHELL_PREFIX.trim(), lines[0].trim(), "第 1 行=前缀");
        assertEquals("& {", lines[1], "第 2 行=脚本块开");
        assertEquals("Get-Location", lines[2], "第 3 行=用户命令原样独占一行");
        assertTrue(lines[3].startsWith("} | Out-String -Width " + ExecResults.OUT_STRING_WIDTH),
                "第 4 行=Out-String 收口: " + lines[3]);
        assertTrue(lines[3].endsWith(ExecResults.POWERSHELL_EXIT_TAIL),
                "第 4 行同含退出码尾部");
    }

    @Test
    void userCommandEmbeddedVerbatim() {
        String cmd = "rg \"a b\" -n .; $x = 1; # trailing comment";
        String script = ExecResults.buildPowerShellScript(cmd);
        // 引号/分号/$/注释一律原样,不转写不拆分;末尾注释由行边界保护(} 不被吞)
        assertTrue(script.contains("\n" + cmd + "\n"), "用户命令原样独占一行: " + script);
    }

    @Test
    void multiLineUserCommandKeptIntact() {
        String cmd = "if ($true) {\n  Write-Output 'hi'\n}";
        String script = ExecResults.buildPowerShellScript(cmd);
        assertTrue(script.contains("\n" + cmd + "\n"), "多行命令整体嵌入不拆分");
    }
}
