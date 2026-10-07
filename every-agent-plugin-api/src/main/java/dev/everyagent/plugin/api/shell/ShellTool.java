package dev.everyagent.plugin.api.shell;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.function.FunctionToolCallback;

/**
 * 通用 shell 工具壳（name + {@link ShellExecutor}，编程式 {@link ToolCallback}）。
 *
 * <p>取代原四个壳类（worker {@code PowerShellTool} / {@code BashTool}、
 * codex {@code CodexPowerShellTool}、wsl-ubuntu {@code WslUbuntuBashTool}）：
 * 只做参数透传 + 契约固化，授权 / 沙箱在执行器内，格式化在 {@link ExecResults}。
 *
 * <p>描述分两层：
 * <ul>
 *   <li><b>默认基线描述</b>（内置，与后端无关的通用契约：工具名 / 工作目录 / stdin 空输入
 *       ；2026-12 起只保留框架私有与本沙箱特有事实，通用 shell 语法与工具常识不再入列）；</li>
 *   <li><b>开放描述接口</b>：{@link #description(String)} 全量覆盖、
 *       {@link #appendDescription(String)} 追加备注，由后端按自身能力定制
 *       （如 rg 注入提示，各后端注入方式不同）。</li>
 * </ul>
 */
public final class ShellTool {

    /**
     * stdin 语义——powershell / bash 两基线共用一份。该语义由「命令 stdin 契约」统一保证、与
     * 后端无关，但<b>实现手段三处不同</b>（DIRECT 用 NUL 设备→读到立即 EOF；codex 用管道 + 立即
     * closeStdin→2026-12 沙箱内实测 ReadToEnd 1.4ms 返回空串；windows-mic 用 NULL 句柄→读取是
     * 失败而非 EOF），故文案只按<b>效果</b>写「无输入可用」，不绑定某一手段，详见 ARCHITECTURE §7.10。
     *
     * <p>本常量原名 {@code STDIN_AND_SEARCH_NOTE}，还带一条「rg/grep/findstr 未给文件参数会改读
     * 空 stdin、结果与「没有匹配」同形，故务必显式给出搜索路径」的惯例说明——<b>2026-12 用户决策
     * 删除</b>，理由是该惯例属搜索工具的通用常识、模型自身已知。惯例本身、其实测后果、以及
     * 「不用命令名特判去兜底（RgShim 已 2026-10 删除）」的完整取舍依据<b>全部保留在 §7.10，
     * 不随本处精简而删</b>。若线上出现「空输出 + exit 1 被误读成无匹配」这类判读错误，应先回到
     * 该条核对再决定是否恢复，不要另写新文案绕开它。
     */
    private static final String STDIN_NOTE =
            "命令的 stdin 无输入可用,无法交互输入;";

    /**
     * PowerShell 默认基线描述。
     *
     * <p><b>精简原则（2026-12 用户决策）：只写「框架私有」与「本沙箱特有」两类事实，通用 shell
     * 语法一律不写</b>——模型自身已知的内容不该占用每次调用的上下文。据此删除四条，其事实与实测
     * 教训均保留在 ARCHITECTURE §7.10，<b>不随此处精简而删</b>：
     * <ul>
     *   <li>搜索命令无路径→改读空 stdin→结果与「没有匹配」同形（沿革见 {@link #STDIN_NOTE}）；</li>
     *   <li>{@code ;} 与 {@code &&}/{@code ||} 的版本差异——与后端追加的「实际执行 shell=&lt;exe&gt;」
     *       重复，模型据此可自行判断；</li>
     *   <li>cmdlet 输出自动表格化与默认裁列（要完整字段用 {@code Format-List *} /
     *       {@code ConvertTo-Json}）；</li>
     *   <li>框架已用 {@code Out-String -Width 4096} 收口、不要自加 {@code | Out-String}。</li>
     * </ul>
     * 末条的删除带<b>残余风险</b>：{@code 4096} 收口由 {@code ExecResults.buildPowerShellScript}
     * 提供，属框架私有行为、模型无法推知，而它从日常经验里学到的恰好是「管道输出加
     * {@code Out-String}」，于是描述不再拦截那个会让<b>内层</b>按默认 120 列折行的动作。将来若
     * 出现「长行被折断」的输出问题，第一嫌疑就是这里；处置优先级见 §7.10 PS-003「二次修正」——
     * 正解是在框架侧做到对自加不敏感，而不是把这条提示加回来。
     *
     * <p>保留的两条都有实测依据：{@code -join}（字符串拼数组按 {@code $OFS} 空格挤成一行是真歧义，
     * {@code -ExpandProperty} 管不到它）；stdin 语义见 {@link #STDIN_NOTE}。
     */
    private static final String POWERSHELL_BASELINE =
            "在系统上用 PowerShell 执行真实 OS 命令;"
            + "命令工作目录默认为任务工作区根;"
            + STDIN_NOTE
            + "数组直接拼进字符串会按 $OFS 空格连成一行,需明确分隔就用 -join;";

    /** bash 默认基线描述。 */
    private static final String BASH_BASELINE =
            "在系统上用 bash 执行真实 OS 命令;"
            + "命令工作目录默认为任务工作区根;"
            + STDIN_NOTE;

    private final ShellExecutor exec;
    private final Class<?> inputType;
    /** 最终工具名（默认由工厂方法指定，可经 {@link #name(String)} 覆盖）。 */
    private String name;
    /** 最终描述（基线或覆盖后的文本 + 追加备注）。 */
    private String description;

    private ShellTool(String name, String baseline, Class<?> inputType, ShellExecutor exec) {
        this.name = name;
        this.exec = exec;
        this.inputType = inputType;
        this.description = baseline;
    }

    /** 注册 powershell 工具。 */
    public static ShellTool powershell(ShellExecutor exec) {
        return new ShellTool("powershell", POWERSHELL_BASELINE, PowerShellCommand.class, exec);
    }

    /** 注册 bash 工具。 */
    public static ShellTool bash(ShellExecutor exec) {
        return new ShellTool("bash", BASH_BASELINE, BashCommand.class, exec);
    }

    /** 覆盖工具名（默认由工厂方法指定为 "powershell" / "bash"），返回 this（链式）。 */
    public ShellTool name(String name) {
        this.name = name;
        return this;
    }

    /** 全量覆盖描述（替换内置基线），返回 this（链式）。 */
    public ShellTool description(String description) {
        this.description = description;
        return this;
    }

    /** 追加备注到当前描述后，返回 this（链式）。 */
    public ShellTool appendDescription(String note) {
        this.description = this.description + note;
        return this;
    }

    /**
     * 编程式构建工具回调（因描述可运行时定制，不用 @Tool 注解）。
     * 注册点直接 {@code tools.add(ShellTool.powershell(exec).callback())}。
     */
    public ToolCallback callback() {
        return FunctionToolCallback.builder(name,
                (ShellCommand req, ToolContext ctx) -> exec.execute(req.command(), name))
                .description(description)
                .inputType(inputType)
                .build();
    }

    /** 工具入参公共形态（JSON Schema 仅一个 command 字段）。 */
    public interface ShellCommand {
        String command();
    }

    /** powershell 工具入参 POJO（@ToolParam 描述进入 JSON Schema）。 */
    public record PowerShellCommand(
            @ToolParam(description = "要执行的 PowerShell 命令,如 \"git status\"") String command)
            implements ShellCommand {
    }

    /** bash 工具入参 POJO（@ToolParam 描述进入 JSON Schema）。 */
    public record BashCommand(
            @ToolParam(description = "要执行的 bash 命令,如 \"git status\"") String command)
            implements ShellCommand {
    }
}
