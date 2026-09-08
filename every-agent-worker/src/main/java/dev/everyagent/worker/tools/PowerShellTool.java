package dev.everyagent.worker.tools;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.function.FunctionToolCallback;

import java.nio.charset.Charset;

/**
 * 真实 OS 命令执行工具 powershell(仅 Windows 注册,Linux/macOS 注册 {@link BashTool})。
 *
 * <p>取代旧 execute_command 的 Windows 侧:接收完整 PowerShell 命令字符串,
 * 委托 {@link CommandExecutor} 以 shell=powershell 经 OsSandbox 降权隔离执行
 * (Job Object + Restricted Token)。不重复造轮子:只做参数透传,授权/沙箱/格式化
 * 全部复用 CommandExecutor。
 *
 * <p><b>乱码提示(动态描述)</b>:PowerShell 5.1 向管道/文件输出时按控制台 OEM 代码页编码
 * (简体中文系统默认 936/GBK),而 {@link CommandExecutor} 统一按 UTF-8 解码子进程输出,
 * 故输出含中文时可能乱码。本工具用代码探测当前系统默认编码
 * ({@code native.encoding},即 Windows 系统 ACP),动态拼入工具描述,提示 AI 先
 * 设置输出编码(如 {@code [Console]::OutputEncoding=[System.Text.Encoding]::UTF8})再输出。
 * 因描述需运行时拼接,@Tool 注解(编译期常量)无法表达,故改用编程式
 * {@link FunctionToolCallback} 构建。
 *
 * <p><b>CLIXML 噪声</b>:PowerShell 5.1 在 {@code -EncodedCommand} 模式下、stdout/stderr 被管道
 * 重定向时,会把非成功流(progress / information / error)序列化成 {@code #< CLIXML ...>}
 * 写进 stderr。已根治:worker 用 {@code -Command} + MSVCRT quoteArg 执行(与 bash 分支一致),
 * 该模式下 Write-Host / Write-Output / 2>&1 / 原生 stderr 均以纯文本输出、无 CLIXML;
 * {@link CommandExecutor} 另预置非成功流 Preference 静默化 + {@code stripClixml} 兜底剥除残余。
 * 本工具保持纯透传,不感知也不重复处理。
 */
public class PowerShellTool {

    private final CommandExecutor exec;
    private final String systemEncoding;

    public PowerShellTool(CommandExecutor exec) {
        this.exec = exec;
        this.systemEncoding = detectSystemEncoding();
    }

    /** 探测当前系统默认编码:优先 native.encoding(Windows 上即系统 ACP,如 GBK/936),回退 JVM 默认。 */
    private static String detectSystemEncoding() {
        String nativeEnc = System.getProperty("native.encoding");
        return (nativeEnc != null && !nativeEnc.isBlank()) ? nativeEnc : Charset.defaultCharset().name();
    }

    /**
     * 编程式构建工具回调:描述中动态注入当前系统默认编码与乱码应对提示。
     * 注册点直接 {@code tools.add(new PowerShellTool(exec).toolCallback())}。
     */
    public ToolCallback toolCallback() {
        String desc = "在 Windows 上用 PowerShell 执行真实 OS 命令。"
                + "rg 已加入 PATH,可直接执行 rg 命令，内容搜索尽量使用rg命令，性能更好;"
                + "命令工作目录固定为任务工作区根;"
                + "⚠️ 当前系统默认编码: " + systemEncoding
                + "若输出含中文出现乱码,请在命令前先执行 [Console]::OutputEncoding=[System.Text.Encoding]::UTF8 再输出,"
                + "或先执行 chcp 65001 切换代码页)。";
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
