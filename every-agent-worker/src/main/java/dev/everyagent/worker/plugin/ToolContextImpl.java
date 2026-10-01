package dev.everyagent.worker.plugin;

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
        // 不带 rg 的执行器(rgBinDir=null),服务 windows-mic 等外部插件
        //（mic 插件自己带 rg）。DIRECT 后端的 rg 注入由 DirectShellToolProvider 自行创建
        // 带 rgBinDir 的 CommandExecutor 承担。
        if (osSandbox == null) {
            return null;
        }
        CommandExecutor exec = new CommandExecutor(osSandbox, taskEntry, gate, agentId);
        return exec::execute;
    }

    @Override
    public ShellExecutor shellExecutor(Path extraBinDir) {
        // 带插件自带工具目录(如 rg)的执行器,把 extraBinDir 注入子进程 PATH
        if (osSandbox == null) {
            return null;
        }
        CommandExecutor exec = new CommandExecutor(osSandbox, taskEntry, gate, agentId, extraBinDir);
        return exec::execute;
    }

    /** 额外暴露：任务条目（含 metadata 等任务级开关），供内置适配器使用。 */
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
