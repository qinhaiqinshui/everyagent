package dev.everyagent.plugin.sandbox.codex.session;

import dev.everyagent.plugin.sandbox.codex.runner.FrameCodec;
import dev.everyagent.plugin.sandbox.codex.runner.IpcMessage;

import java.io.IOException;

/**
 * 宿主侧与单个 runner 的通道抽象（生产 = {@link RunnerClient.Channel}）。
 *
 * <p>从具体类提炼为接口的动机是 {@link RunnerPools} 的可测性：池的全部行为
 * （复用/健康探测/TTL 回收/剔除）都是纯连接管理逻辑，单测用 fake 通道即可覆盖，
 * 无须真的 CreateProcessWithLogonW（那是 windows-admin 端到端档的事）。
 * 方法集与 {@code RunnerClient.Channel} 的公共面一一对应。
 */
public interface RunnerChannel extends AutoCloseable {

    /** 父→runner 写一帧。 */
    void send(IpcMessage message);

    /** runner→父 阻塞读一帧；帧边界 EOF（runner 退出）返回 null。 */
    FrameCodec.FramedMessage readFrame();

    /** 限时读一帧（完整帧轮询；超时抛 IOException）。 */
    FrameCodec.FramedMessage readFrame(long timeoutMs) throws IOException;

    /** TerminateProcess 收尸（通道不可恢复时使用）。 */
    void terminateRunner();

    /** runner 进程是否仍存活（进程句柄 WaitForSingleObject(0) 口径）。 */
    boolean runnerAlive();

    /**
     * {@code -out} 管道内已缓冲、尚未被父侧读走的字节数；管道断开返回 -1。
     * 池化复用前必须为 0——非零意味着上一条命令残留未消费帧，直接复用会把陈旧帧
     * 错位进下一次 spawn_ready 握手（协议流错乱），按不健康剔除。
     */
    int bufferedOutputBytes();

    /** 清理：双管道 + runner 进程句柄（管道关闭即 runner 退出信号）。 */
    @Override
    void close();
}
