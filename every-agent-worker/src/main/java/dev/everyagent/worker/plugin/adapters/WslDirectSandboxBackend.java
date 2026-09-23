package dev.everyagent.worker.plugin.adapters;

import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.modules.WorkspaceManager;
import dev.everyagent.worker.os.OsSandbox.ExecResult;
import dev.everyagent.worker.os.windows.WindowsSandbox;
import dev.everyagent.worker.os.wsl.WslBwrapSandbox;
import dev.everyagent.worker.os.wsl.WslDirectSandbox;
import dev.everyagent.worker.plugin.spi.SandboxBackend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * wsl-direct 沙箱后端适配器。
 *
 * <p>委托 {@link WslDirectSandbox#run} 执行命令：发行版内 root 完整权限直连，
 * automount 关闭 + 手动挂载工作区，命令方言 bash。
 */
public final class WslDirectSandboxBackend implements SandboxBackend {

    private static final Logger log = LoggerFactory.getLogger(WslDirectSandboxBackend.class);

    private final WorkerProperties props;
    private final WorkerProperties.Sandbox cfg;
    private final WorkspaceManager workspaces;
    private final ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
    private final ExecutorService drainExec = Executors.newFixedThreadPool(4,
            r -> { Thread t = new Thread(r, "sandbox-drain"); t.setDaemon(true); return t; });

    WslDirectSandboxBackend(WorkerProperties props, WorkspaceManager workspaces) {
        this.props = props;
        this.cfg = props.getSandbox();
        this.workspaces = workspaces;
    }

    @Override
    public String id() {
        return "wsl-direct";
    }

    @Override
    public ExecResult spawnSandboxed(String command, Path cwd, Map<String, String> extraEnv,
            String shell, List<Path> extraRoots, boolean allowNetwork, boolean allowPrivilege) {
        // wsl-direct 忽略 extraEnv/shell/extraRoots/allowPrivilege（与原 OsSandbox 行为一致：
        // 命令方言恒为 bash，发行版内 root 直连，工作区经 runner trusted 阶段挂载）
        WslBwrapSandbox.OsResult r = WslDirectSandbox.run(command, cwd, props, exec,
                DirectSpawnSupport.MAX_OUTPUT_CHARS, wslDirectMountRoots(), allowNetwork);
        return new ExecResult(r.stdout(), r.stderr(), r.exitCode(), r.aborted());
    }

    @Override
    public ExecResult spawnSandboxedWindows(String command, Path cwd, Map<String, String> extraEnv,
            String shell, List<Path> extraRoots, boolean allowNetwork, boolean allowPrivilege) {
        // 强制以 Windows 原生沙箱执行（供 WSL 后端下 powershell 工具使用）
        return WindowsSandbox.run(command, cwd, extraEnv, cfg, exec, drainExec,
                DirectSpawnSupport.MAX_OUTPUT_CHARS, shell, allowNetwork, allowPrivilege);
    }

    @Override
    public ExecResult spawnNative(String[] argv, Path cwd, Map<String, String> env, long timeoutMs) {
        return DirectSpawnSupport.runDirect(List.of(argv), cwd, env, true, timeoutMs, exec);
    }

    @Override
    public boolean isWslBackend() {
        return true;
    }

    @Override
    public boolean isWslDirect() {
        return true;
    }

    @Override
    public boolean registerBashTool() {
        return true;
    }

    /** wsl-direct 挂载列表（与 OsSandbox.wslDirectMountRoots 同语义）。 */
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
}
