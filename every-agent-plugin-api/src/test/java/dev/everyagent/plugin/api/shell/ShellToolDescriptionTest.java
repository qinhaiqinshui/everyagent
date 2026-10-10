package dev.everyagent.plugin.api.shell;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link ShellTool} 描述契约护栏（2026-12 用户决策，描述精简第三批：<b>核心零默认</b>，
 * 描述权全部移交工具提供者）。原 {@code ShellToolBaselineTest} 随基线删除重写为本测试。
 *
 * <p>契约三条，缺一即会静默走偏：
 * <ul>
 *   <li><b>描述必传</b>——null/空串运行期拒绝。核心不代写任何默认文案，否则「提供者忘了写
 *       描述」会退化成四后端共用一套与各自事实脱节的模板（rg 可用性、实际 shell 等谎报
 *       正是这么来的，见 ARCHITECTURE §7.10）；</li>
 *   <li><b>传入即所得</b>——描述原样生效，核心不隐式拼接（第一/二/三批删除的条目因此
 *       不可能经核心回流，提供者侧护栏见各后端自己的测试）；</li>
 *   <li><b>覆盖/追加语义保留</b>——{@code description()} 全量覆盖、
 *       {@code appendDescription()} 追加，供提供者组装条件化文本（如 rg 提示）。</li>
 * </ul>
 *
 * <p>描述该写什么、删过什么的完整决策链（stdin 语义、{@code -join}/{@code $OFS}、
 * 非 ASCII 已解码等条目的删除沿革与残余风险）见 ARCHITECTURE §7.10，不在本测试重复。
 */
class ShellToolDescriptionTest {

    /** 占位执行器：本测试只检查描述契约，不执行命令。 */
    private static final ShellExecutor NOOP = (command, shell) -> "";

    private static String descOf(ShellTool tool) {
        return tool.callback().getToolDefinition().description();
    }

    /** 描述必传：null/空串拒绝——核心零默认，绝不静默补一份模板。 */
    @Test
    void blankDescriptionIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> ShellTool.powershell(null, NOOP),
                "powershell 描述不得为 null");
        assertThrows(IllegalArgumentException.class, () -> ShellTool.powershell("  ", NOOP),
                "powershell 描述不得为空白");
        assertThrows(IllegalArgumentException.class, () -> ShellTool.bash(null, NOOP),
                "bash 描述不得为 null");
        assertThrows(IllegalArgumentException.class, () -> ShellTool.bash("", NOOP),
                "bash 描述不得为空串");
    }

    /** 传入即所得：描述原样生效，核心不隐式拼接任何基线/备注。 */
    @Test
    void providerDescriptionFlowsThroughVerbatim() {
        String ps = "在系统上用 PowerShell 执行真实 OS 命令;命令工作目录默认为任务工作区根;...";
        assertEquals(ps, descOf(ShellTool.powershell(ps, NOOP)), "powershell 原样生效");
        String sh = "在系统上用 bash 执行真实 OS 命令;...";
        assertEquals(sh, descOf(ShellTool.bash(sh, NOOP)), "bash 原样生效");
    }

    /** 覆盖/追加语义保留：提供者组装条件化文本（如 rg 提示）所依赖。 */
    @Test
    void overrideAndAppendStillWork() {
        String base = "用途;工作目录;";
        assertEquals("用途;工作目录;rg 提示;",
                descOf(ShellTool.powershell(base, NOOP).appendDescription("rg 提示;")),
                "追加到尾部");
        assertEquals("全量覆盖;",
                descOf(ShellTool.powershell(base, NOOP).description("全量覆盖;")),
                "全量覆盖");
    }
}
