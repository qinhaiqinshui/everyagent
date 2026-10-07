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
        // 通用常识类提示(搜索无路径读空 stdin、Out-String 收口、连接符版本)已按 2026-12
        // 决策从描述移除(沿革见 ARCHITECTURE §7.10),四后端一律不再追加;这里只留本沙箱
        // 特有事实,bash 语境下对照命令是 grep 而非 findstr。
        return List.of(ShellTool.bash(exec::execute)
                .appendDescription("内容搜索用 rg(已在 PATH,尊重 .gitignore,"
                        + "全仓递归远快于 grep -r);")
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
