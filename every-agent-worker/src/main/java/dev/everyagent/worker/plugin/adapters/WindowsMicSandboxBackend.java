package dev.everyagent.worker.plugin.adapters;

import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.os.OsSandbox.ExecResult;
import dev.everyagent.worker.os.windows.WindowsSandbox;
import dev.everyagent.worker.plugin.spi.SandboxBackend;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * windows-mic 沙箱后端适配器。
 *
 * <p>委托 {@link WindowsSandbox#run} 执行命令：Restricted Token + Medium IL +
 * Job Object（进程数/内存/CPU 上限 + KillOnJobClose），零文件系统副作用。
 */
public final class WindowsMicSandboxBackend implements SandboxBackend {

    private final WorkerProperties props;
    private final WorkerProperties.Sandbox cfg;
    private final ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
    private final ExecutorService drainExec = Executors.newFixedThreadPool(4,
            r -> { Thread t = new Thread(r, "sandbox-drain"); t.setDaemon(true); return t; });

    WindowsMicSandboxBackend(WorkerProperties props) {
        this.props = props;
        this.cfg = props.getSandbox();
    }

    @Override
    public String id() {
        return "windows-mic";
    }

    @Override
    public ExecResult spawnSandboxed(String command, Path cwd, Map<String, String> extraEnv,
            String shell, List<Path> extraRoots, boolean allowNetwork, boolean allowPrivilege) {
        // spawnSandboxedWindows 的默认实现委托给本方法，行为一致（均走 WindowsSandbox.run）
        return WindowsSandbox.run(command, cwd, extraEnv, cfg, exec, drainExec,
                DirectSpawnSupport.MAX_OUTPUT_CHARS, shell, allowNetwork, allowPrivilege);
    }

    @Override
    public ExecResult spawnNative(String[] argv, Path cwd, Map<String, String> env, long timeoutMs) {
        return DirectSpawnSupport.runDirect(List.of(argv), cwd, env, true, timeoutMs, exec);
    }

    @Override
    public boolean isWslBackend() {
        return false;
    }

    @Override
    public boolean registerBashTool() {
        return false;
    }
}
