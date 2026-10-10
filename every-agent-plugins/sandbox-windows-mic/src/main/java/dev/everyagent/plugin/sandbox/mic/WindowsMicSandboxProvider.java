package dev.everyagent.plugin.sandbox.mic;

import dev.everyagent.plugin.api.config.WorkerConfig;
import dev.everyagent.plugin.api.spi.SandboxBackend;
import dev.everyagent.plugin.api.spi.SandboxProvider;
import dev.everyagent.plugin.api.spi.SandboxProvider.SandboxConfig;

import java.util.Locale;

/**
 * windows-mic 后端提供者。
 *
 * <p>id="windows-mic"，isAvailable 仅 Windows 返回 true，
 * priority=5（auto 模式下最低优先级，作为 wsl 系列不可用时的兜底）。
 */
public final class WindowsMicSandboxProvider implements SandboxProvider {

    private static final boolean WINDOWS =
            System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");

    private final WorkerConfig props;

    public WindowsMicSandboxProvider(WorkerConfig props) {
        this.props = props;
    }

    @Override
    public String id() {
        return "windows-mic";
    }

    @Override
    public boolean isAvailable() {
        return WINDOWS;
    }

    @Override
    public int priority() {
        return 5;
    }

    @Override
    public SandboxBackend create(SandboxConfig config) {
        return new WindowsMicSandboxBackend(props);
    }
}
