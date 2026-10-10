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
 * <p><b>描述零默认（2026-12 用户决策，描述精简第三批）</b>：核心不内置任何基线描述——
 * 谁提供 shell 工具，谁写全量描述。工厂方法必传 {@code description}（null/空串运行期
 * 拒绝），工具用途、工作目录、rg 可用性、实际 shell 等一律由提供者按自身事实声明；
 * 运行期可经 {@link #description(String)} 全量覆盖、{@link #appendDescription(String)}
 * 追加。各条描述的删除沿革与残余风险见 ARCHITECTURE §7.10。
 */
public final class ShellTool {

    private final ShellExecutor exec;
    private final Class<?> inputType;
    /** 最终工具名（默认由工厂方法指定，可经 {@link #name(String)} 覆盖）。 */
    private String name;
    /** 最终描述（工厂传入的文本 + 追加备注，核心不提供默认基线）。 */
    private String description;

    private ShellTool(String name, String description, Class<?> inputType, ShellExecutor exec) {
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException(
                    "description 必填：核心零默认，提供者必须自报工具描述（ARCHITECTURE §7.10 第三批）");
        }
        this.name = name;
        this.description = description;
        this.exec = exec;
        this.inputType = inputType;
    }

    /** 注册 powershell 工具（描述由提供者必传，核心不提供默认基线）。 */
    public static ShellTool powershell(String description, ShellExecutor exec) {
        return new ShellTool("powershell", description, PowerShellCommand.class, exec);
    }

    /** 注册 bash 工具（描述由提供者必传，核心不提供默认基线）。 */
    public static ShellTool bash(String description, ShellExecutor exec) {
        return new ShellTool("bash", description, BashCommand.class, exec);
    }

    /** 覆盖工具名（默认由工厂方法指定为 "powershell" / "bash"），返回 this（链式）。 */
    public ShellTool name(String name) {
        this.name = name;
        return this;
    }

    /** 全量覆盖描述（替换工厂传入的文本），返回 this（链式）。 */
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
     * 注册点直接 {@code tools.add(ShellTool.powershell(desc, exec).callback())}。
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