package dev.everyagent.worker.plugin.spi;

import dev.everyagent.worker.config.WorkerProperties;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * 沙箱后端提供者 SPI —— 插件实现此接口提供不同隔离策略。
 *
 * <p>对标 VSCode 的 terminal profile provider：不同插件提供不同沙箱后端
 * （wsl-direct / wsl-bwrap / windows-mic / docker / none），核心只做选择与聚合。
 *
 * <p>改造前：{@code OsSandbox} 硬编码 {@code if (backend == WSL_BWRAP) ... else if (WSL_DIRECT) ... else WindowsSandbox.run(...)}。
 * 改造后：{@code OsSandbox} 从 {@link dev.everyagent.worker.plugin.registry.SandboxProviderRegistry} 选择后端。
 */
public interface SandboxProvider {

    /** 后端 id（如 "wsl-bwrap"/"windows-mic"/"docker"/"none"）。 */
    String id();

    /** 此后端在当前平台是否可用（探测）。 */
    boolean isAvailable();

    /** 优先级（auto 模式下多后端可用时选最高；数值越大优先级越高）。 */
    default int priority() {
        return 0;
    }

    /** 创建沙箱执行器。 */
    SandboxBackend create(SandboxConfig config);

    /**
     * 沙箱配置（从 WorkerProperties.Sandbox 解析）。
     *
     * @param type 用户配置的后端类型（如 "wsl-direct"/"wsl-bwrap"/"windows-mic"/"auto"/"none"）
     * @param enabled 是否启用沙箱
     * @param networkDenied 是否拒绝网络
     * @param allowPrivilegeEscalation 是否允许提权
     * @param interceptPrivilege 是否拦截提权
     * @param timeoutMs 命令超时（毫秒）
     * @param persistentRoot 持久状态根目录（wsl-bwrap 用）
     * @param props WorkerProperties 原始配置（后端可能需要读取额外参数）
     */
    record SandboxConfig(String type, boolean enabled, boolean networkDenied,
            boolean allowPrivilegeEscalation, boolean interceptPrivilege,
            long timeoutMs, Path persistentRoot, WorkerProperties props) {
    }
}
