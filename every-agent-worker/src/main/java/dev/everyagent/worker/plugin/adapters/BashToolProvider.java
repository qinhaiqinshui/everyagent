package dev.everyagent.worker.plugin.adapters;

import dev.everyagent.worker.os.OsSandbox;
import dev.everyagent.worker.plugin.ToolContextImpl;
import dev.everyagent.plugin.api.spi.ToolContext;
import dev.everyagent.plugin.api.spi.ToolProvider;
import dev.everyagent.worker.tools.BashTool;
import dev.everyagent.worker.tools.CommandExecutor;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

import java.nio.file.Path;
import java.util.List;

/**
 * Bash 命令工具提供者 —— 包装 {@link BashTool} + {@link CommandExecutor}。
 *
 * <p>appliesTo: 当 bash 工具应注册时返回 true（非 Windows，或 WSL 系列后端）。
 * 与改造前 {@code if (isWindows() && !sandbox.registerBashTool()) } 的 else 分支一致。
 *
 * <p>createTools: 创建 CommandExecutor，然后 {@code ToolCallbacks.from(new BashTool(exec))}。
 */
public class BashToolProvider implements ToolProvider {

    private final OsSandbox sandbox;

    public BashToolProvider(OsSandbox sandbox) {
        this.sandbox = sandbox;
    }

    @Override
    public String pluginId() {
        return "builtin-bash-tool";
    }

    @Override
    public boolean appliesTo(ToolContext ctx) {
        // 非注册 PowerShellTool 的平台注册 BashTool（Linux/macOS）;
        // Windows 平台的 BashTool 由沙箱插件提供（如 wsl-ubuntu）,核心不注册。
        return !isWindows();
    }

    @Override
    public List<ToolCallback> createTools(ToolContext ctx) {
        ToolContextImpl impl = (ToolContextImpl) ctx;
        Path rg = ctx.rgBinary();
        CommandExecutor exec = new CommandExecutor(sandbox, impl.taskEntry(), impl.gateImpl(),
                ctx.agentId(), rg != null ? rg.getParent() : null);
        return List.of(ToolCallbacks.from(new BashTool(exec)));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
}
