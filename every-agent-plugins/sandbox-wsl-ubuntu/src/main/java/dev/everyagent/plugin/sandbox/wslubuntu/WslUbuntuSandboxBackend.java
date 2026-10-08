package dev.everyagent.plugin.sandbox.wslubuntu;

import dev.everyagent.plugin.api.spi.SandboxBackend;
import dev.everyagent.plugin.api.config.WorkerConfig;
import dev.everyagent.plugin.api.spi.WorkspaceManager;

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
 * <p>只做两件事：把宿主路径<b>挂进发行版</b>（{@link #grant} / {@link #revoke}），
 * 以及回答「宿主路径在发行版里是什么形态」（{@link #toSandbox} / {@link #toHost}）。
 *
 * <p><b>翻译是后端自己的属性</b>：挂载点由 {@link WslPathMapper#toDirectMount} 推导，
 * 但<b>只对已授权的根及其子路径</b>翻译——未授权的路径原样返回（宿主形态），
 * 避免把「没挂进来的路径」也报成发行版内路径。反向查询无匹配返回 null，
 * 交给调用方按宿主路径处理。
 *
 * <p><b>回收</b>：{@link #revoke} 做 best-effort umount 并把根移出翻译视图——
 * 调用方只传路径，不携带任何任务 / 工作区语义。
 */
public final class WslUbuntuSandboxBackend implements SandboxBackend {

    private static final Logger log = LoggerFactory.getLogger(WslUbuntuSandboxBackend.class);

    private final WorkerConfig props;
    private final WorkspaceManager workspaces;
    private final Path pluginDir;
    private final WslUmounter umounter;

    /** 已授权根的发行版内视图（纯映射，无 IO）。 */
    private final WslPathView view = new WslPathView();

    public WslUbuntuSandboxBackend(WorkerConfig props, WorkspaceManager workspaces,
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

    // ── 效果：挂载 / 卸载 ─────────────────────────────────────

    @Override
    public void grant(List<PathGrant> grants) {
        String distro = WslCommon.effectiveDistro(props, pluginDir);
        for (PathGrant g : grants) {
            if (g == null || g.hostPath() == null) {
                continue;
            }
            Path hostPath = g.hostPath();
            String mountPoint = WslPathMapper.toDirectMount(hostPath);
            if (mountPoint == null) {
                log.debug("[grant] 非 Windows 盘路径，跳过挂载: {}", hostPath);
                continue;
            }
            ensureMount(distro, hostPath.toString(), mountPoint, g.access());
            view.add(hostPath);
        }
    }

    @Override
    public void revoke(List<Path> hostPaths) {
        for (Path h : hostPaths == null ? List.<Path>of() : hostPaths) {
            if (h == null) {
                continue;
            }
            view.remove(h);
            umounter.umountQuietly(h);
        }
    }

    // ── 查询：宿主 ↔ 发行版内 ────────────────────────────────

    @Override
    public String toSandbox(Path hostPath) {
        return view.toSandbox(hostPath);
    }

    @Override
    public Path toHost(String sandboxPath) {
        return view.toHost(sandboxPath);
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
