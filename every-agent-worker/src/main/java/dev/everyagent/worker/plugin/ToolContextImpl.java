package dev.everyagent.worker.plugin;

import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.worker.modules.WorkspaceManager;
import dev.everyagent.worker.os.OsSandbox;
import dev.everyagent.worker.os.SandboxPathRegistry;
import dev.everyagent.plugin.api.interaction.InteractionService;
import dev.everyagent.plugin.api.shell.ShellExecutor;
import dev.everyagent.plugin.api.spi.SandboxBackend;
import dev.everyagent.plugin.api.spi.ToolContext;
import dev.everyagent.worker.task.TaskEntry;
import dev.everyagent.worker.tools.CommandExecutor;
import dev.everyagent.worker.tools.PermissionGate;

import java.nio.file.Path;

/**
 * {@link ToolContext} 的内置实现。
 *
 * <p>封装 per-task 信息（taskId、agentId、workspaceRoot、TaskEntry）和核心只读服务
 * （沙箱、权限门、工作区管理器、rg 二进制路径），供 {@link dev.everyagent.worker.plugin.spi.ToolProvider}
 * 据此创建工具实例。
 *
 * <p>除实现 {@link ToolContext} 接口方法外，额外暴露 {@link #taskEntry()} 供内置适配器
 * 获取 {@link TaskEntry}（含 metadata 等任务级开关）。外部插件仅依赖接口方法。
 */
public class ToolContextImpl implements ToolContext {

    private final String taskId;
    private final String agentId;
    private final Path workspaceRoot;
    private final SandboxBackend sandbox;
    private final OsSandbox osSandbox;
    private final PermissionGate gate;
    private final WorkspaceManager workspaces;
    private final Path rgBinary;
    private final InteractionService interaction;
    private final TaskEntry taskEntry;
    private final SandboxPathRegistry pathRegistry;

    public ToolContextImpl(String taskId, String agentId, Path workspaceRoot,
            SandboxBackend sandbox, PermissionGate gate, WorkspaceManager workspaces,
            Path rgBinary, InteractionService interaction, TaskEntry taskEntry,
            SandboxPathRegistry pathRegistry) {
        this.taskId = taskId;
        this.agentId = agentId;
        this.workspaceRoot = workspaceRoot;
        this.sandbox = sandbox;
        this.osSandbox = sandbox instanceof OsSandbox os ? os : null;
        this.gate = gate;
        this.workspaces = workspaces;
        this.rgBinary = rgBinary;
        this.interaction = interaction;
        this.taskEntry = taskEntry;
        this.pathRegistry = pathRegistry;
    }

    @Override
    public String taskId() {
        return taskId;
    }

    @Override
    public String agentId() {
        return agentId;
    }

    @Override
    public Path workspaceRoot() {
        return workspaceRoot;
    }

    @Override
    public SandboxBackend sandbox() {
        return sandbox;
    }

    @Override
    public dev.everyagent.plugin.api.spi.WorkspaceManager workspaces() {
        return workspaces;
    }

    @Override
    public Path rgBinary() {
        return rgBinary;
    }

    @Override
    public InteractionService interaction() {
        return interaction;
    }

    @Override
    public ShellExecutor shellExecutor() {
        // 不带 rg 的执行器(rgBinDir=null),服务外部插件。
        // 插件如需注入自带工具(如 rg)到 PATH，自行包装 ShellExecutor 实现。
        // DIRECT 后端的 rg 注入由 DirectShellToolProvider 自行创建带 rgBinDir 的
        // CommandExecutor 承担，不经此接口。
        if (osSandbox == null) {
            return null;
        }
        CommandExecutor exec = new CommandExecutor(osSandbox, execution(), gate, agentId);
        return exec::execute;
    }

    /**
     * 统一执行上下文槽位（S3 起内置工具/授权链经此取数，不再接触 TaskEntry 具体类型；
     * worker 域内恒为 TaskEntry 实例）。
     */
    @Override
    public ExecContext execution() {
        return taskEntry;
    }

    /** 额外暴露：任务条目（含 metadata 等任务级开关），供内置适配器使用（过渡，S5 退役）。 */
    public TaskEntry taskEntry() {
        return taskEntry;
    }

    /** 内置适配器专用：权限门具体实现（接口方法返回 SPI 接口类型）。 */
    public PermissionGate gateImpl() {
        return gate;
    }

    /** 路径翻译中间人（宿主路径 ↔ 沙箱内路径）。 */
    public SandboxPathRegistry pathRegistry() {
        return pathRegistry;
    }
}
