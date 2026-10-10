package dev.everyagent.plugin.sandbox.codex.session;

import dev.everyagent.plugin.sandbox.codex.acl.RootPolicy;
import dev.everyagent.plugin.sandbox.codex.runner.FrameCodec;
import dev.everyagent.plugin.sandbox.codex.runner.IpcMessage;
import dev.everyagent.plugin.sandbox.codex.runner.IpcMessage.Exit;
import dev.everyagent.plugin.sandbox.codex.runner.IpcMessage.Output;
import dev.everyagent.plugin.sandbox.codex.runner.IpcMessage.SpawnReady;
import dev.everyagent.plugin.sandbox.codex.runner.IpcMessage.SpawnRequest;
import dev.everyagent.plugin.sandbox.codex.session.RunnerClient.Channel;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 一次 exec 的会话门面（设计文档 §2.8 CodexCommandExecutor 的会话层）。
 *
 * <p>生命周期：{@link #open} 组装 {@link SpawnRequest}（cap_sids = 各写根 cap +
 * workspace cap，write_roots/deny_write_paths 透传给 runner 做信息展示）→
 * {@link RunnerClient#start} 拉起 runner 并连管 → 发 spawn_request → 15s 内收
 * spawn_ready；之后调用方循环 {@link #receive()} 收 output/exit（阻塞或限时），
 * 可穿插 {@link #writeStdin}/{@link #closeStdin}/{@link #terminate}；
 * {@link #close()} 清理句柄与管道（管道关闭即 runner 退出信号）。
 *
 * <p>请求组装是纯函数（{@link #buildSpawnRequest}），跨平台可单测；
 * 握手/IO 仅 Windows。PrivateDesktop 尚未接线（privateDesktopName=null，
 * 见设计 §2.6；ChildProcess 已支持 lpDesktop，接桌面后从会话层传入）。
 */
public final class CodexSandboxSession implements AutoCloseable {

    private final Channel channel;
    private final int childPid;
    private volatile boolean closed;

    private CodexSandboxSession(Channel channel, int childPid) {
        this.channel = channel;
        this.childPid = childPid;
    }

    /** 会话参数（{@link #buildSpawnRequest} 的纯输入）。 */
    public record SessionSpec(
            List<String> command,
            String cwd,
            Map<String, String> env,
            /** 看门狗毫秒；null = 无限等待。 */
            Long timeoutMs,
            /** 写根（每根带 cap SID，来自 RootPolicy）。 */
            List<RootPolicy.WriteRoot> writeRoots,
            /** deny-write 路径（透传 runner 展示；ACL 在 setup/preflight 侧施加）。 */
            List<String> denyWritePaths,
            /** 全局 workspace capability SID（无额外写根时的最小授权）。 */
            String workspaceCapSid,
            /** 网络身份："offline"/"online"（账户选择的镜像，透传 runner）。 */
            String networkIdentity,
            /** 是否保持子进程 stdin 打开（worker 契约 stdin=NUL 时 false）。 */
            boolean stdinOpen,
            /** 私有桌面名（PrivateDesktop 接线后传入；当前恒 null）。 */
            String privateDesktopName) {
    }

    /**
     * 纯组装（跨平台可测）：cap_sids = 各写根 cap SID + workspace cap（去重保序，
     * 至少 1 个——runner 侧空 cap_sids 拒绝执行）；write_roots/deny_write_paths
     * 原样透传；tty 恒 false（ConPTY 暂缓，设计 §1.2）。
     */
    public static SpawnRequest buildSpawnRequest(SessionSpec spec) {
        Objects.requireNonNull(spec.command(), "command");
        if (spec.command().isEmpty()) {
            throw new IllegalArgumentException("empty command");
        }
        List<String> capSids = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (RootPolicy.WriteRoot root : spec.writeRoots()) {
            if (seen.add(root.capSid())) {
                capSids.add(root.capSid());
            }
        }
        if (spec.workspaceCapSid() != null && seen.add(spec.workspaceCapSid())) {
            capSids.add(spec.workspaceCapSid());
        }
        if (capSids.isEmpty()) {
            throw new IllegalArgumentException(
                    "spawn request needs at least one capability SID (write root or workspace)");
        }
        List<String> writeRoots = spec.writeRoots().stream()
                .map(r -> r.root().toString()).toList();
        return new SpawnRequest(spec.command(), spec.cwd(), spec.env(),
                List.copyOf(capSids), writeRoots, spec.denyWritePaths(),
                spec.networkIdentity(), spec.timeoutMs(), false /* tty */,
                spec.stdinOpen(), spec.privateDesktopName());
    }

    /**
     * 打开会话：拉起 runner → spawn_request → spawn_ready（15s）。
     * 收到 error 帧（stage+winerr）→ {@link RunnerStartupException}。
     */
    public static CodexSandboxSession open(RunnerClient.RunnerConfig cfg, SessionSpec spec)
            throws IOException {
        SpawnRequest request = buildSpawnRequest(spec);
        Channel channel = RunnerClient.start(cfg);
        try {
            channel.send(request);
            FrameCodec.FramedMessage first = channel.readFrame(RunnerClient.SPAWN_READY_TIMEOUT_MS);
            if (first == null) {
                throw new IOException("runner pipe closed before spawn_ready");
            }
            if (first.version() != IpcMessage.IPC_PROTOCOL_VERSION) {
                throw new IOException("runner protocol version mismatch: " + first.version());
            }
            switch (first.message()) {
                case SpawnReady ready -> {
                    return new CodexSandboxSession(channel, ready.processId());
                }
                case IpcMessage.Error e -> throw new RunnerStartupException(e);
                default -> throw new IOException("expected spawn_ready from runner, got "
                        + first.message().tag());
            }
        } catch (IOException | RuntimeException e) {
            channel.terminateRunner(); // 收尸（对齐 spawn_runner_transport 失败路径）
            channel.close();
            throw e;
        }
    }

    /** runner 报告的启动失败（对齐 RunnerStartupError）。 */
    public static final class RunnerStartupException extends IOException {
        private final IpcMessage.Error error;

        public RunnerStartupException(IpcMessage.Error error) {
            super("runner failed during " + error.stage().wireName() + ": " + error.message()
                    + (error.windowsErrorCode() != null
                            ? " (Windows error " + error.windowsErrorCode() + ")" : ""));
            this.error = error;
        }

        /** 原始 error 帧（stage/windows_error_code 可供凭据失配二次分类）。 */
        public IpcMessage.Error error() {
            return error;
        }
    }

    /** 子进程 PID（spawn_ready.process_id）。 */
    public int childPid() {
        return childPid;
    }

    /** 写子进程 stdin（经 stdin 帧，base64）。 */
    public void writeStdin(byte[] data) {
        assertOpen();
        channel.send(new IpcMessage.Stdin(IpcMessage.encodeBytes(data)));
    }

    /** 关闭子进程 stdin（EOF）。 */
    public void closeStdin() {
        assertOpen();
        channel.send(new IpcMessage.CloseStdin());
    }

    /** 请求终止子进程整树（terminate 帧 → runner TerminateJobObject）。 */
    public void terminate() {
        assertOpen();
        channel.send(new IpcMessage.Terminate());
    }

    /** 阻塞收下一帧（output/exit/…）；帧边界 EOF（runner 退出）返回 null。 */
    public FrameCodec.FramedMessage receive() {
        assertOpen();
        return channel.readFrame();
    }

    /** 限时收下一帧（完整帧轮询；超时抛 IOException）。 */
    public FrameCodec.FramedMessage receive(long timeoutMs) throws IOException {
        assertOpen();
        return channel.readFrame(timeoutMs);
    }

    /** 便捷：阻塞收下一帧且必须为 output（断言流型）。 */
    public Output receiveOutput() {
        FrameCodec.FramedMessage frame = receive();
        if (frame == null || !(frame.message() instanceof Output output)) {
            throw new IllegalStateException("expected output frame, got "
                    + (frame == null ? "EOF" : frame.message().tag()));
        }
        return output;
    }

    /** 便捷：阻塞收 exit 帧（会话终结；EOF/其他帧 → IllegalStateException）。 */
    public Exit awaitExit() {
        while (true) {
            FrameCodec.FramedMessage frame = receive();
            if (frame == null) {
                throw new IllegalStateException("runner pipe closed before exit frame");
            }
            switch (frame.message()) {
                case Exit exit -> {
                    return exit;
                }
                case Output ignored -> { // 汇聚方可能尚未消费的尾块
                }
                default -> throw new IllegalStateException(
                        "expected output/exit frame, got " + frame.message().tag());
            }
        }
    }

    private void assertOpen() {
        if (closed) {
            throw new IllegalStateException("session already closed");
        }
    }

    /** 清理：双管道 + runner 进程句柄（管道关闭即 runner 退出信号）。 */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        channel.close();
    }
}
