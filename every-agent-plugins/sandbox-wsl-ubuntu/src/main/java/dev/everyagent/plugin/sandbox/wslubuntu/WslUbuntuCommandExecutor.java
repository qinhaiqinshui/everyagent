package dev.everyagent.plugin.sandbox.wslubuntu;

import dev.everyagent.plugin.api.config.WorkerConfig;
import dev.everyagent.plugin.api.spi.WorkspaceManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

/**
 * wsl-ubuntu 沙箱自己的命令执行器。
 *
 * <p>直接通过 {@code wsl -d xxx} 构造进程执行命令，委托 {@link WslUbuntuSandbox#run}
 * 完成实际执行（发行版内 root 直连、runner 脚本 trusted 阶段挂载 + seccomp + exec bash）。
 *
 * <p>网络许可两级取交集：worker 全局 {@code sandbox.allow-network} ∧ 本任务未开
 * /禁用网络（{@link NetworkTaskFlag}，每条命令执行瞬间实时读取）。 deny 由 runner 在
 * trusted 阶段 {@code unshare -n} 新建无 eth0 的 netns 硬落地（DNS/回环全断）。
 */
public class WslUbuntuCommandExecutor {

    private static final Logger log = LoggerFactory.getLogger(WslUbuntuCommandExecutor.class);

    /** 单流输出字符上限（与 DirectSpawnSupport.MAX_OUTPUT_CHARS 一致）。 */
    static final int MAX_OUTPUT_CHARS = 1_000_000;

    private final WorkerConfig props;
    private final Path workspaceRoot;
    private final WorkspaceManager workspaces;
    private final Path pluginDir;
    /** 任务级禁网开关读取器(可空 = 无任务上下文,恒放行)。 */
    private final BooleanSupplier networkBlocked;
    private final ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();

    public WslUbuntuCommandExecutor(WorkerConfig props, Path workspaceRoot,
            WorkspaceManager workspaces, Path pluginDir, BooleanSupplier networkBlocked) {
        this.props = props;
        this.workspaceRoot = workspaceRoot;
        this.workspaces = workspaces;
        this.pluginDir = pluginDir;
        this.networkBlocked = networkBlocked;
    }

    /**
     * 执行命令：经 {@link WslUbuntuSandbox#run} 在发行版内以 root 直连执行，
     * 结果格式化为 stdout + [stderr] + exit code 尾注。
     */
    public String execute(String command, String shell) {
        if (command == null || command.trim().isEmpty()) {
            return "execute: command 不能为空";
        }

        List<Path> allWorkspaces = wslDirectMountRoots();
        // 全局放行 ∧ 本任务未选 /禁用网络 → 放行;否则 deny(runner 内 unshare -n 真断网)
        boolean allowNetwork = props.sandbox().allowNetwork()
                && (networkBlocked == null || !networkBlocked.getAsBoolean());

        WslCommon.OsResult r = WslUbuntuSandbox.run(command, workspaceRoot, props, pluginDir,
                exec, MAX_OUTPUT_CHARS, allWorkspaces, allowNetwork);

        log.info("[exec] wsl-ubuntu rc={} aborted={} cmd={}", r.exitCode(), r.aborted(),
                truncate(command, 200));
        return format(r);
    }

    /** wsl-ubuntu 挂载列表。 */
    private List<Path> wslDirectMountRoots() {
        if (workspaces == null) {
            return List.of();
        }
        try {
            return workspaces.pruneStaleAndListMountRoots();
        } catch (RuntimeException e) {
            log.warn("[sandbox] 清理失效挂载源失败,按存活子集降级: {}", e.getMessage());
            return List.of();
        }
    }

    /** stdout / [stderr] / 超时 / exit code 尾注的格式化。 */
    private static String format(WslCommon.OsResult r) {
        StringBuilder sb = new StringBuilder();
        if (r.stdout() != null && !r.stdout().isEmpty()) {
            sb.append(r.stdout());
        }
        if (r.stderr() != null && !r.stderr().isEmpty()) {
            sb.append(sb.isEmpty() ? "" : "\n").append("[stderr]\n").append(r.stderr());
        }
        if (r.aborted()) {
            sb.append(sb.isEmpty() ? "" : "\n").append("[命令被沙箱超时中止]");
        }
        if (r.exitCode() != 0) {
            sb.append(sb.isEmpty() ? "" : "\n").append("[exit code: ").append(r.exitCode()).append("]");
        }
        return sb.toString();
    }

    private static String truncate(String s, int n) {
        return s == null ? "" : (s.length() <= n ? s : s.substring(0, n) + "...");
    }
}
