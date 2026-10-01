package dev.everyagent.plugin.sandbox.wslubuntu;

import dev.everyagent.plugin.api.WorkerServices;
import dev.everyagent.plugin.api.spi.ToolContext;
import dev.everyagent.plugin.api.spi.ToolProvider;
import dev.everyagent.plugin.api.config.WorkerConfig;
import dev.everyagent.plugin.api.spi.WorkspaceManager;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * wsl-ubuntu 沙箱自己的命令工具 ToolProvider。
 *
 * <p>appliesTo：只在当前沙箱是 wsl-ubuntu 时生效。
 * createTools：创建 {@link WslUbuntuBashTool}（使用 {@link WslUbuntuCommandExecutor}），
 * 返回 {@code List.of(ToolCallbacks.from(...))}。
 *
 * <p>禁网开关按 taskId 实时读取(每条命令执行瞬间查任务
 * {@code metadata[networkBlocked]}),故运行中用户选中/取消 /禁用网络 对本轮后续命令即时生效。
 */
public class WslUbuntuBashToolProvider implements ToolProvider {

    private final WorkerConfig props;
    private final WorkspaceManager workspaces;
    private final Path pluginDir;
    private final WorkerServices services;

    public WslUbuntuBashToolProvider(WorkerConfig props, WorkspaceManager workspaces,
            Path pluginDir, WorkerServices services) {
        this.props = props;
        this.workspaces = workspaces;
        this.pluginDir = pluginDir;
        this.services = services;
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
        WslUbuntuCommandExecutor exec = new WslUbuntuCommandExecutor(props, workspaceRoot,
                workspaces, pluginDir, taskNetworkBlocked(ctx.taskId()));
        return List.of(ToolCallbacks.from(new WslUbuntuBashTool(exec)));
    }

    /** 任务级禁网开关读取器(每次调用实时查任务 metadata;任务服务缺失时恒 false)。 */
    private BooleanSupplier taskNetworkBlocked(String taskId) {
        if (services == null || services.task() == null) {
            return () -> false;
        }
        return () -> NetworkTaskFlag.isOn(services.task().get(taskId));
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
