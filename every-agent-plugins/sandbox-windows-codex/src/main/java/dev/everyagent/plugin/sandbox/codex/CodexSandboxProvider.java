package dev.everyagent.plugin.sandbox.codex;

import dev.everyagent.plugin.api.spi.SandboxBackend;
import dev.everyagent.plugin.api.spi.SandboxProvider;
import dev.everyagent.plugin.api.spi.SandboxProvider.SandboxConfig;
import dev.everyagent.plugin.sandbox.codex.setup.SetupMarker;
import dev.everyagent.plugin.sandbox.codex.setup.SetupPayload;

import java.util.Locale;

/**
 * codex 后端提供者（设计文档 §2.8/§5）。
 *
 * <p>id="codex"；isAvailable = Windows 平台 ∧ setup marker + 凭据双闸门就绪——
 * <b>绝不自动触发 setup / UAC</b>（首次供给只能经显式的 codex_sandbox_setup 工具
 * 或人工运行 helper，避免 auto 探测时静默弹提权窗）。
 *
 * <p>priority：就绪 8、未 setup 0。取值依据（对照既有后端：wsl-ubuntu=10、
 * windows-mic=5）：
 * <ul>
 *   <li>8 明显低于最高优先者 wsl-ubuntu(10)——auto 模式永不抢占既有默认后端，
 *       WSL 可用的机器行为不变；</li>
 *   <li>高于 windows-mic(5) 的兜底位——setup 完成是一次显式 UAC 授权的用户选择，
 *       无 WSL 的机器上 auto 应当兑现为更强的隔离而非零摩擦兜底；</li>
 *   <li>未 setup 时 0 且 isAvailable=false，任何选择路径都不会命中。</li>
 * </ul>
 */
public final class CodexSandboxProvider implements SandboxProvider {

    private static final boolean WINDOWS =
            System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");

    /** setup 就绪时的 priority（见类 Javadoc 取值依据）。 */
    public static final int READY_PRIORITY = 8;

    private final CodexSandboxManager manager;

    public CodexSandboxProvider(CodexSandboxManager manager) {
        this.manager = manager;
    }

    @Override
    public String id() {
        return "codex";
    }

    /**
     * Windows ∧ marker+凭据就绪（{@link SetupMarker#isComplete} 双闸门）。
     * 只读探测：marker 缺失/损坏/版本不匹配一律 false，不触发任何供给动作。
     */
    @Override
    public boolean isAvailable() {
        return WINDOWS && SetupMarker.isComplete(
                manager.options().codexHome(), SetupPayload.SETUP_VERSION);
    }

    @Override
    public int priority() {
        return isAvailable() ? READY_PRIORITY : 0;
    }

    @Override
    public SandboxBackend create(SandboxConfig config) {
        manager.accept(config);
        return new CodexSandboxBackend(manager);
    }
}
