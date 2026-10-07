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
 *       与搜索命令无路径惯例）；</li>
 *   <li><b>开放描述接口</b>：{@link #description(String)} 全量覆盖、
 *       {@link #appendDescription(String)} 追加备注，由后端按自身能力定制
 *       （如 rg 注入提示，各后端注入方式不同）。</li>
 * </ul>
 */
public final class ShellTool {

    /**
     * stdin 语义 + 搜索命令无路径惯例——两基线共用一份。该语义由「命令 stdin 契约」统一
     * 保证、与后端无关，但<b>实现手段三处不同</b>（DIRECT 用 NUL 设备→读到立即 EOF；codex 用
     * 管道 + 立即 closeStdin→2026-12 沙箱内实测 ReadToEnd 1.4ms 返回空串；windows-mic 用 NULL
     * 句柄→读取是失败而非 EOF），故文案只按<b>效果</b>写「无输入可用」，不绑定某一手段，
     * 详见 ARCHITECTURE §7.10。
     *
     * <p>三件事缺一不可：①stdin 无输入可用（所以无法交互输入）；②rg/grep/findstr 在未给
     * 文件参数时按<b>工具自身惯例</b>把 stdin 当搜索源，于是得到对空输入的结果（实测空输出 +
     * exit 1）；③该结果与「没有匹配」<b>输出同形</b>——所以必须给正解（始终显式给路径），
     * 只警告不解决问题。这是 Unix/Windows 既有惯例、不是本实现的 bug，不用命令名特判去兜
     * （RgShim 已 2026-10 删除，见 §7.10）。
     */
    private static final String STDIN_AND_SEARCH_NOTE =
            "命令的 stdin 无输入可用(读它会立即得到空结果,部分后端直接读取失败,故无法交互输入);"
            + "rg/grep/findstr 这类搜索命令在未给文件参数时会按惯例改把 stdin 当搜索源,"
            + "于是得到的是对空输入的结果(实测空输出 + exit 1),与「没有匹配」在输出上无法区分"
            + "——搜索请始终显式给出路径(如 rg 模式 .);";

    /**
     * PowerShell 默认基线描述。
     *
     * <p>2026-12 沙箱内实测校准三条(旧文案里有两条推荐的解法是错的,记下判据防回归):
     * <ul>
     *   <li><b>连接符</b>:旧文案逐个枚举「5.1/cmd 会语法报错」属版本断言(实测本机是 pwsh 7.6),
     *       且与后端追加的「实际执行 shell=&lt;exe&gt;」职责重叠;现只留「不确定就一律用 ;」。</li>
     *   <li><b>空字符串参数</b>:旧文案称其为「PS 5.1 引擎行为」,双版本对照实测证伪——{@code -File}
     *       形态下 Windows PowerShell 5.1 与 pwsh 7.6 <b>均完整传递空串</b>(argc=2),丢弃只发生在
     *       已从两后端彻底删除的 {@code -Command} 内联形态。整条删除(教训归档 ARCHITECTURE §7.10,
     *       不必每次喂给模型)。</li>
     *   <li><b>对象输出</b>:旧文案推荐 {@code | Out-String} 提可靠性,实测<b>方向相反且有害</b>——
     *       执行框架已由 {@code ExecResults.buildPowerShellScript} 把整条命令包成
     *       {@code & { … } | Out-String -Width 4096} 收口(见 {@code OUT_STRING_WIDTH},其 javadoc
     *       本就写明「重定向下 PS 默认宽度只有 120」),模型<b>什么都不加</b>才能拿到完整输出
     *       (实测 200 字符属性完整一行);模型一旦自加 {@code | Out-String},<b>内层</b>按默认
     *       120 列折行(同一属性折成 120×6 行),外层 4096 收到的是已折好的字符串、救不回来。
     *       即:框架接入是对的,是<b>提示语在诱导模型破坏该收口</b>。{@code -ExpandProperty}
     *       同样管不了「字符串拼数组按 $OFS 空格连接」这个真歧义,解法是 {@code -join}。</li>
     * </ul>
     */
    private static final String POWERSHELL_BASELINE =
            "在系统上用 PowerShell 执行真实 OS 命令;"
            + "命令工作目录默认为任务工作区根;"
            + STDIN_AND_SEARCH_NOTE
            + "多条命令请用 ; 分隔(&& 与 || 仅 pwsh 7+ 支持,不确定实际 shell 版本就一律用 ;);"
            + "cmdlet 输出会被自动格式化成表格文本且默认只显示常用列(如 Get-Location 只剩 Path 列),"
            + "要完整字段用 Format-List * 或 ConvertTo-Json -Compress;"
            + "输出已由执行框架以 Out-String -Width 4096 统一收口,命令里不要再自己加 | Out-String"
            + "(内层不带 -Width 时按默认 120 列折行,外层收不回来);"
            + "多值请用 -join '<分隔符>' 明确分隔,字符串与数组直接拼接会按 $OFS 用空格连接(歧义源);";

    /** bash 默认基线描述。 */
    private static final String BASH_BASELINE =
            "在系统上用 bash 执行真实 OS 命令;"
            + "命令工作目录默认为任务工作区根;"
            + STDIN_AND_SEARCH_NOTE;

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
