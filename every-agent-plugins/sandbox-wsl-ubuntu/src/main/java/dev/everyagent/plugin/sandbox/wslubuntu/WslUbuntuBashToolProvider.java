package dev.everyagent.plugin.sandbox.wslubuntu;

import dev.everyagent.plugin.api.spi.ToolContext;
import dev.everyagent.plugin.api.spi.ToolProvider;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.modules.WorkspaceManager;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.nio.file.Path;
import java.util.List;

/**
 * wsl-ubuntu 沙箱自己的命令工具 ToolProvider。
 *
 * <p>appliesTo：只在当前沙箱是 wsl-ubuntu 时生效。
 * createTools：创建 {@link WslUbuntuBashTool}（使用 {@link WslUbuntuCommandExecutor}），
 * 返回 {@code List.of(ToolCallbacks.from(...))}。
 */
public class WslUbuntuBashToolProvider implements ToolProvider {

    private final WorkerProperties props;
    private final WorkspaceManager workspaces;
    private final Path pluginDir;

    public WslUbuntuBashToolProvider(WorkerProperties props, WorkspaceManager workspaces,
            Path pluginDir) {
        this.props = props;
        this.workspaces = workspaces;
        this.pluginDir = pluginDir;
    }

    @Override
    public String pluginId() {
        return "sandbox-wsl-ubuntu";
    }

    @Override
    public boolean appliesTo(ToolContext ctx) {
        return ctx.sandbox() != null && "wsl-ubuntu".equals(ctx.sandbox().id());
    }

    @Override
    public List<ToolCallback> createTools(ToolContext ctx) {
        Path workspaceRoot = ctx.workspaceRoot();
        WslUbuntuCommandExecutor exec = new WslUbuntuCommandExecutor(props, workspaceRoot, workspaces, pluginDir);
        return List.of(ToolCallbacks.from(new WslUbuntuBashTool(exec)));
    }

    /**
     * wsl-ubuntu 沙箱的 bash 工具（本地实现，不依赖 worker 的 CommandExecutor）。
     *
     * <p>接收完整 bash 命令字符串，委托 {@link WslUbuntuCommandExecutor#execute}
     * 在 WSL 发行版内以 root 直连执行。
     */
    public static class WslUbuntuBashTool {

        private final WslUbuntuCommandExecutor exec;

        public WslUbuntuBashTool(WslUbuntuCommandExecutor exec) {
            this.exec = exec;
        }

        @Tool(name = "bash", description = "在系统上用 bash 执行真实 OS 命令;"
                + "rg 已加入 PATH,可直接执行 rg 命令,内容搜索尽量使用rg命令，性能更好;"
                + "命令工作目录默认为任务工作区根;"
                + "stdin 为 /dev/null,命令无法从 stdin 读入输入;")
        public String bash(
                @ToolParam(description = "要执行的 bash 命令,如 \"git status\"") String command) {
            return exec.execute(command, "bash");
        }
    }
}
