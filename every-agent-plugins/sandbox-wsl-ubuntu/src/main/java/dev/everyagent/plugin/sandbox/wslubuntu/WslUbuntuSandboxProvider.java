package dev.everyagent.plugin.sandbox.wslubuntu;

import dev.everyagent.plugin.api.spi.SandboxBackend;
import dev.everyagent.plugin.api.spi.SandboxProvider;
import dev.everyagent.plugin.api.spi.SandboxProvider.SandboxConfig;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.modules.WorkspaceManager;

/**
 * wsl-ubuntu 后端提供者。
 *
 * <p>id="wsl-ubuntu"，isAvailable 调用 {@link WslUbuntuSandbox#probe}，
 * priority=10（auto 模式下低于 wsl-bwrap，高于 windows-mic）。
 */
public final class WslUbuntuSandboxProvider implements SandboxProvider {

    private final WorkerProperties props;
    private final WorkspaceManager workspaces;
    private volatile Boolean available;

    public WslUbuntuSandboxProvider(WorkerProperties props, WorkspaceManager workspaces) {
        this.props = props;
        this.workspaces = workspaces;
    }

    @Override
    public String id() {
        return "wsl-ubuntu";
    }

    @Override
    public boolean isAvailable() {
        if (available != null) {
            return available;
        }
        synchronized (this) {
            if (available != null) {
                return available;
            }
            available = WslUbuntuSandbox.probe(props).ok();
            return available;
        }
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public SandboxBackend create(SandboxConfig config) {
        return new WslUbuntuSandboxBackend((WorkerProperties) config.props(), workspaces);
    }
}
