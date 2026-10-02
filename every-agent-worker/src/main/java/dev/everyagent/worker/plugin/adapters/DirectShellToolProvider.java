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
        String rgNote = "rg 已加入 PATH,可直接执行 rg 命令，内容搜索尽量使用rg命令，性能更好;";
        if (win) {
            String note = rgNote + "输出编码已自动设为 UTF-8,无需手动切换。";
            return List.of(ShellTool.powershell(exec::execute)
                    .appendDescription(note)
                    .callback());
        } else {
            return List.of(ShellTool.bash(exec::execute)
                    .appendDescription(rgNote)
                    .callback());
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
}
