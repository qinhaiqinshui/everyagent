package dev.everyagent.worker.os.wsl;

import dev.everyagent.worker.config.WorkerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * wsl-direct 后端:命令在沙箱发行版内以 root 完整权限直接执行,不做 bwrap 隔离。
 *
 * <p>产品语义(与 wsl-bwrap 相对):发行版整体即「可丢弃的 AI 专用环境」——AI 拥有
 * 发行版内完整权限(root,可安装软件、改系统配置);发行版 rootfs 由 WSL2 VHDX 持久化,
 * 「本次命令装的环境下次仍可用」,无需 bwrap 的 persistent-state 桥接。宿主 Windows
 * 盘的隔离由发行版 {@code /etc/wsl.conf [automount] enabled=false} 关闭自动挂载实现,
 * 仅手动 {@code mount -t drvfs} 工作区到原路径挂载点 {@code /c/a/foo}({@link
 * WslPathMapper#toDirectMount}),AI 在发行版内默认只可见挂载进去的工作区。
 *
 * <p>执行形态:{@code wsl.exe -d <发行版> -u root -e bash -lc "<ensureMount> && cd <挂载点> && <命令>"}。
 * <ul>
 *   <li><b>登录 shell</b>({@code -lc}):自动加载 /etc/profile 与 ~/.profile,profile 里 export
 *       的环境变量对每条命令生效;</li>
 *   <li><b>网络开关</b>:allowNetwork=false 时命令经 {@code unshare -n} 包进无 eth0 的 netns
 *       (root 有 CAP_SYS_ADMIN,WSL2 内可建 user/net namespace);</li>
 *   <li><b>进程树击杀</b>:bash 由 wsl.exe 直接拉起即进程组组长,开头 {@code echo $$} 登记 pgid,
 *       超时后 {@code pkill -9 -g} 收割整棵进程树(wsl.exe 退出不保证杀后代);</li>
 *   <li><b>动态挂载</b>:每次命令前对全部已注册工作区幂等 ensureMount,新建工作区首条命令
 *       即自动挂载;{@code wsl --shutdown} 后挂载丢失,下次命令自愈重挂。</li>
 * </ul>
 */
public final class WslDirectSandbox {

    private static final Logger log = LoggerFactory.getLogger(WslDirectSandbox.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 探测超时(冷启动 VM 首次 wsl.exe 调用可能数秒)。 */
    private static final long PROBE_TIMEOUT_MS = 30_000;
    /** 击杀命令自身的完成等待(防泄漏 wsl.exe 进程,尽力而为)。 */
    private static final long KILL_TIMEOUT_MS = 10_000;
    /** 发行版内 runner 落地路径(automount=false 时 /mnt/c 不可达,须落到发行版内持久位置)。 */
    private static final String RUNNER_IN_DISTRO = "/root/.eagent/eagent-run.py";
    /** 已落地 runner 的内容字节(内容变化才重新落地);null = 未落地。 */
    private static volatile byte[] landedRunner;
    /** runCapture 出口约定:-1 = 超时,-127 = 启动失败(wsl.exe 不存在等)。 */
    private static final int RC_TIMEOUT = -1;
    private static final int RC_LAUNCH_FAIL = -127;

    private WslDirectSandbox() {
    }

    /**
     * 后端可用性探测:目标发行版可用 + 可 root 进入 + bash/mount/findmnt 在位。
     * 不要求 bwrap(直连模式不建隔离命名空间)。发行版缺失 → DISTRO_NOT_FOUND,
     * 供上层触发托管镜像自动导入(autoImport)。
     */
    public static WslBwrapSandbox.ProbeResult probe(WorkerProperties props) {
        String distro = WslBwrapSandbox.effectiveDistro(props);
        String cmd = "command -v bash >/dev/null && command -v mount >/dev/null"
                + " && command -v findmnt >/dev/null && id -u";
        Capture c = runCapture(WslBwrapSandbox.wslCmd(distro, "-u", "root", "-e", "/bin/sh", "-c", cmd),
                PROBE_TIMEOUT_MS);
        // wsl.exe 旧版自身消息为 UTF-16LE(字节流掺 NUL):成功判定与错误码/文案匹配前必须先
        // 剥 NUL,否则中文「不存在…」与纯 "0" 都会失配(历史 bug:发行版缺失被误判为未归类,
        // 自动导入永不触发;WSL 2.6 对不存在发行版报 WSL_E_DISTRO_NOT_FOUND 而非 E_ACCESSDENIED)
        boolean ok = c.rc() == 0 && "0".equals(WslBwrapSandbox.ascii(c.out()).strip());
        if (ok) {
            return new WslBwrapSandbox.ProbeResult(true, null, "");
        }
        String out = truncate(WslBwrapSandbox.ascii(c.out()), 400);
        if (c.rc() != 0 && WslBwrapSandbox.isDistroNotFound(c.out())) {
            return new WslBwrapSandbox.ProbeResult(false, WslBwrapSandbox.Cause.DISTRO_NOT_FOUND, out);
        }
        if (c.rc() != 0 && WslBwrapSandbox.isAccessDenied(c.out())) {
            return new WslBwrapSandbox.ProbeResult(false, WslBwrapSandbox.Cause.ACCESS_DENIED, out);
        }
        String brief = "wsl-direct 探测失败(dist=" + WslBwrapSandbox.distroLabel(distro) + ",rc=" + c.rc()
                + "): " + out;
        return new WslBwrapSandbox.ProbeResult(false, WslBwrapSandbox.Cause.UNKNOWN, brief);
    }

    /**
     * 在发行版内以 root 直连执行命令(经 eagent-run.py direct 模式:trusted 阶段挂载
     * 工作区 + 装 seccomp deny mount + exec bash)。
     *
     * @param allWorkspaces 全部已注册工作区(Windows 宿主路径),由 runner trusted 阶段幂等挂载
     */
    public static WslBwrapSandbox.OsResult run(String command, Path cwd, WorkerProperties props,
            ExecutorService exec, int maxOut, List<Path> allWorkspaces, boolean allowNetwork) {
        WorkerProperties.Sandbox cfg = props.getSandbox();
        String distro = WslBwrapSandbox.effectiveDistro(props);

        String cwdMount = WslPathMapper.toDirectMount(cwd);
        if (cwdMount == null) {
            return new WslBwrapSandbox.OsResult("", "[sandbox] 工作区不在本地盘(UNC/相对路径),"
                    + "无法映射进发行版: " + cwd, 1, false);
        }

        String runId = "d" + Long.toUnsignedString(System.nanoTime(), 36);
        long timeoutSec = cfg.getTimeoutMs() / 1000 + 60; // CPU 秒上限≥墙钟,留余量
        // 载荷:workspaces 为 [{src, dest}],runner trusted 阶段幂等挂载(seccomp 前)
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mode", "direct");
        payload.put("runId", runId);
        payload.put("command", command);
        payload.put("cwd", cwdMount);
        payload.put("workspaces", mountPairs(allWorkspaces, cwd));
        payload.put("network", allowNetwork ? "open" : "deny");
        payload.put("limits", Map.of(
                "asMb", Math.max(0, cfg.getMemoryLimitMb()),
                "cpuSec", timeoutSec));

        // runner 必须在发行版内可达:automount=false 下 /mnt/c 不存在、Windows 侧路径映射
        // 不可用,须先把 eagent-run.py 落地到发行版内持久位置(/root,rootfs VHDX 保留)
        String runnerInDistro = ensureRunnerInDistro(distro, props, exec);
        if (runnerInDistro == null) {
            return new WslBwrapSandbox.OsResult("", "[sandbox] eagent-run 无法落地进发行版"
                    + "(automount 关闭下 /mnt/c 不可达,且发行版内 /root 不可写):"
                    + "请确认发行版可 root 进入", 1, false);
        }

        List<String> cmd = WslBwrapSandbox.wslCmd(distro, "-u", "root", "-e", "python3", runnerInDistro);
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.environment().remove("WSLENV");
            pb.environment().put("WSL_UTF8", "1");
            Process p = pb.start();
            // 载荷经 stdin 写入(独立线程,防管道缓冲写满阻塞)
            exec.submit(() -> {
                try (OutputStream os = p.getOutputStream()) {
                    os.write(JSON.writeValueAsBytes(payload));
                    os.flush();
                } catch (IOException e) {
                    // 子进程先退/管道断:写入失败无害,读取侧自然收尾
                }
            });
            Future<String> out = exec.submit(() -> drain(p.getInputStream()));
            Future<String> err = exec.submit(() -> drain(p.getErrorStream()));
            boolean aborted = false;
            String outText;
            String errText;
            try {
                outText = out.get(cfg.getTimeoutMs(), TimeUnit.MILLISECONDS);
                errText = awaitQuiet(err);
            } catch (TimeoutException e) {
                aborted = true;
                p.destroyForcibly(); // 只杀 wsl.exe;Linux 侧树靠 pgid 收割
                killGroup(distro, runId, exec);
                outText = awaitQuiet(out);
                errText = awaitQuiet(err) + "\n[exec 超时中止: >" + cfg.getTimeoutMs() + "ms]";
            } catch (ExecutionException e) {
                outText = "";
                errText = awaitQuiet(err);
            }
            int code = aborted ? -1 : p.waitFor();
            return new WslBwrapSandbox.OsResult(capOutput(outText, maxOut), capOutput(errText, maxOut), code,
                    aborted);
        } catch (IOException e) {
            return new WslBwrapSandbox.OsResult("", "exec 启动失败(wsl.exe / 发行版 "
                    + WslBwrapSandbox.distroLabel(distro) + "): " + e.getMessage(), 1, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new WslBwrapSandbox.OsResult("", "exec 被中断", 1, false);
        }
    }

    /**
     * 确保 eagent-run.py 已落地到发行版内,返回发行版内执行路径;失败返回 null。
     *
     * <p>wsl-direct 后端 automount=false,发行版内无 {@code /mnt/c},Windows 侧文件经
     * {@link WslPathMapper#toWsl} 映射的路径不可达;而 rootfs 由 WSL2 VHDX 持久化,
     * {@code /root} 是可写持久位置。这里把脚本内容经 stdin {@code cat >} 写进发行版,
     * 内容未变化时(worker 侧字节对比)跳过重复落地;内容变化(worker 升级)或进程重启后
     * 自动重落。落地幂等,并发重复落地无害(cat > 覆盖写)。
     */
    private static String ensureRunnerInDistro(String distro, WorkerProperties props,
            ExecutorService exec) {
        byte[] bytes;
        try {
            bytes = WslBwrapSandbox.runnerBytes(props);
        } catch (IOException e) {
            log.warn("[sandbox] 读取 eagent-run 失败: {}", e.getMessage());
            return null;
        }
        byte[] landed = landedRunner;
        if (landed != null && java.util.Arrays.equals(landed, bytes)) {
            return RUNNER_IN_DISTRO; // 内容未变,发行版内已落地
        }
        List<String> cmd = WslBwrapSandbox.wslCmd(distro, "-u", "root", "-e", "/bin/sh", "-c",
                "mkdir -p /root/.eagent && cat > " + RUNNER_IN_DISTRO);
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
            pb.environment().put("WSL_UTF8", "1");
            Process p = pb.start();
            // stdin 写脚本内容 + stdout 读取必须并发:cat 读满管道不退出会卡死
            exec.submit(() -> {
                try (OutputStream os = p.getOutputStream()) {
                    os.write(bytes);
                    os.flush();
                } catch (IOException ignored) {
                    // 子进程先退/管道断:读取侧自然收尾
                }
            });
            StringBuilder out = new StringBuilder();
            exec.submit(() -> {
                try (InputStream in = p.getInputStream()) {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                    }
                } catch (IOException ignored) {
                    // 丢弃输出
                }
            });
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                log.warn("[sandbox] eagent-run 落地进发行版超时");
                return null;
            }
            if (p.exitValue() != 0) {
                log.warn("[sandbox] eagent-run 落地进发行版失败 rc={} out={}", p.exitValue(),
                        out.toString().trim());
                return null;
            }
            landedRunner = bytes;
            return RUNNER_IN_DISTRO;
        } catch (IOException e) {
            log.warn("[sandbox] eagent-run 落地进发行版启动失败: {}", e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** 全部已注册工作区 + 当前工作区的挂载对 [{src, dest}](Windows 源 → 原路径挂载点)。 */
    private static List<Map<String, String>> mountPairs(List<Path> allWorkspaces, Path cwd) {
        Set<Path> roots = new LinkedHashSet<>();
        if (cwd != null) {
            roots.add(cwd);
        }
        if (allWorkspaces != null) {
            roots.addAll(allWorkspaces);
        }
        List<Map<String, String>> pairs = new ArrayList<>();
        for (Path ws : roots) {
            String dest = WslPathMapper.toDirectMount(ws);
            if (dest != null) {
                pairs.add(Map.of("src", ws.toString(), "dest", dest));
            }
        }
        return pairs;
    }

    /** 显式收割发行版内进程组(与 WslBwrapSandbox 同语义):pkill -9 -g <pgid> 并清理登记文件。 */
    private static void killGroup(String distro, String runId, ExecutorService exec) {
        String sh = "cat /run/eagent/" + runId + ".pgid 2>/dev/null | xargs -r pkill -9 -g; "
                + "rm -f /run/eagent/" + runId + ".pgid";
        try {
            Process k = new ProcessBuilder(WslBwrapSandbox.wslCmd(distro, "-e", "/bin/sh", "-c", sh))
                    .redirectErrorStream(true).start();
            exec.submit(() -> {
                try (InputStream ignored = k.getInputStream()) {
                    ignored.transferTo(OutputStream.nullOutputStream());
                } catch (IOException ignored) {
                    // 丢弃输出防管道阻塞
                }
                try {
                    k.waitFor(KILL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                k.destroyForcibly();
            });
        } catch (IOException e) {
            log.warn("[sandbox] wsl-direct 进程组击杀命令发起失败 runId={}: {}", runId, e.getMessage());
        }
    }

    private static Capture runCapture(List<String> cmd, long timeoutMs) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
            pb.environment().put("WSL_UTF8", "1");
            Process p = pb.start();
            StringBuilder sb = new StringBuilder();
            Thread reader = Thread.ofVirtual().start(() -> {
                try (InputStream in = p.getInputStream()) {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) >= 0 && sb.length() < 8192) {
                        sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                    }
                } catch (IOException ignored) {
                    // 进程被杀/管道断:读到多少算多少
                }
            });
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                joinQuiet(reader, 500);
                return new Capture(RC_TIMEOUT, sb.toString());
            }
            joinQuiet(reader, 1000);
            return new Capture(p.exitValue(), sb.toString());
        } catch (IOException e) {
            return new Capture(RC_LAUNCH_FAIL, e.getMessage() == null ? "IOException" : e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Capture(RC_LAUNCH_FAIL, "interrupted");
        }
    }

    private static void joinQuiet(Thread t, long ms) {
        try {
            t.join(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String awaitQuiet(Future<String> task) {
        try {
            return task.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "";
        }
    }

    private static String drain(InputStream in) throws IOException {
        try (java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return bos.toString(StandardCharsets.UTF_8);
        }
    }

    private static String capOutput(String s, int maxOut) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        return s.length() > maxOut ? s.substring(0, maxOut) + "\n[输出已截断至 " + maxOut + " 字符]" : s;
    }

    private static String truncate(String s, int n) {
        return s == null ? "" : (s.length() <= n ? s : s.substring(0, n) + "...");
    }

    private record Capture(int rc, String out) {
    }
}
