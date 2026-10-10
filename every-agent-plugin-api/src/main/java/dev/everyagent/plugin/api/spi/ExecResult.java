package dev.everyagent.plugin.api.spi;

/**
 * 沙箱命令执行结果（stdout / stderr / exitCode / aborted）。
 *
 * <p>从 worker 的 {@code OsSandbox.ExecResult} 抽到 plugin-api，
 * 使 {@link SandboxBackend} 的方法签名不依赖 worker 实现类。
 */
public record ExecResult(String stdout, String stderr, int exitCode, boolean aborted) {
}
