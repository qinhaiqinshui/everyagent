package dev.everyagent.plugin.sandbox.codex;

import dev.everyagent.plugin.api.spi.SandboxBackend;
import dev.everyagent.plugin.api.spi.SandboxProvider;
import dev.everyagent.plugin.api.spi.SandboxProvider.SandboxConfig;
import dev.everyagent.plugin.sandbox.codex.setup.SetupMarker;
import dev.everyagent.plugin.sandbox.codex.setup.SetupPayload;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Locale;

/**
 * codex 后端提供者（设计文档 §2.8/§5）。
 *
 * <p>id="codex"；isAvailable = Windows 平台（仅平台探测，不查 marker）；
 * priority = Windows ? 8 : 0。取值依据（对照既有后端：wsl-ubuntu=10、
 * windows-mic=5）：
 * <ul>
 *   <li>8 明显低于最高优先者 wsl-ubuntu(10)——auto 模式永不抢占既有默认后端，
 *       WSL 可用的机器行为不变；</li>
 *   <li>高于 windows-mic(5) 的兜底位——无 WSL 的机器上 auto 应当兑现为更强的隔离
 *       而非零摩擦兜底；</li>
 * </ul>
 *
 * <p><b>延迟 setup</b>：isAvailable/priority 只看平台，不看 marker——
 * 这样沙箱选择器（{@code SandboxProviderRegistry.select}）会在所有后端注册完毕后
 * 按优先级选出最高者。只有当 codex 真正胜出（即没有更高优先级的可用后端）时，
 * {@link #create} 才被调用，此时才同步执行 setup（会弹 UAC）。
 * 若 wsl-ubuntu(10) 可用，codex(8) 永不被选中，setup 不触发——
 * 避免在已有更优沙箱的机器上无谓弹 UAC。
 */
public final class CodexSandboxProvider implements SandboxProvider {

    private static final boolean WINDOWS =
            System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");

    private static final Logger LOG =
            System.getLogger(CodexSandboxProvider.class.getName());

    /** Windows 上的 priority（见类 Javadoc 取值依据）。 */
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
     * 仅探测 Windows 平台——不查 marker，不触发 setup。
     * 沙箱选择器据此 + {@link #priority()} 做决策；setup 延迟到 {@link #create}。
     */
    @Override
    public boolean isAvailable() {
        return WINDOWS;
    }

    @Override
    public int priority() {
        return WINDOWS ? READY_PRIORITY : 0;
    }

    /**
     * 创建后端——此时 codex 已被选为最高优先级可用沙箱（意味着无 WSL 等更高
     * 优先级后端可用）。若 setup 未完成，在此同步执行 setup（会弹 UAC）。
     * setup 失败则抛异常（调用方回退到下一后端或无沙箱直接 spawn）。
     */
    @Override
    public SandboxBackend create(SandboxConfig config) {
        manager.accept(config);
        ensureSetup();
        return new CodexSandboxBackend(manager);
    }

    private void ensureSetup() {
        if (!WINDOWS) {
            return;
        }
        if (SetupMarker.isComplete(manager.options().codexHome(),
                SetupPayload.SETUP_VERSION)) {
            return;
        }
        LOG.log(Level.INFO, "codex 沙箱被选中且 setup 未完成,开始同步 setup(会弹 UAC)...");
        CodexSetupCoordinator.ensure(manager);
        LOG.log(Level.INFO, "codex 沙箱 setup 完成(marker 版本 {0},codexHome={1})",
                new Object[] { SetupPayload.SETUP_VERSION, manager.options().codexHome() });
    }
}
