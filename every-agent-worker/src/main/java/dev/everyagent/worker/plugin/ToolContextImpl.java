package dev.everyagent.worker.plugin;

import dev.everyagent.worker.modules.WorkspaceManager;
import dev.everyagent.worker.os.SandboxPathRegistry;
import dev.everyagent.plugin.api.interaction.InteractionService;
import dev.everyagent.plugin.api.spi.SandboxBackend;
import dev.everyagent.plugin.api.spi.ToolContext;
import dev.everyagent.worker.task.TaskEntry;
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
