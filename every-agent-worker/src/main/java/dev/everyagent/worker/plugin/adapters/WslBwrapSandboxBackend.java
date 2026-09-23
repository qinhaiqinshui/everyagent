package dev.everyagent.worker.plugin.adapters;

import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.plugin.api.spi.ExecResult;
import dev.everyagent.worker.os.windows.WindowsSandbox;
import dev.everyagent.worker.os.wsl.WslBwrapSandbox;
import dev.everyagent.plugin.api.spi.SandboxBackend;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * wsl-bwrap 沙箱后端适配器。
 *
 * <p>委托 {@link WslBwrapSandbox#run} 执行命令：命令经 wsl.exe 进发行版、
 * bubblewrap 挂载命名空间隔离（bind 白名单 + 只读基座 + --unshare-net）。
 */
public final class WslBwrapSandboxBackend implements SandboxBackend {

    private final WorkerProperties props;
    private final WorkerProperties.Sandbox cfg;
    private final ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
    private final ExecutorService drainExec = Executors.newFixedThreadPool(4,
            r -> { Thread t = new Thread(r, "sandbox-drain"); t.setDaemon(true); return t; });

    WslBwrapSandboxBackend(WorkerProperties props) {
        this.props = props;
        this.cfg = props.getSandbox();
    }

    @Override
    public String id() {
        return "wsl-bwrap";
    }

    @Override
    public ExecResult spawnSandboxed(String command, Path cwd, Map<String, String> extraEnv,
            String shell, List<Path> extraRoots, boolean allowNetwork, boolean allowPrivilege) {
        WslBwrapSandbox.OsResult r = WslBwrapSandbox.run(command, cwd, extraEnv, props, exec,
                DirectSpawnSupport.MAX_OUTPUT_CHARS, shell,
                extraRoots == null ? List.of() : extraRoots, allowNetwork, allowPrivilege);
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
    public boolean isWslBwrap() {
        return true;
    }

    @Override
    public boolean registerBashTool() {
        return true;
    }
}
