package dev.everyagent.worker.os.wsl;

import dev.everyagent.worker.config.WorkerProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 外部授权根的级联 umount 收口(工作区删除时由 WorkspaceManager 调用,best-effort)。
 *
 * <p>独立小组件而非挂进 OsSandbox:OsSandbox 构造注入了 WorkspaceManager,若
 * WorkspaceManager 反向依赖 OsSandbox 会构成构造循环。后端判定按配置自行归一
 * (归一逻辑对齐 {@code OsSandbox.normalizeBackend},但不重复其探测):仅 wsl 系
 * (auto/空 = Windows 默认 wsl-direct,wsl-direct/wsl-bwrap 及别名)执行;
 * windows-mic/none 不做宿主挂载,直接跳过。wsl.exe 不存在/发行版缺失时命令自然失败,
 * best-effort 语义天然兜底,不做前置探测。
 *
 * <p>卸载语义:挂载点 = {@link WslPathMapper#toDirectMount}(C:\a\b → /c/a/b);
 * 命令 = {@code wsl.exe -d <发行版> -u root -e umount <挂载点>}(发行版参考
 * {@link WslBwrapSandbox#effectiveDistro});失败重试 lazy({@code umount -l}),
 * 仍失败仅 WARN——<b>绝不抛出、绝不阻塞工作区删除流程</b>;单命令短超时防 wsl.exe 挂死。
 */
@Component
public class WslUmounter {

    private static final Logger log = LoggerFactory.getLogger(WslUmounter.class);

    /** 单命令短超时:umount 应亚秒完成,5s 兜底防 wsl.exe 卡死阻塞删除流程。 */
    static final long UMOUNT_TIMEOUT_MS = 5_000;

    private final WorkerProperties props;
    private final CommandRunner runner;

    /**
     * wsl.exe 命令执行器(返回退出码;超时/启动失败返回非 0)。
     * 独立接口便于测试注入伪造,隔离真实 wsl.exe 调用。
     */
    public interface CommandRunner {
        int run(List<String> argv, long timeoutMs);
    }

    @Autowired
    public WslUmounter(WorkerProperties props) {
        this(props, WslUmounter::runProcess);
    }

    public WslUmounter(WorkerProperties props, CommandRunner runner) {
        this.props = props;
        this.runner = runner;
    }

    /**
     * best-effort 卸载一个外部授权根(realpath 规范化后的 Windows 原生绝对路径)。
     * 任何失败只记日志:非 wsl 系后端/路径不可映射直接跳过;umount 失败重试 lazy;
     * 仍失败 WARN。绝不抛出——调用方(工作区删除级联)不因卸载失败受阻。
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
            String distro = WslBwrapSandbox.effectiveDistro(props);
            int rc = runner.run(
                    WslBwrapSandbox.wslCmd(distro, "-u", "root", "-e", "umount", mount), UMOUNT_TIMEOUT_MS);
            if (rc == 0) {
                log.info("[umount] 外部授权根已卸载: {} → {}", externalRoot, mount);
                return;
            }
            rc = runner.run(
                    WslBwrapSandbox.wslCmd(distro, "-u", "root", "-e", "umount", "-l", mount), UMOUNT_TIMEOUT_MS);
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
     * 配置层后端判定(归一对齐 OsSandbox.normalizeBackend,不重复探测):沙箱启用且
     * type 归一后为 wsl 系(含 auto/空——Windows 默认 wsl-direct)即需要级联 umount;
     * windows-mic/none 不做宿主挂载,直接跳过。
     */
    boolean wslConfigured() {
        WorkerProperties.Sandbox cfg = props.getSandbox();
        if (!cfg.isEnabled()) {
            return false;
        }
        return switch (normalizeType(cfg.getType())) {
            case "wsl-direct", "wsl-bwrap", "auto" -> true;
            default -> false;
        };
    }

    /** type 归一:别名/大小写/空值/未知 → wsl-direct|wsl-bwrap|windows-mic|none|auto(未知按 auto)。 */
    static String normalizeType(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "wsl-bwrap", "wsl", "bwrap" -> "wsl-bwrap";
            case "wsl-direct", "direct" -> "wsl-direct";
            case "windows-mic", "acl", "mic" -> "windows-mic";
            case "none" -> "none";
            default -> "auto";
        };
    }

    /**
     * 真实执行(默认 runner):合并输出并丢弃(防管道写满阻塞),超时强杀。
     * 出口约定对齐 {@code WslBwrapSandbox.runCapture}:超时 -1、启动失败 -127。
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
