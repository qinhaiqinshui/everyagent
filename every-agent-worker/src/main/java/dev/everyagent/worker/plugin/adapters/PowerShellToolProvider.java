package dev.everyagent.worker.plugin.adapters;

import dev.everyagent.worker.os.OsSandbox;
import dev.everyagent.worker.plugin.ToolContextImpl;
import dev.everyagent.plugin.api.spi.ToolContext;
import dev.everyagent.plugin.api.spi.ToolProvider;
import dev.everyagent.worker.tools.CommandExecutor;
import dev.everyagent.worker.tools.PowerShellTool;
import org.springframework.ai.tool.ToolCallback;

import java.nio.file.Path;
import java.util.List;

/**
 * PowerShell 命令工具提供者 —— 包装 {@link PowerShellTool}。
 *
 * <p>appliesTo: 当 Windows 且沙箱不注册 bash 工具时返回 true（PowerShell 为唯一命令工具），
 * 或任务级 powershellEnabled 开启时返回 true（bash 之外追加 PowerShell）。
 * 与改造前 {@code if (isWindows() && !sandbox.registerBashTool()) } 的 then 分支
 * + {@code if (t.powershellEnabled) } 追加分支一致。
 *
 * <p>createTools: 创建 CommandExecutor，然后 {@code new PowerShellTool(exec).toolCallback()}。
 */
public class PowerShellToolProvider implements ToolProvider {

    private final OsSandbox sandbox;

    public PowerShellToolProvider(OsSandbox sandbox) {
        this.sandbox = sandbox;
    }

    @Override
    public String pluginId() {
        return "builtin-powershell-tool";
    }

    @Override
    public boolean appliesTo(ToolContext ctx) {
        // 与改造前一致：
        // ① Windows 且沙箱不注册 bash → PowerShell 为唯一命令工具
        // ② 任务级 powershellEnabled → bash 之外追加 PowerShell
        boolean windowsNoBash = isWindows() && !ctx.sandbox().registerBashTool();
        boolean powershellEnabled = ((ToolContextImpl) ctx).taskEntry().powershellEnabled;
        return windowsNoBash || powershellEnabled;
    }

    @Override
    public List<ToolCallback> createTools(ToolContext ctx) {
        ToolContextImpl impl = (ToolContextImpl) ctx;
        Path rg = ctx.rgBinary();
        CommandExecutor exec = new CommandExecutor(sandbox, impl.taskEntry(), impl.gateImpl(),
                ctx.agentId(), rg != null ? rg.getParent() : null);
        return List.of(new PowerShellTool(exec).toolCallback());
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
}
