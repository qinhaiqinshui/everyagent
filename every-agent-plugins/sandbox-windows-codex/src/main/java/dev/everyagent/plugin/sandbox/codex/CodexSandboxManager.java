package dev.everyagent.plugin.sandbox.codex;

import dev.everyagent.plugin.api.spi.SandboxBackend.Access;
import dev.everyagent.plugin.api.spi.SandboxProvider.SandboxConfig;
import dev.everyagent.plugin.sandbox.codex.accounts.CapSids;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * codex 沙箱的会话工厂登记簿（设计文档 §5：Backend.mount 登记 → CommandExecutor 查询）。
 *
 * <p>刻意保持「无复杂状态」：一个 {@link CodexSandboxOptions} 快照 + 两张
 * canonical 键控的根路径表（READ_WRITE / READ_ONLY），外加 {@link SandboxConfig}
 * 折叠来的 timeoutMs/networkDenied。不持锁文件、不管理 ACL、不缓存会话——
 * ACL 施加在 setup/preflight 侧，会话每次执行新建。
 *
 * <p>生命周期：插件 activate 创建单实例，Provider/Backend/BashToolProvider 共享；
 * {@code create(SandboxConfig)} 时经 {@link #accept} 折叠任务级配置。
 */
public final class CodexSandboxManager {

    private final CodexSandboxOptions options;
    private final long defaultTimeoutMs;

    /** canonical 键 → 根路径（READ_WRITE）。 */
    private final Map<String, Path> writeRoots = new LinkedHashMap<>();
    /** canonical 键 → 根路径（READ_ONLY）。 */
    private final Map<String, Path> readRoots = new LinkedHashMap<>();

    private long configuredTimeoutMs;
    private boolean networkDenied;

    public CodexSandboxManager(CodexSandboxOptions options, long defaultTimeoutMs) {
        this.options = options;
        this.defaultTimeoutMs = defaultTimeoutMs;
    }

    public CodexSandboxOptions options() {
        return options;
    }

    /** 折叠 {@link SandboxProvider#create} 传入的任务级配置（timeoutMs>0 生效）。 */
    public void accept(SandboxConfig config) {
        if (config == null) {
            return;
        }
        if (config.timeoutMs() > 0) {
            configuredTimeoutMs = config.timeoutMs();
        }
        networkDenied = config.networkDenied();
    }

    /** 登记一个挂载根（幂等：同 canonical 键重复登记为覆盖，不重复计入）。 */
    public synchronized void register(Path hostPath, Access access) {
        if (hostPath == null) {
            return;
        }
        String key = CapSids.canonicalPathKey(hostPath);
        if (access == Access.READ_ONLY) {
            writeRoots.remove(key);
            readRoots.putIfAbsent(key, hostPath);
        } else {
            readRoots.remove(key);
            writeRoots.putIfAbsent(key, hostPath);
        }
    }

    /** 全部 READ_WRITE 根（登记序）。 */
    public synchronized List<Path> writeRoots() {
        return new ArrayList<>(writeRoots.values());
    }

    /** 全部 READ_ONLY 根（登记序）。 */
    public synchronized List<Path> readRoots() {
        return new ArrayList<>(readRoots.values());
    }

    /** 单命令看门狗：SandboxConfig.timeoutMs &gt; 0 优先，否则 worker 沙箱默认。 */
    public long execTimeoutMs() {
        return configuredTimeoutMs > 0 ? configuredTimeoutMs : defaultTimeoutMs;
    }

    /** worker 全局断网（SandboxConfig.networkDenied；未 create 时 false）。 */
    public boolean networkDenied() {
        return networkDenied;
    }
}
