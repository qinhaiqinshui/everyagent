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
 *   <li><b>默认基线描述</b>（内置，与后端无关的通用契约：工具名 / 工作目录 / stdin 为 null 设备）；</li>
 *   <li><b>开放描述接口</b>：{@link #description(String)} 全量覆盖、
 *       {@link #appendDescription(String)} 追加备注，由后端按自身能力定制
 *       （如 rg 注入提示，各后端注入方式不同）。</li>
 * </ul>
 */
public final class ShellTool {

    /** PowerShell 默认基线描述。 */
    private static final String POWERSHELL_BASELINE =
            "在系统上用 PowerShell 执行真实 OS 命令;"
            + "命令工作目录默认为任务工作区根;"
            + "stdin 为 null 设备,命令无法从 stdin 读入输入;"
            + "多条命令请用 ; 分隔;"
            + "Windows PowerShell 5.1 不支持 && 与 || 连接符,请用 ; 分隔多条命令;"
            + "PowerShell 5.1 会丢弃传给原生命令的空字符串参数(如 rg -c \"\" 中的 \"\" "
            + "不会传给 rg,导致后一个参数被当成模式),请避免空字符串参数;"
            + "需要可靠读取对象输出时建议显式转字符串(如 | Out-String 或 -ExpandProperty);";

    /** bash 默认基线描述。 */
    private static final String BASH_BASELINE =
            "在系统上用 bash 执行真实 OS 命令;"
            + "命令工作目录默认为任务工作区根;"
            + "stdin 为 /dev/null,命令无法从 stdin 读入输入;";

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
