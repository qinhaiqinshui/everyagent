package dev.everyagent.plugin.sandbox.codex;

import com.sun.jna.Platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link CodexSandboxProvider} 的 isAvailable/priority 行为：
 * 非 Windows 恒不可用、priority 恒 0；Windows 上恒可用且 priority=8。
 *
 * <p>isAvailable 不再查 marker——仅探测 Windows 平台。setup 延迟到
 * {@link CodexSandboxProvider#create()} 被调用时（即 codex 被选为最高优先级
 * 可用沙箱时）才触发。这意味着只有无 WSL(10>8) 等更高优先级沙箱时才弹 UAC。
 */
class CodexSandboxProviderTest {

    @TempDir
    Path tempDir;

    private CodexSandboxProvider provider(Path codexHome) {
        return new CodexSandboxProvider(new CodexSandboxManager(
                new CodexSandboxOptions(codexHome, null, null, null, false, null),
                60_000));
    }

    /** 非 Windows：恒不可用、priority 恒 0（Linux CI 确定性断言）。 */
    @Test
    void nonWindowsNeverAvailable() {
        assumeTrue(!Platform.isWindows(), "本用例固化非 Windows 行为");
        CodexSandboxProvider provider = provider(tempDir);
        assertFalse(provider.isAvailable(), "非 Windows 平台恒不可用");
        assertEquals(0, provider.priority());
        assertEquals("codex", provider.id());
    }

    /** Windows：恒可用（不查 marker）且 priority=8（低于 wsl-ubuntu=10，高于 mic=5）。 */
    @Test
    void windowsAlwaysAvailableWithPriority8() {
        assumeTrue(Platform.isWindows(), "Windows 专属");
        CodexSandboxProvider provider = provider(tempDir);
        assertTrue(provider.isAvailable(), "Windows 上恒可用（不查 marker）");
        assertEquals(CodexSandboxProvider.READY_PRIORITY, provider.priority());
        assertEquals(8, CodexSandboxProvider.READY_PRIORITY, "低于 wsl-ubuntu(10),高于 mic(5)");
    }

    /** Windows：即使无 marker 也可用（setup 延迟到 create()）。 */
    @Test
    void windowsAvailableWithoutMarker() {
        assumeTrue(Platform.isWindows(), "Windows 专属");
        CodexSandboxProvider provider = provider(tempDir);
        assertTrue(provider.isAvailable(), "无 marker 也可用——setup 延迟到 create()");
        assertEquals(8, provider.priority());
    }
}
