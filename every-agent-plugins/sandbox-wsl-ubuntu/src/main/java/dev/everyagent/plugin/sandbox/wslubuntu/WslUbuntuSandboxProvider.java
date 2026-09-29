package dev.everyagent.plugin.sandbox.wslubuntu;

import dev.everyagent.plugin.api.spi.SandboxBackend;
import dev.everyagent.plugin.api.spi.SandboxProvider;
import dev.everyagent.plugin.api.spi.SandboxProvider.SandboxConfig;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.modules.WorkspaceManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * wsl-ubuntu 后端提供者。
 *
 * <p>id="wsl-ubuntu"，isAvailable 调用 {@link WslUbuntuSandbox#probe}，
 * priority=10（auto 模式下低于 wsl-bwrap，高于 windows-mic）。
 *
 * <p>当 probe 返回 {@link WslCommon.Cause#DISTRO_NOT_FOUND} 时，
 * 自动触发 {@link WslCommon#autoImport}（托管镜像自动导入），
 * 导入成功后重新探测。
 */
public final class WslUbuntuSandboxProvider implements SandboxProvider {

    private static final Logger log = LoggerFactory.getLogger(WslUbuntuSandboxProvider.class);

    private final WorkerProperties props;
    private final WorkspaceManager workspaces;
    private final Path pluginDir;
    private volatile Boolean available;

    public WslUbuntuSandboxProvider(WorkerProperties props, WorkspaceManager workspaces,
            Path pluginDir) {
        this.props = props;
        this.workspaces = workspaces;
        this.pluginDir = pluginDir;
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
            available = doProbe();
            return available;
        }
    }

    private boolean doProbe() {
        WslCommon.ProbeResult pr = WslUbuntuSandbox.probe(props, pluginDir);
        if (pr.ok()) {
            return true;
        }
        if (pr.cause() == WslCommon.Cause.DISTRO_NOT_FOUND) {
            // L2 自举：发行版缺失且托管镜像在位 → 自动 wsl --import 后重探
            log.info("[sandbox] wsl-ubuntu 发行版缺失，尝试自动导入: {}", pr.brief());
            pr = WslCommon.autoImport(props, pluginDir, pr,
                    () -> WslUbuntuSandbox.probe(props, pluginDir));
            if (pr.ok()) {
                log.info("[sandbox] wsl-ubuntu 自动导入成功，后端可用");
                return true;
            }
            log.error("[sandbox] wsl-ubuntu 自动导入失败: {} 修正: {}", pr.brief(), pr.fix());
            return false;
        }
        log.error("[sandbox] wsl-ubuntu 探测失败: {} 修正: {}", pr.brief(), pr.fix());
        return false;
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public SandboxBackend create(SandboxConfig config) {
        return new WslUbuntuSandboxBackend((WorkerProperties) config.props(), workspaces, pluginDir);
    }
}
