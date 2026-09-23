package dev.everyagent.worker.plugin.adapters;

import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.os.wsl.WslBwrapSandbox;
import dev.everyagent.worker.plugin.spi.SandboxBackend;
import dev.everyagent.worker.plugin.spi.SandboxProvider;

/**
 * wsl-bwrap 后端提供者。
 *
 * <p>id="wsl-bwrap"，isAvailable 调用 {@link WslBwrapSandbox#probe}，
 * priority=20（auto 模式下最高优先级）。
 */
public final class WslBwrapSandboxProvider implements SandboxProvider {

    private final WorkerProperties props;
    private volatile Boolean available;

    public WslBwrapSandboxProvider(WorkerProperties props) {
        this.props = props;
    }

    @Override
    public String id() {
        return "wsl-bwrap";
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
            available = WslBwrapSandbox.probe(props).ok();
            return available;
        }
    }

    @Override
    public int priority() {
        return 20;
    }

    @Override
    public SandboxBackend create(SandboxConfig config) {
        return new WslBwrapSandboxBackend(config.props());
    }
}
