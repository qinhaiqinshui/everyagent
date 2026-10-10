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
     * 用真实 {@link ExecResults#buildPowerShellScript} 产物跑一条 PowerShell 命令,返回其
     * <b>原始 stdout+stderr</b>。
     *
     * <p>为什么要端到端:本类其余断言只看<b>脚本字符串形态</b>,形态对不等于引擎买账
     * ——PS-003 与 {@code Out-String} 宽度都是 PowerShell 运行期行为,只有真 spawn 才拦得住
     * 回归。脚本按生产口径带 UTF-8 BOM,stdout/stderr 合并(与 DIRECT 文件承载同形:重定向
     * 正是 PS-003 的触发条件之一)。非 Windows 自动跳过。
     */
    private static String runRealScript(String userCommand) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                java.nio.file.Files.exists(java.nio.file.Path.of(
                        "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe")),
                "非 Windows,跳过端到端验证");
        java.nio.file.Path script = java.nio.file.Files.createTempFile("ea-script-", ".ps1");
        try {
            // 调用方负责 UTF-8 BOM(与生产一致)
            java.nio.file.Files.writeString(script,
                    "\uFEFF" + ExecResults.buildPowerShellScript(userCommand));
            Process p = new ProcessBuilder("powershell", "-NoProfile", "-ExecutionPolicy",
                    "Bypass", "-File", script.toString()).redirectErrorStream(true).start();
            String out;
            try (var in = p.getInputStream()) {
                out = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            p.waitFor();
            return out;
        } finally {
            java.nio.file.Files.deleteIfExists(script);
        }
    }

    /**
     * 端到端验证 {@code Out-String:Width} 免疫<b>真的生效</b>:跑一条模型会自加
     * {@code | Out-String} 的命令(200 字符单属性),断言该行完整、未被内层默认 120 列折断。
     */
    @Test
    void selfAddedOutStringNoLongerWrapsAt120() throws Exception {
        String out = runRealScript("$__eaO = [pscustomobject]@{ A = ('X' * 200) }; $__eaO | Out-String");
        int maxLine = 0;
        for (String line : out.split("\r?\n")) {
            maxLine = Math.max(maxLine, line.length());
        }
        assertTrue(maxLine >= 200,
                "自加 | Out-String 的输出应完整一行(免疫项生效);实测 maxLine=" + maxLine
                        + ",若为 120 说明 guard 失效 → 参见 ARCHITECTURE §7.10 PS-003:" + out);
    }

    /**
     * 端到端验证 PS-003 修复<b>真的生效</b>(§7.10「顶层对象输出断流修复」)。
     *
     * <p>命令刻意含一个<b>顶层裸对象语句</b>({@code Get-Location},PathInfo 触发格式化引擎)
     * 加上其<b>后</b>的一条 {@code Write-Output}——修复前的形态(prefix + 裸命令 + exit 尾部)
     * 在这两者上都会静默丢字节:实测旧形态 powershell.exe 与 pwsh.exe 均 rc=0、stdout 仅 2
     * 字节、stderr 空、无 CLIXML,表现就是模型看到的「空输出」。这也是本条比形态断言更值钱的
     * 地方:<b>它把「输出被吞」和「命令确无输出」这两种同形结果区分开的能力,只能靠真跑证明</b>。
     *
     * <p>只断言当前形态正确(该契约永不该红);<b>不</b>断言旧形态必错——那是 PowerShell 版本
     * 相关的引擎行为,将来若上游修好,断言它会变成假失败。旧形态不可回退的理由记在注释与 §7.10。
     */
    @Test
    void topBareObjectOutputSurvivesFileCarriage() throws Exception {
        String out = runRealScript(
                "Get-Location; Write-Output 'TAIL_AFTER_BARE_OBJECT'");
        assertTrue(out.contains("TAIL_AFTER_BARE_OBJECT"),
                "裸对象之后的 PS 渲染输出不得断流(PS-003 回归) → §7.10:" + out);
        assertTrue(out.contains("Path"),
                "裸对象(PathInfo 表格)自身输出不得消失 → §7.10:" + out);
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
