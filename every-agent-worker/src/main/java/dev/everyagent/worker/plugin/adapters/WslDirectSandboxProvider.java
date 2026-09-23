package dev.everyagent.worker.plugin.adapters;

import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.modules.WorkspaceManager;
import dev.everyagent.worker.os.wsl.WslBwrapSandbox;
import dev.everyagent.worker.os.wsl.WslDirectSandbox;
import dev.everyagent.worker.plugin.spi.SandboxBackend;
import dev.everyagent.worker.plugin.spi.SandboxProvider;

/**
 * wsl-direct 后端提供者。
 *
 * <p>id="wsl-direct"，isAvailable 调用 {@link WslDirectSandbox#probe}，
 * priority=10（auto 模式下低于 wsl-bwrap，高于 windows-mic）。
 */
public final class WslDirectSandboxProvider implements SandboxProvider {

    private final WorkerProperties props;
    private final WorkspaceManager workspaces;
    private volatile Boolean available;

    public WslDirectSandboxProvider(WorkerProperties props, WorkspaceManager workspaces) {
        this.props = props;
        this.workspaces = workspaces;
    }

    @Override
    public String id() {
        return "wsl-direct";
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
            available = WslDirectSandbox.probe(props).ok();
            return available;
        }
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public SandboxBackend create(SandboxConfig config) {
        return new WslDirectSandboxBackend(config.props(), workspaces);
    }
}
