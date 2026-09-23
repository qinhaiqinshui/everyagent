package dev.everyagent.worker.plugin.adapters;

import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.modules.WorkspaceManager;
import dev.everyagent.worker.plugin.registry.SandboxProviderRegistry;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * 内置沙箱后端提供者注册器。
 *
 * <p>在 Spring 容器启动时（@PostConstruct）将 3 个内置 SandboxProvider
 * 注册到 {@link SandboxProviderRegistry}，使 OsSandbox 可经 SPI 选择后端。
 */
@Component
public class BuiltInSandboxProviders {

    private final SandboxProviderRegistry registry;
    private final WorkerProperties props;
    private final WorkspaceManager workspaces;

    public BuiltInSandboxProviders(SandboxProviderRegistry registry,
            WorkerProperties props, WorkspaceManager workspaces) {
        this.registry = registry;
        this.props = props;
        this.workspaces = workspaces;
    }

    @PostConstruct
    void register() {
        registry.register(new WslDirectSandboxProvider(props, workspaces));
        registry.register(new WslBwrapSandboxProvider(props));
        registry.register(new WindowsMicSandboxProvider());
    }
}
