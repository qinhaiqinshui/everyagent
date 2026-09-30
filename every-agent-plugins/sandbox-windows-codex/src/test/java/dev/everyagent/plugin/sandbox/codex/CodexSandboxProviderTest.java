package dev.everyagent.plugin.sandbox.codex;

import dev.everyagent.plugin.sandbox.codex.accounts.SandboxSecrets;
import dev.everyagent.plugin.sandbox.codex.setup.SetupMarker;
import dev.everyagent.plugin.sandbox.codex.setup.SetupPayload;

import com.sun.jna.Platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link CodexSandboxProvider} 的 isAvailable/priority 行为：
 * 非 Windows 恒不可用（即使 marker+凭据齐全）；Windows 上双闸门就绪才可用
 * （Windows 专属部分 assumeTrue 守卫，设计文档 §8）。
 */
class CodexSandboxProviderTest {

    @TempDir
    Path tempDir;

    private CodexSandboxProvider provider(Path codexHome) {
        return new CodexSandboxProvider(new CodexSandboxManager(
                new CodexSandboxOptions(codexHome, null, null, null, false, null),
                60_000));
    }

    /** 非 Windows：marker 齐全也不可用、priority 恒 0（Linux CI 确定性断言）。 */
    @Test
    void nonWindowsNeverAvailableEvenWithMarker() throws IOException {
        assumeTrue(!Platform.isWindows(), "本用例固化非 Windows 行为");
        Path home = tempDir;
        completeSetup(home);
        CodexSandboxProvider provider = provider(home);
        assertFalse(provider.isAvailable(), "非 Windows 平台恒不可用");
        assertEquals(0, provider.priority());
        assertEquals("codex", provider.id());
    }

    /** 非 Windows 且无 marker：同样不可用（无状态探测路径）。 */
    @Test
    void nonWindowsWithoutMarkerUnavailable() {
        assumeTrue(!Platform.isWindows(), "本用例固化非 Windows 行为");
        CodexSandboxProvider provider = provider(tempDir);
        assertFalse(provider.isAvailable());
        assertEquals(0, provider.priority());
    }

    /** Windows：双闸门就绪 → 可用且 priority=8（不抢 wsl-ubuntu=10，高于 mic=5）。 */
    @Test
    void windowsReadyYieldsAvailabilityAndPriority8() throws IOException {
        assumeTrue(Platform.isWindows(), "Windows 专属");
        CodexSandboxProvider provider = provider(tempDir);
        assertFalse(provider.isAvailable(), "未 setup 不可用");
        assertEquals(0, provider.priority());

        completeSetup(tempDir);
        assertTrue(provider.isAvailable(), "marker+凭据就绪 → 可用");
        assertEquals(CodexSandboxProvider.READY_PRIORITY, provider.priority());
        assertEquals(8, CodexSandboxProvider.READY_PRIORITY, "低于 wsl-ubuntu(10),高于 mic(5)");
    }

    /** Windows：marker 版本不匹配 → 不可用（版本闸门）。 */
    @Test
    void windowsMarkerVersionMismatchUnavailable() throws IOException {
        assumeTrue(Platform.isWindows(), "Windows 专属");
        completeSetup(tempDir);
        SetupMarker.commit(tempDir, SetupPayload.SETUP_VERSION + 1,
                "OfflineUser", "OnlineUser", List.of(), false);
        assertFalse(provider(tempDir).isAvailable());
    }

    /** Windows：缺凭据文件（marker 单闸门）→ 不可用。 */
    @Test
    void windowsSecretsMissingUnavailable() throws IOException {
        assumeTrue(Platform.isWindows(), "Windows 专属");
        SetupMarker.commit(tempDir, SetupPayload.SETUP_VERSION,
                "OfflineUser", "OnlineUser", List.of(), false);
        assertFalse(provider(tempDir).isAvailable());
    }

    /** 写出「完整 setup」状态：marker 提交 + 凭据文件占位。 */
    private void completeSetup(Path codexHome) throws IOException {
        SetupMarker.commit(codexHome, SetupPayload.SETUP_VERSION,
                "EveryAgentCodexOffline", "EveryAgentCodexOnline", List.of(8080), false);
        Files.createDirectories(SandboxSecrets.secretsFile(codexHome).getParent());
        Files.writeString(SandboxSecrets.secretsFile(codexHome), "{}");
    }
}
