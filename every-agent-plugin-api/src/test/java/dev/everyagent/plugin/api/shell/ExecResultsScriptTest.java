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
    void prefixCarriesOutStringWidthGuardDerivedFromConstant() {
        // Out-String:Width 免疫项(§7.10 PS-003「二次修正」):模型自加 | Out-String 时,内层
        // 那次调用若不带 -Width 会按无控制台的默认 120 列折行,外层收口拿到已折好的字符串、
        // 救不回来。故在 PREFIX 预置默认宽,使「加不加都完整」,描述无需再加禁令。
        String expect = "$PSDefaultParameterValues['Out-String:Width']="
                + ExecResults.OUT_STRING_WIDTH + ";";
        assertTrue(ExecResults.POWERSHELL_PREFIX.replace(" ", "").contains(expect.replace(" ", "")),
                "PREFIX 应含 Out-String:Width 免疫项且值由 OUT_STRING_WIDTH 派生(不留两处硬编码): "
                        + ExecResults.POWERSHELL_PREFIX);
    }

    /**
     * 端到端验证免疫<b>真的生效</b>——形态断言只能证明字符串里有这项,证不了 PowerShell 买账。
     *
     * <p>用真实 {@link ExecResults#buildPowerShellScript} 产物跑一条<b>模型会自加
     * {@code | Out-String}</b> 的命令(200 字符单属性),断言该行完整未被折成默认 120 列。
     * 本测试直接 spawn 进程取原始 stdout,不经工具层收口,故测的是脚本自身行为。
     */
    @Test
    void selfAddedOutStringNoLongerWrapsAt120() throws Exception {
        String shell = java.nio.file.Files.exists(
                java.nio.file.Path.of("C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe"))
                ? "powershell" : null;
        org.junit.jupiter.api.Assumptions.assumeTrue(shell != null, "非 Windows,跳过端到端验证");
        String cmd = "$__eaO = [pscustomobject]@{ A = ('X' * 200) }; $__eaO | Out-String";
        java.nio.file.Path script = java.nio.file.Files.createTempFile("ea-guard-", ".ps1");
        // 调用方负责 UTF-8 BOM(与生产一致)
        java.nio.file.Files.writeString(script,
                "\uFEFF" + ExecResults.buildPowerShellScript(cmd));
        Process p = new ProcessBuilder(shell, "-NoProfile", "-ExecutionPolicy", "Bypass",
                "-File", script.toString()).redirectErrorStream(true).start();
        String out;
        try (var in = p.getInputStream()) {
            out = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        p.waitFor();
        java.nio.file.Files.deleteIfExists(script);
        int maxLine = 0;
        for (String line : out.split("\r?\n")) {
            maxLine = Math.max(maxLine, line.length());
        }
        assertTrue(maxLine >= 200,
                "自加 | Out-String 的输出应完整一行(免疫项生效);实测 maxLine=" + maxLine
                        + ",若为 120 说明 guard 失效 → 参见 ARCHITECTURE §7.10 PS-003:" + out);
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
