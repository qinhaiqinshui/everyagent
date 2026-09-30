package dev.everyagent.plugin.sandbox.wslubuntu;

import dev.everyagent.plugin.api.config.WorkerConfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 外部授权根的级联 umount 收口（工作区删除时调用，best-effort）。
 *
 * <p>卸载语义：挂载点 = {@link WslPathMapper#toDirectMount}（C:\a\b → /c/a/b）；
 * 命令 = {@code wsl.exe -d <发行版> -u root -e umount <挂载点>}；
 * 失败重试 lazy（{@code umount -l}），仍失败仅 WARN——
 * <b>绝不抛出、绝不阻塞工作区删除流程</b>；单命令短超时防 wsl.exe 挂死。
 */
public class WslUmounter {

    private static final Logger log = LoggerFactory.getLogger(WslUmounter.class);

    /** 单命令短超时：umount 应亚秒完成,5s 兜底防 wsl.exe 卡死阻塞删除流程。 */
    static final long UMOUNT_TIMEOUT_MS = 5_000;

    private final WorkerConfig props;
    private final Path pluginDir;

    public WslUmounter(WorkerConfig props, Path pluginDir) {
        this.props = props;
        this.pluginDir = pluginDir;
    }

    /**
     * best-effort 卸载一个外部授权根。任何失败只记日志：
     * 路径不可映射直接跳过；umount 失败重试 lazy；仍失败 WARN。
     * 绝不抛出——调用方不因卸载失败受阻。
     */
    public void umountQuietly(Path externalRoot) {
        try {
            if (!wslConfigured()) {
                return;
            }
            String mount = WslPathMapper.toDirectMount(externalRoot);
            if (mount == null) {
                log.debug("[umount] 外部授权根非本地盘路径,跳过卸载: {}", externalRoot);
                return;
            }
            String distro = WslCommon.effectiveDistro(props, pluginDir);
            int rc = runProcess(
                    WslCommon.wslCmd(distro, "-u", "root", "-e", "umount", mount), UMOUNT_TIMEOUT_MS);
            if (rc == 0) {
                log.info("[umount] 外部授权根已卸载: {} → {}", externalRoot, mount);
                return;
            }
            rc = runProcess(
                    WslCommon.wslCmd(distro, "-u", "root", "-e", "umount", "-l", mount), UMOUNT_TIMEOUT_MS);
            if (rc == 0) {
                log.info("[umount] 外部授权根已 lazy 卸载: {} → {}", externalRoot, mount);
                return;
            }
            log.warn("[umount] 外部授权根卸载失败(best-effort,不影响工作区删除): {} → {},rc={}",
                    externalRoot, mount, rc);
        } catch (RuntimeException e) {
            log.warn("[umount] 外部授权根卸载异常(best-effort,不影响工作区删除): {} - {}",
                    externalRoot, e.getMessage());
        }
    }

    /**
     * 配置层后端判定：沙箱启用且 type 归一后为 wsl 系即需要级联 umount。
     */
    boolean wslConfigured() {
        WorkerConfig.Sandbox cfg = props.sandbox();
        if (!cfg.enabled()) {
            return false;
        }
        return switch (normalizeType(cfg.type())) {
            case "wsl-ubuntu", "wsl-direct", "wsl-bwrap", "auto" -> true;
            default -> false;
        };
    }

    /** type 归一。 */
    static String normalizeType(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "wsl-bwrap", "wsl", "bwrap" -> "wsl-bwrap";
            case "wsl-direct", "wsl-ubuntu", "direct" -> "wsl-ubuntu";
            case "windows-mic", "acl", "mic" -> "windows-mic";
            case "none" -> "none";
            default -> "auto";
        };
    }

    /**
     * 真实执行：合并输出并丢弃（防管道写满阻塞），超时强杀。
     * 出口约定：超时 -1、启动失败 -127。
     */
    static int runProcess(List<String> argv, long timeoutMs) {
        try {
            ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(true);
            pb.environment().put("WSL_UTF8", "1");
            Process p = pb.start();
            Thread reader = Thread.ofVirtual().start(() -> {
                try (InputStream in = p.getInputStream()) {
                    in.transferTo(OutputStream.nullOutputStream());
                } catch (IOException ignored) {
                    // 进程被杀/管道断:读到多少算多少
                }
            });
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return -1;
            }
            try {
                reader.join(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return p.exitValue();
        } catch (IOException e) {
            return -127;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -127;
        }
    }
}
