package dev.everyagent.plugin.api.shell;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ShellTool} 基线描述的精简护栏（2026-12 用户决策）。
 *
 * <p>决策原则：<b>描述只留「框架私有」与「本沙箱特有」两类事实，通用 shell 语法与工具常识
 * 不写</b>——模型自身已知的内容不该占用每次调用的上下文。据此从 powershell 基线删除四条
 * （搜索命令无路径读空 stdin、{@code ;}/{@code &&} 连接符版本细则、cmdlet 自动表格化与默认裁列、
 * 框架 {@code Out-String -Width 4096} 收口），另加此前已删的「空字符串参数」一条。
 *
 * <p>本测试因此承担两个方向的守护，缺一即会静默走偏：
 * <ul>
 *   <li>{@link #keptFactsStillPresent()}——<b>该留的没被误删</b>：stdin 效果语义（两基线共用、
 *       各只一份）与 {@code -join}/{@code $OFS}（真歧义，模型易踩）。</li>
 *   <li>{@link #removedClaimsStayRemoved()}——<b>已删的不许加回来</b>。删除是有代价的：尤其
 *       {@code Out-String} 那条，框架 4096 收口是私有行为、模型无法推知，描述现在不再拦截
 *       「自加 {@code Out-String}」这个会把内层打到默认 120 列折行的动作。若将来因这类现象想恢复
 *       提示，必须先读 ARCHITECTURE §7.10（PS-003「二次修正」给出正解优先级：在框架侧做到
 *       对自加不敏感，而不是往描述里加提示），本测试的失败信息也应指向该处。</li>
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

    /** 该留的两项仍在，且 stdin 语义两基线共用一份、不重复。 */
    @Test
    void keptFactsStillPresent() {
        String ps = powershellDesc();
        assertTrue(ps.contains("stdin 无输入可用"), "stdin 效果语义保留(按效果口径,不绑定实现手段)");
        assertTrue(ps.contains("-join"), "多值分隔正解保留");
        assertTrue(ps.contains("$OFS"), "说明歧义来源(实测字符串拼数组被空格挤成一行)");
        assertEquals(1, countOf(ps, "stdin 无输入可用"), "powershell 基线只一份");
        assertEquals(1, countOf(bashDesc(), "stdin 无输入可用"), "bash 基线同样共用这一份");
        assertFalse(bashDesc().contains("-join"),
                "-join/$OFS 是 PowerShell 特有歧义,bash 基线不该带上");
    }

    /** 已按决策删除的条目不许回归；旧的不准确措辞同样不许回归。 */
    @Test
    void removedClaimsStayRemoved() {
        String ps = powershellDesc();
        // 搜索命令无路径惯例(属工具常识)
        assertFalse(ps.contains("搜索请始终显式给出路径"), "已删:搜索须显式给路径");
        assertFalse(ps.contains("当搜索源"), "已删:无路径改读 stdin 的机制说明");
        // 连接符版本细则(与后端「实际执行 shell=<exe>」重复)
        assertFalse(ps.contains("多条命令请用 ; 分隔"), "已删:连接符条");
        assertFalse(ps.contains("&&"), "已删:连接符版本差异");
        // cmdlet 自动表格化与裁列
        assertFalse(ps.contains("只显示常用列"), "已删:默认裁列说明");
        assertFalse(ps.contains("Format-List"), "已删:Format-List 正解");
        assertFalse(ps.contains("ConvertTo-Json"), "已删:ConvertTo-Json 正解");
        // 框架 Out-String 收口(残余风险已记 §7.10 PS-003)
        assertFalse(ps.contains("Out-String"), "已删:框架收口与「不要自加」提示");
        assertFalse(ps.contains("120"), "已删:默认 120 列折行说明");
        // 更早一轮删除的条目
        assertFalse(ps.contains("空字符串参数"), "已删:-Command 形态空串条(-File 实测可传)");
        assertFalse(ps.contains("null 设备"), "旧措辞(把实现手段当契约)不得回归");
        assertFalse(ps.contains("静默过滤 null stdin"), "旧措辞(归因错误)不得回归");
    }

    /**
     * 长度上限：本次精简的直接目的就是压缩上下文。基线层超过 200 字说明有人又往里塞通用常识，
     * 应按「框架私有 / 沙箱特有」原则复核后再决定是否放行。
     */
    @Test
    void baselineStaysShort() {
        String ps = powershellDesc();
        assertTrue(ps.length() <= 200,
                "powershell 基线描述应 ≤200 字,当前 " + ps.length() + " 字;新增前先核对 §7.10");
    }

    private static int countOf(String hay, String needle) {
        int n = 0;
        for (int i = hay.indexOf(needle); i >= 0; i = hay.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }
}
