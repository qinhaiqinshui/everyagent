package dev.everyagent.worker.tools;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.function.FunctionToolCallback;

import java.nio.charset.Charset;

/**
 * 真实 OS 命令执行工具 powershell(Windows 注册;WSL+Linux 后端经任务级
 * {@code /启用powershell} 斜杠命令可<b>追加</b>注册,与 {@link BashTool} 并存;
 * 其余 Linux/macOS 注册 {@link BashTool})。
 *
 * <p>取代旧 execute_command 的 Windows 侧:接收完整 PowerShell 命令字符串,
 * 委托 {@link CommandExecutor} 以 shell=powershell 经 OsSandbox 降权隔离执行
 * (Job Object + Restricted Token)。WSL 后端注册时,shell=powershell 由沙箱归一为
 * 发行版内 {@code pwsh}(PowerShell Core,须 {@code worker.sandbox.wsl.pwsh-enabled=true})。
 * 不重复造轮子:只做参数透传,授权/沙箱/格式化全部复用 CommandExecutor。
 *
 * <p><b>乱码提示(动态描述,仅 Windows 后端)</b>:PowerShell 5.1 向管道/文件输出时按控制台
 * OEM 代码页编码(简体中文系统默认 936/GBK),而 {@link CommandExecutor} 统一按 UTF-8
 * 解码子进程输出,故输出含中文时可能乱码。本工具用代码探测当前系统默认编码
 * ({@code native.encoding},即 Windows 系统 ACP),动态拼入工具描述,提示 AI 先
 * 设置输出编码(如 {@code [Console]::OutputEncoding=[System.Text.Encoding]::UTF8})再输出。
 * WSL 后端输出为 UTF-8,无该问题,描述改为 Linux 路径语义与 pwsh 启用前提。
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
    /** 是否注册在 WSL+Linux 后端(描述按发行版内 pwsh 语义拼装,而非 Windows PowerShell)。 */
    private final boolean wslBackend;
    private final String systemEncoding;

    /** Windows 原生(或默认)形态:描述按 Windows PowerShell 语义。 */
    public PowerShellTool(CommandExecutor exec) {
        this(exec, false);
    }

    /** 显式指定注册后端:WSL 后端({@code wslBackend=true})描述按发行版内 pwsh 语义。 */
    public PowerShellTool(CommandExecutor exec, boolean wslBackend) {
        this.exec = exec;
        this.wslBackend = wslBackend;
        this.systemEncoding = detectSystemEncoding();
    }

    /** 探测当前系统默认编码:优先 native.encoding(Windows 上即系统 ACP,如 GBK/936),回退 JVM 默认。 */
    private static String detectSystemEncoding() {
        String nativeEnc = System.getProperty("native.encoding");
        return (nativeEnc != null && !nativeEnc.isBlank()) ? nativeEnc : Charset.defaultCharset().name();
    }

    /**
     * 编程式构建工具回调:描述按注册后端(windows-mic / wsl)拼装——Windows 动态注入
     * 当前系统默认编码与乱码应对提示;WSL 注入 Linux 路径语义与 pwsh 启用前提。
     * 注册点直接 {@code tools.add(new PowerShellTool(exec).toolCallback())}。
     */
    public ToolCallback toolCallback() {
        return FunctionToolCallback.builder("powershell",
                (PowerShellCommand req, ToolContext ctx) -> powershell(req.command()))
                .description(wslBackend ? wslDescription() : windowsDescription())
                .inputType(PowerShellCommand.class)
                .build();
    }

    /** Windows 后端工具描述(含乱码提示;行为与既有描述逐字一致)。 */
    private String windowsDescription() {
        return "在 Windows 上用 PowerShell 执行真实 OS 命令。"
                + "rg 已加入 PATH,可直接执行 rg 命令，内容搜索尽量使用rg命令，性能更好;"
                + "命令工作目录固定为任务工作区根;"
                + "stdin 为 null 设备,命令无法从 stdin 读入输入;"
                + "⚠️ 当前系统默认编码: " + systemEncoding
                + "若输出含中文出现乱码,请在命令前先执行 [Console]::OutputEncoding=[System.Text.Encoding]::UTF8 再输出,"
                + "或先执行 chcp 65001 切换代码页)。";
    }

    /** WSL+Linux 后端工具描述:命令经发行版内 pwsh 执行,路径为 Linux 语义,输出 UTF-8。 */
    private String wslDescription() {
        return "在 WSL 发行版内用 PowerShell(pwsh)执行命令,与 bash 工具共享同一 Linux 沙箱环境;"
                + "路径为 Linux 语义(/workspace、/mnt/<盘>),输出 UTF-8,无 Windows 代码页乱码问题;"
                + "rg 已加入 PATH,可直接执行 rg 命令，内容搜索尽量使用rg命令，性能更好;"
                + "命令工作目录固定为任务工作区根;"
                + "stdin 为 /dev/null,命令无法从 stdin 读入输入;"
                + "仅当发行版已安装 pwsh 且 worker.sandbox.wsl.pwsh-enabled=true 时可执行,否则返回错误提示。";
    }

    public String powershell(String command) {
        return exec.execute(command, "powershell");
    }

    /** 工具入参 POJO(@ToolParam 描述进入 JSON Schema)。 */
    public record PowerShellCommand(
            @ToolParam(description = "要执行的 PowerShell 命令,如 \"git status\" ") String command) {
    }
}
