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
 * <p>禁网开关按 taskId 实时读取(每条命令执行瞬间查任务级 {@code network.access} token,
 * 恒以磁盘 {@code slash-tokens.json} 为准,见 {@link NetworkTaskFlag#isOn}),故运行中用户
 * 选中/取消 /禁用网络 对本轮后续命令即时生效。
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
                workspaces, pluginDir, taskNetworkBlocked(ctx.subjectId()), ctx.toolOutcomes());
        // 描述全量自报(2026-12 第三批:核心零默认,提供者必传):用途/工作目录两句随移交由本
        // 后端自写;通用常识类条目(stdin 语义、Out-String 收口、连接符版本)已按用户决策从
        // 描述删除(沿革见 ARCHITECTURE §7.10),后端不得在此私自加回。这里只留本沙箱
        // 特有事实。
        //
        // rg 不进本描述:rg 工具由独立插件 ripgrep-tool 提供(schema 化工具,其 appliesTo
        // 排除本后端——wsl 沙箱内的内容搜索属 wsl 语境,归本 bash 工具);发行版内用什么
        // 命令搜索(grep/rg 等)由模型自行决定——发行版是否预装 rg 宿主侧无从静态判定,
        // 任何方向的搜索工具建议都踩谎报红线(§7.10)。
        return List.of(ShellTool.bash("在系统上用 bash 执行真实 OS 命令;"
                        + "命令工作目录默认为任务工作区根;",
                gated(ctx.commandGate(), exec::execute)).callback());
    }

    /**
     * 门禁包装：先授权、再执行（授权拒绝 → 命令不执行，异常回灌模型）。
     *
     * <p>本插件的命令执行器自建，worker 无法拦命令串，故必须在此过 worker 的授权门禁——
     * 否则「工作区外路径授权 → 下发沙箱」这条链根本不会启动。授权通过后 worker 会同步把
     * 授权范围下发沙箱（§7.8），本类无需自行落地权限。
     */
    static dev.everyagent.plugin.api.shell.ShellExecutor gated(
            dev.everyagent.plugin.api.spi.CommandGate gate,
            dev.everyagent.plugin.api.shell.ShellExecutor delegate) {
        return (command, shell) -> {
            gate.authorize(command);
            return delegate.execute(command, shell);
        };
    }

    /** 任务级禁网开关读取器(每次调用实时查任务级 network.access token;services 缺失时恒 false)。 */
    private BooleanSupplier taskNetworkBlocked(String subjectId) {
        if (services == null) {
            return () -> false;
        }
        return () -> NetworkTaskFlag.isOn(services, subjectId);
    }
}
