package dev.everyagent.plugin.sandbox.wslubuntu;

import dev.everyagent.plugin.api.config.WorkerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
 * wsl-ubuntu 后端：命令在沙箱发行版内以 root 完整权限直接执行，不做 bwrap 隔离。
 *
 * <p>产品语义（与 wsl-bwrap 相对）：发行版整体即「可丢弃的 AI 专用环境」——AI 拥有
 * 发行版内完整权限（root，可安装软件、改系统配置）；发行版 rootfs 由 WSL2 VHDX 持久化，
 * 「本次命令装的环境下次仍可用」，无需 bwrap 的 persistent-state 桥接。宿主 Windows
 * 盘的隔离由发行版 {@code /etc/wsl.conf [automount] enabled=false} 关闭自动挂载实现，
 * 仅手动 {@code mount -t drvfs} 工作区到原路径挂载点 {@code /c/a/foo}（{@link
 * WslPathMapper#toDirectMount}），AI 在发行版内默认只可见挂载进去的工作区。
 *
 * <p>执行形态：{@code wsl.exe -d <发行版> -u root -e bash -lc "<ensureMount> && cd <挂载点> && <命令>"}。
 *
 * <p>镜像与启动器脚本由插件自己管理（{@code <pluginDir>/wsl/}），自动导入也由插件承担。
 */
public final class WslUbuntuSandbox {

    private static final Logger log = LoggerFactory.getLogger(WslUbuntuSandbox.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 探测超时（冷启动 VM 首次 wsl.exe 调用可能数秒）。 */
    private static final long PROBE_TIMEOUT_MS = 30_000;
    /** 击杀命令自身的完成等待（防泄漏 wsl.exe 进程，尽力而为）。 */
    private static final long KILL_TIMEOUT_MS = 10_000;
    /** 发行版内 runner 落地路径（automount=false 时 /mnt/c 不可达，须落到发行版内持久位置）。 */
    private static final String RUNNER_IN_DISTRO = "/root/.eagent/eagent-run.py";
    /** 已落地 runner 的内容字节（内容变化才重新落地）；null = 未落地。 */
    private static volatile byte[] landedRunner;
    /** runCapture 出口约定：-1 = 超时，-127 = 启动失败。 */
    private static final int RC_TIMEOUT = -1;
    private static final int RC_LAUNCH_FAIL = -127;

    private WslUbuntuSandbox() {
    }

    /**
     * 后端可用性探测：目标发行版可用 + 可 root 进入 + bash/mount/findmnt 在位。
     * 不要求 bwrap（直连模式不建隔离命名空间）。发行版缺失 → DISTRO_NOT_FOUND，
     * 供上层触发托管镜像自动导入（autoImport）。
     */
    public static WslCommon.ProbeResult probe(WorkerConfig props, Path pluginDir) {
        String distro = WslCommon.effectiveDistro(props, pluginDir);
        String cmd = "command -v bash >/dev/null && command -v mount >/dev/null"
                + " && command -v findmnt >/dev/null && id -u";
        Capture c = runCapture(WslCommon.wslCmd(distro, "-u", "root", "-e", "/bin/sh", "-c", cmd),
                PROBE_TIMEOUT_MS);
        boolean ok = probeOutputOk(c.rc(), c.out());
        if (ok) {
            return new WslCommon.ProbeResult(true, null, "");
        }
        String full = WslCommon.ascii(c.diagText());
        String brief = WslCommon.truncate(full.isEmpty() ? "(无输出)" : full, 400);
        if (c.rc() != 0 && WslCommon.isDistroNotFound(full)) {
            return new WslCommon.ProbeResult(false, WslCommon.Cause.DISTRO_NOT_FOUND, brief);
        }
        if (c.rc() != 0 && WslCommon.isAccessDenied(full)) {
            return new WslCommon.ProbeResult(false, WslCommon.Cause.ACCESS_DENIED, brief);
        }
        String briefMsg = "wsl-ubuntu 探测失败(dist=" + WslCommon.distroLabel(distro) + ",rc=" + c.rc()
                + "): " + brief;
        return new WslCommon.ProbeResult(false, WslCommon.Cause.UNKNOWN, briefMsg);
    }

    /**
     * 探测成功判定：rc==0 且 stdout 为纯 "0"（id -u 输出）。
     * <b>只看 stdout</b>——wsl.exe 的无害提示走 stderr。
     */
    static boolean probeOutputOk(int rc, String stdout) {
        return rc == 0 && "0".equals(WslCommon.ascii(stdout).strip());
    }

    /**
     * 在发行版内以 root 直连执行命令（经 eagent-run.py direct 模式：trusted 阶段挂载
     * 工作区 + 装 seccomp deny mount + exec bash）。
     *
     * @param allWorkspaces 全部已注册工作区（Windows 宿主路径），由 runner trusted 阶段幂等挂载
     */
    public static WslCommon.OsResult run(String command, Path cwd, WorkerConfig props,
            Path pluginDir, ExecutorService exec, int maxOut, List<Path> allWorkspaces,
            boolean allowNetwork) {
        WorkerConfig.Sandbox cfg = props.sandbox();
        String distro = WslCommon.effectiveDistro(props, pluginDir);

        String cwdMount = WslPathMapper.toDirectMount(cwd);
        if (cwdMount == null) {
            return new WslCommon.OsResult("", "[sandbox] 工作区不在本地盘(UNC/相对路径),"
                    + "无法映射进发行版: " + cwd, 1, false);
        }

        String runId = "d" + Long.toUnsignedString(System.nanoTime(), 36);
        long timeoutSec = cfg.timeoutMs() / 1000 + 60;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mode", "direct");
        payload.put("runId", runId);
        payload.put("command", command);
        payload.put("cwd", cwdMount);
        payload.put("workspaces", mountPairs(allWorkspaces, cwd, props.resolveSkillsDir()));
        payload.put("network", allowNetwork ? "open" : "deny");
        payload.put("limits", Map.of(
                "memMb", Math.max(0, cfg.resolveMemoryLimitMb()),
                "cpuSec", timeoutSec));

        String runnerInDistro = ensureRunnerInDistro(distro, props, pluginDir, exec);
        if (runnerInDistro == null) {
            return new WslCommon.OsResult("", "[sandbox] eagent-run 无法落地进发行版"
                    + "(automount 关闭下 /mnt/c 不可达,且发行版内 /root 不可写):"
                    + "请确认发行版可 root 进入", 1, false);
        }

        List<String> cmd = WslCommon.wslCmd(distro, "-u", "root", "-e", "python3", runnerInDistro);
        // p 提升到 try 外声明:InterruptedException 收尾路径(catch 块)需引用它收割进程;
        // pb.start() 尚未执行就被中断时为 null,收割前判空
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.environment().remove("WSLENV");
            pb.environment().put("WSL_UTF8", "1");
            Process proc = pb.start();
            p = proc;
            exec.submit(() -> {
                try (OutputStream os = proc.getOutputStream()) {
                    os.write(JSON.writeValueAsBytes(payload));
                    os.flush();
                } catch (IOException e) {
                    // 子进程先退/管道断:写入失败无害,读取侧自然收尾
                }
            });
            Future<String> out = exec.submit(() -> drain(proc.getInputStream()));
            Future<String> err = exec.submit(() -> drain(proc.getErrorStream()));
            boolean aborted = false;
            String outText;
            String errText;
            try {
                outText = out.get(cfg.timeoutMs(), TimeUnit.MILLISECONDS);
                errText = awaitQuiet(err);
            } catch (TimeoutException e) {
                aborted = true;
                proc.destroyForcibly();
                killGroup(distro, runId, exec);
                outText = awaitQuiet(out);
                errText = awaitQuiet(err) + "\n[exec 超时中止: >" + cfg.timeoutMs() + "ms]";
            } catch (ExecutionException e) {
                // 读流任务异常(罕见):wsl.exe 可能仍在运行,防御性收割,
                // 防止发行版内进程组(bash/mvn 等)泄漏为孤儿
                outText = "";
                errText = awaitQuiet(err);
                proc.destroyForcibly();
                killGroup(distro, runId, exec);
            }
            int code = aborted ? -1 : proc.waitFor();
            return new WslCommon.OsResult(WslCommon.capOutput(outText, maxOut),
                    WslCommon.capOutput(errText, maxOut), code, aborted);
        } catch (IOException e) {
            return new WslCommon.OsResult("", "exec 启动失败(wsl.exe / 发行版 "
                    + WslCommon.distroLabel(distro) + "): " + e.getMessage(), 1, false);
        } catch (InterruptedException e) {
            // 任务停止/取消(TaskManager 对运行 future 调 cancel(true))会中断工具执行线程,
            // 阻塞在 out.get() 的等待抛 InterruptedException 到达这里:必须与超时路径对齐收割
            // wsl.exe + 发行版内进程组(killGroup 按 runner 登记的 pgid pkill -9),
            // 否则发行版内 bash/mvn 等前台命令全部泄漏为孤儿——
            // 实测孤儿 mvn 持续占用 CPU 并锁住 target 目录,还会破坏后续构建(clean 删一半)。
            // killGroup 幂等无害:若命令已自然结束/pgid 未登记,pkill 找不到目标即空操作。
            Thread.currentThread().interrupt();
            if (p != null) {
                p.destroyForcibly();
            }
            killGroup(distro, runId, exec);
            return new WslCommon.OsResult("", "exec 被中断", 1, false);
        }
    }

    /**
     * 确保 eagent-run.py 已落地到发行版内，返回发行版内执行路径；失败返回 null。
     */
    private static String ensureRunnerInDistro(String distro, WorkerConfig props,
            Path pluginDir, ExecutorService exec) {
        byte[] bytes;
        try {
            bytes = WslCommon.runnerBytes(props, pluginDir);
        } catch (IOException e) {
            log.warn("[sandbox] 读取 eagent-run 失败: {}", e.getMessage());
            return null;
        }
        byte[] landed = landedRunner;
        if (landed != null && java.util.Arrays.equals(landed, bytes)) {
            return RUNNER_IN_DISTRO;
        }
        List<String> cmd = WslCommon.wslCmd(distro, "-u", "root", "-e", "/bin/sh", "-c",
                "mkdir -p /root/.eagent && cat > " + RUNNER_IN_DISTRO);
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
            pb.environment().put("WSL_UTF8", "1");
            Process p = pb.start();
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

    /**
     * 全部已注册工作区 + 当前工作区 + 系统技能目录的挂载对。
     */
    static List<Map<String, String>> mountPairs(List<Path> allWorkspaces, Path cwd, Path skillsDir) {
        Set<Path> roots = new LinkedHashSet<>();
        if (cwd != null) {
            roots.add(cwd);
        }
        if (allWorkspaces != null) {
            roots.addAll(allWorkspaces);
        }
        List<Map<String, String>> pairs = new ArrayList<>();
        Set<String> dests = new LinkedHashSet<>();
        for (Path ws : roots) {
            if (!Files.isDirectory(ws)) {
                log.debug("[sandbox] 跳过不存在的挂载源(已删除/未创建): {}", ws);
                continue;
            }
            String dest = WslPathMapper.toDirectMount(ws);
            if (dest != null && dests.add(dest)) {
                pairs.add(Map.of("src", ws.toString(), "dest", dest));
            }
        }
        if (skillsDir != null && Files.isDirectory(skillsDir)) {
            String dest = WslPathMapper.toDirectMount(skillsDir);
            if (dest != null && dests.add(dest)) {
                Map<String, String> rw = new LinkedHashMap<>();
                rw.put("src", skillsDir.toString());
                rw.put("dest", dest);
                pairs.add(rw);
            }
        }
        return pairs;
    }

    /** 显式收割发行版内进程组：pkill -9 -g <pgid> 并清理登记文件。 */
    private static void killGroup(String distro, String runId, ExecutorService exec) {
        String sh = "cat /run/eagent/" + runId + ".pgid 2>/dev/null | xargs -r pkill -9 -g; "
                + "rm -f /run/eagent/" + runId + ".pgid";
        try {
            Process k = new ProcessBuilder(WslCommon.wslCmd(distro, "-e", "/bin/sh", "-c", sh))
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
            log.warn("[sandbox] wsl-ubuntu 进程组击杀命令发起失败 runId={}: {}", runId, e.getMessage());
        }
    }

    private static Capture runCapture(List<String> cmd, long timeoutMs) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(false);
            pb.environment().put("WSL_UTF8", "1");
            Process p = pb.start();
            StringBuilder outSb = new StringBuilder();
            StringBuilder errSb = new StringBuilder();
            Thread outReader = Thread.ofVirtual().start(() -> {
                try (InputStream in = p.getInputStream()) {
                    readInto(in, outSb);
                } catch (IOException ignored) {
                    // 进程被杀/管道断:读到多少算多少
                }
            });
            Thread errReader = Thread.ofVirtual().start(() -> {
                try (InputStream in = p.getErrorStream()) {
                    readInto(in, errSb);
                } catch (IOException ignored) {
                    // 进程被杀/管道断:读到多少算多少
                }
            });
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                joinQuiet(outReader, 500);
                joinQuiet(errReader, 500);
                return new Capture(RC_TIMEOUT, outSb.toString(), errSb.toString());
            }
            joinQuiet(outReader, 1000);
            joinQuiet(errReader, 1000);
            return new Capture(p.exitValue(), outSb.toString(), errSb.toString());
        } catch (IOException e) {
            return new Capture(RC_LAUNCH_FAIL, "", e.getMessage() == null ? "IOException" : e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Capture(RC_LAUNCH_FAIL, "", "interrupted");
        }
    }

    private static void readInto(InputStream in, StringBuilder sb) throws IOException {
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) >= 0 && sb.length() < 8192) {
            sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
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
        } catch (InterruptedException e) {
            // 恢复中断位:任务停止信号不得在收尾等待中被吞——
            // 丢失后 p.waitFor() 感知不到停止,泄漏运行中的 wsl.exe 与发行版内进程
            Thread.currentThread().interrupt();
            return "";
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

    private record Capture(int rc, String out, String err) {
        String diagText() {
            return WslCommon.mergeDiagnostics(out, err);
        }
    }
}
