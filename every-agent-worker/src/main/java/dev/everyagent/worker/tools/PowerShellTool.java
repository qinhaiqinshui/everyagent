package dev.everyagent.worker.tools;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.function.FunctionToolCallback;

/**
 * 真实 OS 命令执行工具 powershell(仅 Windows 注册,Linux/macOS 注册 {@link BashTool})。
 *
 * <p>取代旧 execute_command 的 Windows 侧:接收完整 PowerShell 命令字符串,
 * 委托 {@link CommandExecutor} 以 shell=powershell 经 OsSandbox 降权隔离执行
 * (Job Object + Restricted Token)。不重复造轮子:只做参数透传,授权/沙箱/格式化
 * 全部复用 CommandExecutor。
 *
 * <p><b>UTF-8 编码(PS-001 已修复)</b>:{@link CommandExecutor} 的 PowerShell 前缀
 * 自动设置 {@code [Console]::OutputEncoding=UTF8} 与 {@code $OutputEncoding=UTF8},
 * PowerShell 向管道输出时按 UTF-8 编码,Java 端按 UTF-8 解码,中文不再乱码。
 * 工具描述中已移除手动编码切换提示。
 *
 * <p><b>引号转义(PS-002 已修复)</b>:命令含双引号时经临时 {@code .ps1} 文件 +
 * {@code -File} 参数执行,绕开 ProcessBuilder 的 MSVCRT 引号转义对 PowerShell
 * {@code ""} 嵌套引号语法的截断。不含双引号的简单命令仍走 {@code -Command}。
 * 详见 {@link CommandExecutor#executePowerShellViaTempScript}。
 *
 * <p><b>CLIXML 噪声</b>:{@code -File} 模式与 {@code -Command} 模式行为一致——
 * Write-Host / Write-Output / 2>&1 / 原生 stderr 均以纯文本输出,不产生 CLIXML;
 * {@link CommandExecutor} 另预置非成功流 Preference 静默化 + {@code stripClixml} 兜底剥除残余。
 * 本工具保持纯透传,不感知也不重复处理。
 */
public class PowerShellTool {

    private final CommandExecutor exec;

    public PowerShellTool(CommandExecutor exec) {
        this.exec = exec;
    }

    /**
     * 编程式构建工具回调(因需包含编码说明等运行时信息,不用 @Tool 注解)。
     * 注册点直接 {@code tools.add(new PowerShellTool(exec).toolCallback())}。
     */
    public ToolCallback toolCallback() {
        String desc = "在 Windows 上用 PowerShell 执行真实 OS 命令。"
                + "rg 已加入 PATH,可直接执行 rg 命令，内容搜索尽量使用rg命令，性能更好;"
                + "命令工作目录固定为任务工作区根;"
                + "stdin 为 null 设备,命令无法从 stdin 读入输入;"
                + "输出编码已自动设为 UTF-8,无需手动切换。";
        return FunctionToolCallback.builder("powershell",
                (PowerShellCommand req, ToolContext ctx) -> powershell(req.command()))
                .description(desc)
                .inputType(PowerShellCommand.class)
                .build();
    }

    public String powershell(String command) {
        return exec.execute(command, "powershell");
    }

    /** 工具入参 POJO(@ToolParam 描述进入 JSON Schema)。 */
    public record PowerShellCommand(
            @ToolParam(description = "要执行的 PowerShell 命令,如 \"git status\" ") String command) {
    }
}
