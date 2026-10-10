package dev.everyagent.worker.plugin;

import dev.everyagent.plugin.api.agent.AgentContext;
import dev.everyagent.plugin.api.agent.AgentFactory;
import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.plugin.api.interaction.InteractionService;
import dev.everyagent.plugin.api.model.EventEmitter;
import dev.everyagent.plugin.api.model.ModelConfig;
import dev.everyagent.worker.modules.WorkspaceManager;
import dev.everyagent.worker.os.OsSandbox;
import dev.everyagent.worker.os.SandboxPathRegistry;
import dev.everyagent.plugin.api.shell.ShellExecutor;
import dev.everyagent.plugin.api.spi.SandboxBackend;
import dev.everyagent.plugin.api.spi.ToolContext;
import dev.everyagent.worker.tools.CommandExecutor;
import dev.everyagent.worker.tools.PermissionGate;

import java.nio.file.Path;
import java.util.Map;

/**
 * {@link ToolContext} 的内置实现。
 *
 * <p>实现 {@link ExecContext}：全部执行主体槽位（subjectId / workspaceRoot /
 * snapshot / emitter 等）委托持有的 {@code exec}，无需自身重复存储；外加工具
 * 创建侧专属信息（agentId、沙箱后端、权限门、工作区管理器、rg 二进制路径、
 * 路径翻译中间人）。内置适配器与外部插件一律经本对象取数，不再接触
 * {@code TaskEntry} 具体类型。
 */
public class ToolContextImpl implements ToolContext {

    private final ExecContext exec;
    private final String agentId;
    private final SandboxBackend sandbox;
    private final OsSandbox osSandbox;
    private final PermissionGate gate;
    private final WorkspaceManager workspaces;
    private final Path rgBinary;
    private final SandboxPathRegistry pathRegistry;

    public ToolContextImpl(ExecContext exec, String agentId, SandboxBackend sandbox,
            PermissionGate gate, WorkspaceManager workspaces, Path rgBinary,
            SandboxPathRegistry pathRegistry) {
        this.exec = exec;
        this.agentId = agentId;
        this.sandbox = sandbox;
        this.osSandbox = sandbox instanceof OsSandbox os ? os : null;
        this.gate = gate;
        this.workspaces = workspaces;
        this.rgBinary = rgBinary;
        this.pathRegistry = pathRegistry;
    }

    // ── ExecContext 槽位（委托 exec） ──────────────────────────

    @Override
    public String subjectId() {
        return exec.subjectId();
    }

    @Override
    public String workspaceRoot() {
        return exec.workspaceRoot();
    }

    @Override
    public String workspaceId() {
        return exec.workspaceId();
    }

    @Override
    public ModelConfig snapshot() {
        return exec.snapshot();
    }

    @Override
    public EventEmitter emitter() {
        return exec.emitter();
    }

    @Override
    public AgentFactory agentFactory() {
        return exec.agentFactory();
    }

    @Override
    public Map<String, Object> metadata() {
        return exec.metadata();
    }

    @Override
    public Path dataDir() {
        return exec.dataDir();
    }

    @Override
    public boolean terminal() {
        return exec.terminal();
    }

    @Override
    public InteractionService interaction() {
        return exec.interaction();
    }

    @Override
    public Map<String, AgentContext> agents() {
        return exec.agents();
    }

    // ── ToolContext 专属槽位 ──────────────────────────────────

    @Override
    public String agentId() {
        return agentId;
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
    public ShellExecutor shellExecutor() {
        // 不带 rg 的执行器(rgBinDir=null),服务外部插件。
        // 插件如需注入自带工具(如 rg)到 PATH，自行包装 ShellExecutor 实现。
        // DIRECT 后端的 rg 注入由 DirectShellToolProvider 自行创建带 rgBinDir 的
        // CommandExecutor 承担，不经此接口。
        if (osSandbox == null) {
            return null;
        }
        CommandExecutor cmd = new CommandExecutor(osSandbox, this, gate, agentId);
        return cmd::execute;
    }

    /**
     * 命令授权门禁:后端自建命令执行器的插件在 spawn 前调用,行为与 worker 内置
     * {@link CommandExecutor} 的门禁一致(危险动词 + 越界路径逐项授权;拒绝抛异常回灌模型)。
     *
     * <p>授权通过后 {@code GrantRegistry} 会把授权范围下发给沙箱(§7.8),
     * 故插件无需自行落地权限。
     */
    @Override
    public dev.everyagent.plugin.api.spi.CommandGate commandGate() {
        return command -> {
            try {
                gate.requireCommand(this, agentId, command);
            } catch (java.io.IOException e) {
                throw new RuntimeException("execute: 授权检查失败 " + e.getMessage(), e);
            }
        };
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
