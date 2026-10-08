package dev.everyagent.plugin.sandbox.mic;

import dev.everyagent.plugin.api.config.WorkerConfig;
import dev.everyagent.plugin.api.spi.SandboxBackend;

/**
 * windows-mic 沙箱后端（新 SPI）。
 *
 * <p>极简实现：命令跑在<b>宿主路径</b>上，故 <b>grant/revoke 与 toSandbox/toHost
 * 全部走 SPI 默认</b>（边界 no-op / 恒等翻译）——路径形态与权限都在宿主原生形态下
 * 表达，不需要挂载也没有独立翻译。
 *
 * <p>命令执行逻辑（{@link WindowsSandbox#run}）保留在本模块中，
 * 供沙箱插件自身的 CommandExecutor 调用，但不在 SandboxBackend SPI 中暴露。
 */
public final class WindowsMicSandboxBackend implements SandboxBackend {

    private final WorkerConfig props;
    private final WorkerConfig.Sandbox cfg;

    WindowsMicSandboxBackend(WorkerConfig props) {
        this.props = props;
        this.cfg = props.sandbox();
    }

    @Override
    public String id() {
        return "windows-mic";
    }

    // grant/revoke 与 toSandbox/toHost 使用 SPI 默认：边界 no-op、翻译恒等
    // （windows-mic 命令跑在宿主上，不需要挂载也没有独立路径形态）
}
