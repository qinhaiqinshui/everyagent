package dev.everyagent.plugin.sandbox.wslubuntu;

import dev.everyagent.plugin.api.WorkerServices;
import dev.everyagent.plugin.api.config.WorkerConfig;
import dev.everyagent.plugin.api.shell.ShellTool;
import dev.everyagent.plugin.api.spi.ToolContext;
import dev.everyagent.plugin.api.spi.ToolProvider;
import dev.everyagent.plugin.api.spi.WorkspaceManager;

import org.springframework.ai.tool.ToolCallback;

import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * wsl-ubuntu 沙箱自己的命令工具 ToolProvider。
 *
 * <p>appliesTo：只在当前沙箱是 wsl-ubuntu 时生效。
 * createTools：使用 {@link ShellTool#bash} + {@link WslUbuntuCommandExecutor}，
 * 返回 {@link ToolCallback}。
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
        Path workspaceRoot = ctx.workspaceRoot() != null ? Path.of(ctx.workspaceRoot()) : null;
        WslUbuntuCommandExecutor exec = new WslUbuntuCommandExecutor(props, workspaceRoot,
                workspaces, pluginDir, taskNetworkBlocked(ctx.subjectId()));
        return List.of(ShellTool.bash(exec::execute)
                .appendDescription("rg 已加入 PATH,可直接执行 rg 命令，内容搜索尽量使用rg命令，性能更好;")
                .callback());
    }

    /** 任务级禁网开关读取器(每次调用实时查任务 metadata;任务服务缺失时恒 false)。 */
    private BooleanSupplier taskNetworkBlocked(String subjectId) {
        if (services == null || services.task() == null) {
            return () -> false;
        }
        return () -> NetworkTaskFlag.isOn(services.task().get(subjectId));
    }
}
