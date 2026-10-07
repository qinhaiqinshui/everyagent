package dev.everyagent.plugin.api.shell;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ShellTool} 基线描述的实测校准回归护栏（2026-12）。
 *
 * <p>基线文案里的每一条都应是<b>沙箱内实测过的行为</b>，而不是从别处抄来的经验断言。历史上
 * 出过两类问题，本测试各立一条防线：
 * <ul>
 *   <li><b>版本/形态断言失准</b>：写死「Windows PowerShell 5.1 会丢弃空字符串参数」，实测本机是
 *       pwsh 7.6，且 {@code -File} 形态下 5.1 与 7.6 <b>均完整传递空串</b>——丢弃只发生在已删除的
 *       {@code -Command} 内联形态。该条已整条移除，不得回归。</li>
 *   <li><b>推荐了解法反而破坏框架收口</b>：旧文案建议「显式 {@code | Out-String} 才可靠读对象」，
 *       而 {@link ExecResults#buildPowerShellScript} 早已把整条命令包成
 *       {@code & { … } | Out-String -Width 4096}；模型自加 {@code Out-String} 会让<b>内层</b>按
 *       默认 120 列折行，外层救不回来（实测同对象：裸输出 200 字符完整一行，自加后 120×6 行）。
 *       现文案改为「不要再自己加」。</li>
 * </ul>
 */
class ShellToolBaselineTest {

    /** 占位执行器：本测试只检查描述文本，不执行命令。 */
    private static final ShellExecutor NOOP = (command, shell) -> "";

    private static String powershellDesc() {
        return ShellTool.powershell(NOOP).callback().getToolDefinition().description();
    }

    private static String bashDesc() {
        return ShellTool.bash(NOOP).callback().getToolDefinition().description();
    }

    /** ⑤ 空字符串参数：已证实是「-Command 形态 + 版本」两个条件混为一谈，整条删除不得回归。 */
    @Test
    void removedStaleVersionAndEmptyArgClaims() {
        String desc = powershellDesc();
        assertFalse(desc.contains("空字符串参数"), "旧「避免空字符串参数」条不得回归(-File 实测可传)");
        assertFalse(desc.contains("建议显式转字符串"), "旧「显式 Out-String 才可靠」推荐必须移除");
        // 连接符一条仍要在(它是真约束),但不再逐个枚举版本报错细节
        assertTrue(desc.contains("多条命令请用 ; 分隔"), "连接符约束保留");
        assertFalse(desc.contains("会直接语法报错"), "不再枚举各版本报错细节");
    }

    /** ⑥ 对象输出：按实测重写,核心是「框架已收口,别自己再加」。 */
    @Test
    void objectOutputGuidanceMatchesFrameworkWrap() {
        String desc = powershellDesc();
        assertTrue(desc.contains("Out-String -Width 4096"), "写明框架收口方式");
        assertTrue(desc.contains("不要再自己加 | Out-String"), "给出正解:不加才完整");
        assertTrue(desc.contains("120"), "写明自加后的真实后果(默认 120 列)");
        assertTrue(desc.contains("Format-List *") && desc.contains("ConvertTo-Json"),
                "要完整字段的两条正解");
    }

    /** 多值歧义：解法是 -join(实测 "s=" + $array 会按 $OFS 空格挤成一行),非 -ExpandProperty。 */
    @Test
    void multiValueAmbiguityPointsToJoin() {
        String desc = powershellDesc();
        assertTrue(desc.contains("-join"), "多值分隔正解");
        assertTrue(desc.contains("$OFS"), "说明歧义来源");
    }

    /**
     * stdin 与搜索无路径惯例：两基线共用一份且<b>各只出现一次</b>；bash 基线历史上完全缺这条。
     */
    @Test
    void stdinSearchNoteSharedByBothBaselinesExactlyOnce() {
        String needle = "搜索请始终显式给出路径";
        assertEquals(1, countOf(powershellDesc(), needle), "powershell 基线唯一一份");
        assertEquals(1, countOf(bashDesc(), needle), "bash 基线同样必须含这条");
        assertTrue(bashDesc().contains("grep") && bashDesc().contains("findstr"),
                "同类搜索命令一并覆盖,不只 rg");
        assertFalse(powershellDesc().contains("null 设备"), "旧措辞不得回归");
        assertFalse(bashDesc().contains("null 设备"), "旧措辞不得回归");
    }

    private static int countOf(String hay, String needle) {
        int n = 0;
        for (int i = hay.indexOf(needle); i >= 0; i = hay.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }
}
