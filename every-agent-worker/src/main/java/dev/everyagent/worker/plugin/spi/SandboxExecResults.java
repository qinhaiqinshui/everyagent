package dev.everyagent.worker.plugin.spi;

import dev.everyagent.worker.os.OsSandbox;

/**
 * OsSandbox 的执行结果（从 OsSandbox.ExecResult 引用，避免重复定义）。
 *
 * <p>沙箱 SPI 的方法返回 {@link OsSandbox.ExecResult}——这是现有系统已定义的 record，
 * 插件侧无需重复定义，直接引用即可。
 */
public final class SandboxExecResults {
    private SandboxExecResults() {}

    // OsSandbox.ExecResult 是 public record，可直接引用
    // 此类仅作为文档锚点存在
}
