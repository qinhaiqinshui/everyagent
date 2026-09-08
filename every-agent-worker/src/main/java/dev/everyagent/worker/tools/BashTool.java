package dev.everyagent.worker.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 真实 OS 命令执行工具 bash(仅 Linux/macOS 注册,Windows 注册 {@link PowerShellTool})。
 *
 * <p>取代旧 execute_command 的非 Windows 侧:接收完整 bash 命令字符串,
 * 委托 {@link CommandExecutor} 以 shell=bash 经 OsSandbox 隔离执行。
 * 不重复造轮子:只做参数透传,授权/沙箱/格式化全部复用 CommandExecutor。
 */
public class BashTool {

    private final CommandExecutor exec;

    public BashTool(CommandExecutor exec) {
        this.exec = exec;
    }

    @Tool(name = "bash", description = "在系统上用 bash 执行真实 OS 命令;"
            + "rg 已加入 PATH,可直接执行 rg 命令,内容搜索尽量使用rg命令，性能更好;"
            + "命令工作目录默认为任务工作区根;")
    public String bash(
            @ToolParam(description = "要执行的 bash 命令,如 \"git status\"") String command) {
        return exec.execute(command, "bash");
    }
}
