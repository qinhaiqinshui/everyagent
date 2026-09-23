package dev.everyagent.worker.plugin;

import dev.everyagent.plugin.api.WorkerServices;
import dev.everyagent.plugin.api.spi.SandboxBackend;
import dev.everyagent.worker.modules.WorkspaceManager;
import dev.everyagent.worker.os.OsSandbox;
import dev.everyagent.worker.tools.PermissionGate;
import org.springframework.stereotype.Component;

/**
 * WorkerServices 实现 —— 插件经此访问 worker 核心只读服务。
 */
@Component
public class WorkerServicesImpl implements WorkerServices {

    private final OsSandbox sandbox;
    private final PermissionGate gate;
    private final WorkspaceManager workspaces;

    public WorkerServicesImpl(OsSandbox sandbox, PermissionGate gate, WorkspaceManager workspaces) {
        this.sandbox = sandbox;
        this.gate = gate;
        this.workspaces = workspaces;
    }

    @Override
    public SandboxBackend sandbox() {
        return sandbox;
    }

    @Override
    public dev.everyagent.plugin.api.spi.PermissionGate gate() {
        return gate;
    }

    @Override
    public dev.everyagent.plugin.api.spi.WorkspaceManager workspaces() {
        return workspaces;
    }
}
