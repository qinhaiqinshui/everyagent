package dev.everyagent.plugin.sandbox.wslubuntu;

import dev.everyagent.plugin.api.spi.SandboxBackend;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.modules.WorkspaceManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * wsl-ubuntu 沙箱后端（新 SPI）。
 *
 * <p>只负责<strong>挂载</strong>和<strong>工作区生命周期</strong>：
 * <ul>
 *   <li>{@link #mount}：批量 drvfs 挂载宿主路径到发行版内，返回 {hostPath → sandboxPath} 映射；</li>
 *   <li>{@link #onWorkspaceRemoved}：best-effort umount 清理挂载。</li>
 * </ul>
 * 不执行命令、不翻译路径、不涉及工具注册、不涉及授权策略。
 */
public final class WslUbuntuSandboxBackend implements SandboxBackend {

    private static final Logger log = LoggerFactory.getLogger(WslUbuntuSandboxBackend.class);

    private final WorkerProperties props;
    private final WorkspaceManager workspaces;
    private final Path pluginDir;
    private final WslUmounter umounter;

    public WslUbuntuSandboxBackend(WorkerProperties props, WorkspaceManager workspaces,
            Path pluginDir) {
        this.props = props;
        this.workspaces = workspaces;
        this.pluginDir = pluginDir;
        this.umounter = new WslUmounter(props, pluginDir);
    }

    @Override
    public String id() {
        return "wsl-ubuntu";
    }

    @Override
    public Map<Path, String> mount(List<MountRequest> requests) {
        Map<Path, String> result = new LinkedHashMap<>();
        String distro = WslCommon.effectiveDistro(props, pluginDir);
        for (MountRequest req : requests) {
            Path hostPath = req.hostPath();
            String mountPoint = WslPathMapper.toDirectMount(hostPath);
            if (mountPoint != null) {
                ensureMount(distro, hostPath.toString(), mountPoint, req.access());
            }
            result.put(hostPath, mountPoint != null ? mountPoint : hostPath.toString());
        }
        return result;
    }

    @Override
    public void onWorkspaceRemoved(Path root) {
        umounter.umountQuietly(root);
    }

    /**
     * 幂等 drvfs 挂载：先 findmnt 检查是否已挂载，未挂载则 mount -t drvfs。
     * best-effort：失败仅 WARN，不阻塞。
     */
    private void ensureMount(String distro, String hostPath, String mountPoint, Access access) {
        // 先确保挂载点目录存在
        String roFlag = access == Access.READ_ONLY ? "-o ro" : "";
        String sh = "mkdir -p '" + mountPoint + "' && "
                + "findmnt --source '" + hostPath + "' --target '" + mountPoint + "' >/dev/null 2>&1"
                + " || mount -t drvfs " + roFlag + " '" + hostPath + "' '" + mountPoint + "'"
                + " 2>/dev/null || true";
        List<String> cmd = WslCommon.wslCmd(distro, "-u", "root", "-e", "/bin/sh", "-c", sh);
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
            pb.environment().put("WSL_UTF8", "1");
            Process p = pb.start();
            Thread reader = Thread.ofVirtual().start(() -> {
                try (InputStream in = p.getInputStream()) {
                    in.transferTo(OutputStream.nullOutputStream());
                } catch (IOException ignored) {
                    // 丢弃输出防管道阻塞
                }
            });
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                log.warn("[mount] drvfs 挂载超时: {} → {}", hostPath, mountPoint);
                return;
            }
            if (p.exitValue() != 0) {
                log.warn("[mount] drvfs 挂载失败(best-effort): {} → {}, rc={}",
                        hostPath, mountPoint, p.exitValue());
            }
            reader.join(1_000);
        } catch (IOException e) {
            log.warn("[mount] drvfs 挂载命令启动失败: {} → {}: {}", hostPath, mountPoint, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
