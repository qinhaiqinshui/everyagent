package dev.everyagent.plugin.sandbox.mic;

import dev.everyagent.plugin.api.spi.SandboxBackend;
import dev.everyagent.worker.config.WorkerProperties;

/**
 * windows-mic 沙箱后端（新 SPI）。
 *
 * <p>极简实现：mount 返回原路径（windows-mic 命令跑在宿主上，不需要挂载），
 * onWorkspaceRemoved 为 no-op。
 *
 * <p>命令执行逻辑（{@link WindowsSandbox#run}）保留在本模块中，
 * 供沙箱插件自身的 CommandExecutor 调用，但不在 SandboxBackend SPI 中暴露。
 */
public final class WindowsMicSandboxBackend implements SandboxBackend {

    private final WorkerProperties props;
    private final WorkerProperties.Sandbox cfg;

    WindowsMicSandboxBackend(WorkerProperties props) {
        this.props = props;
        this.cfg = props.getSandbox();
    }

    @Override
    public String id() {
        return "windows-mic";
    }

    // mount() 使用默认实现：返回原路径（windows-mic 命令跑在宿主上，不需要挂载）

    // onWorkspaceRemoved() 使用默认 no-op 实现
}
