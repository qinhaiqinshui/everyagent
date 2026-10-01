package dev.everyagent.worker.plugin.adapters;

import dev.everyagent.worker.os.OsSandbox;
import dev.everyagent.worker.plugin.ToolContextImpl;
import dev.everyagent.plugin.api.spi.SandboxBackend;
import dev.everyagent.plugin.api.spi.ToolContext;
import dev.everyagent.plugin.api.spi.ToolProvider;
import dev.everyagent.worker.tools.CommandExecutor;
import dev.everyagent.worker.tools.PowerShellTool;
import org.springframework.ai.tool.ToolCallback;

import java.nio.file.Path;
import java.util.List;

/**
 * bash 命令工具提供者（Windows 原生后端）—— 包装 {@link PowerShellTool}。
 *
 * <p>appliesTo: Windows 平台且当前沙箱后端为 windows-mic 或 direct（无沙箱插件）
 * 时返回 true。wsl-ubuntu / codex 后端各自提供自己的 bash 工具,不在此注册。
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
        // 仅 Windows 平台;wsl-ubuntu / codex 后端各自提供自己的 bash 工具
        if (!isWindows()) {
            return false;
        }
        SandboxBackend sb = ctx.sandbox();
        if (sb == null) {
            return true; // 无沙箱（DIRECT）
        }
        String id = sb.id();
        return "direct".equals(id) || "windows-mic".equals(id);
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
