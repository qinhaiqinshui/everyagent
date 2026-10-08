package dev.everyagent.worker.plugin.adapters;

import dev.everyagent.plugin.api.shell.ShellTool;
import dev.everyagent.plugin.api.spi.SandboxBackend;
import dev.everyagent.plugin.api.spi.ToolContext;
import dev.everyagent.plugin.api.spi.ToolProvider;
import dev.everyagent.worker.os.OsSandbox;
import dev.everyagent.worker.plugin.ToolContextImpl;
import dev.everyagent.worker.tools.CommandExecutor;
import dev.everyagent.worker.tools.RipgrepBinary;
import org.springframework.ai.tool.ToolCallback;

import java.nio.file.Path;
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
 * {@link CommandExecutor}（带 rgBinDir，DIRECT 后端 rg 仍可用）。
 *
 * <p><b>rg 注入路线</b>：DirectShellToolProvider 持有 {@link OsSandbox} +
 * {@link RipgrepBinary}，createTools 时若 rg 可用则把 rg 目录作为 rgBinDir 传入
 * CommandExecutor，由 CommandExecutor 把 rg 目录注入子进程 PATH。
 * {@link ToolContextImpl#shellExecutor()} 装的是不带 rg 的执行器
 * （rgBinDir=null），主要服务 windows-mic 等外部插件（mic 插件自己带 rg）；
 * DIRECT 后端的 rg 可用性由本提供者承担，不经 ctx.shellExecutor()。
 */
public class DirectShellToolProvider implements ToolProvider {

    private final OsSandbox sandbox;
    private final RipgrepBinary rgBinary;

    public DirectShellToolProvider(OsSandbox sandbox, RipgrepBinary rgBinary) {
        this.sandbox = sandbox;
        this.rgBinary = rgBinary;
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
        Path rgDir = (rgBinary != null && rgBinary.available() && rgBinary.path() != null)
                ? rgBinary.path().getParent() : null;
        ToolContextImpl impl = (ToolContextImpl) ctx;
        CommandExecutor exec = new CommandExecutor(sandbox, ctx,
                impl.gateImpl(), ctx.agentId(), rgDir);
        boolean win = isWindows();
        // 描述全量自报(2026-12 第三批:核心零默认,提供者必传):用途/工作目录两句随移交由本
        // 提供者自写;通用常识类条目(stdin 语义、非 ASCII 已解码、Out-String 收口、连接符版本)
        // 已按用户决策从描述删除(见 ARCHITECTURE §7.10),不得私自加回。
        // rg 提示按实际解析结果条件化:rgDir==null 时 CommandExecutor 根本不会注入 rg 目录,
        // 此时若仍宣称「已在 PATH」,模型会把「命令不存在」误读成「无匹配、结果正常」。
        // 本提供者按 OS 分叉,对照命令也必须分叉——Linux/macOS 分支提 findstr 是错的。
        String rgNote = rgDir != null
                ? "内容搜索用 rg(已在 PATH,尊重 .gitignore,全仓递归远快于"
                        + (win ? " findstr);" : " grep -r);")
                : "rg 不可用,内容搜索改用" + (win ? " Select-String;" : " grep;");
        String head = win
                ? "在系统上用 PowerShell 执行真实 OS 命令;命令工作目录默认为任务工作区根;"
                : "在系统上用 bash 执行真实 OS 命令;命令工作目录默认为任务工作区根;";
        return List.of((win
                        ? ShellTool.powershell(head + rgNote, exec::execute)
                        : ShellTool.bash(head + rgNote, exec::execute))
                .callback());
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
}
