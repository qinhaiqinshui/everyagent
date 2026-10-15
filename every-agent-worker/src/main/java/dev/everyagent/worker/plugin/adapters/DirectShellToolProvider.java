package dev.everyagent.worker.plugin.adapters;

import dev.everyagent.plugin.api.shell.ShellTool;
import dev.everyagent.plugin.api.spi.SandboxBackend;
import dev.everyagent.plugin.api.spi.ToolContext;
import dev.everyagent.plugin.api.spi.ToolProvider;
import dev.everyagent.worker.os.OsSandbox;
import dev.everyagent.worker.plugin.ToolContextImpl;
import dev.everyagent.worker.tools.CommandExecutor;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * DIRECT（无沙箱）后端的 shell 工具提供者 —— 取代旧
 * {@code PowerShellToolProvider} + {@code BashToolProvider} 的 Windows DIRECT 分支
 * 与 Linux/macOS 分支。
 *
 * <p>appliesTo: 生效后端 id == {@code "direct"}（即 SPI 未解析到可用后端；
 * {@code ctx.sandbox()} 是 {@link OsSandbox} 门面,恒非 null,故不能只判 null）。
 *
 * <p>createTools: 按 OS 选工具——Windows 用 {@link ShellTool#powershell}，
 * 非 Windows 用 {@link ShellTool#bash}。执行器为自行创建的
 * {@link CommandExecutor}。
 *
 * <p>本提供者只负责 shell 本身:rg 能力已收敛到独立插件 ripgrep-tool
 * （every-agent-plugins/ripgrep-tool），本提供者既不注入 rg 进子进程 PATH，
 * 也不在描述中提及任何搜索工具（含 Select-String/grep 等未验证可用性的
 * 替代建议——描述谎报红线:不宣称任何本提供者管不了的搜索工具）。
 */
public class DirectShellToolProvider implements ToolProvider {

    private final OsSandbox sandbox;

    public DirectShellToolProvider(OsSandbox sandbox) {
        this.sandbox = sandbox;
    }

    @Override
    public String pluginId() {
        return "builtin-direct-shell-tool";
    }

    @Override
    public boolean appliesTo(ToolContext ctx) {
        // 仅 DIRECT（无 SPI 后端生效）:门面恒非 null,判「生效后端 id == direct」
        SandboxBackend sb = ctx.sandbox();
        return sb == null || "direct".equals(sb.id());
    }

    @Override
    public List<ToolCallback> createTools(ToolContext ctx) {
        ToolContextImpl impl = (ToolContextImpl) ctx;
        CommandExecutor exec = new CommandExecutor(sandbox, ctx,
                impl.gateImpl(), ctx.agentId(), impl.toolOutcomes());
        boolean win = isWindows();
        // 描述全量自报(2026-12 第三批:核心零默认,提供者必传):用途/工作目录两句随移交由本
        // 提供者自写;通用常识类条目(stdin 语义、非 ASCII 已解码、Out-String 收口、连接符版本)
        // 已按用户决策从描述删除(见 ARCHITECTURE §7.10),不得私自加回。
        // rg 不在本提供者管辖(由独立插件 ripgrep-tool 提供):描述不提任何搜索工具,
        // 也不给 Select-String/grep 等未验证可用性的替代建议——描述谎报红线:
        // 不宣称任何本提供者管不了的搜索工具。
        String head = win
                ? "在系统上用 PowerShell 执行真实 OS 命令;命令工作目录默认为任务工作区根;"
                : "在系统上用 bash 执行真实 OS 命令;命令工作目录默认为任务工作区根;";
        return List.of((win
                        ? ShellTool.powershell(head, exec::execute)
                        : ShellTool.bash(head, exec::execute))
                .callback());
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
}
