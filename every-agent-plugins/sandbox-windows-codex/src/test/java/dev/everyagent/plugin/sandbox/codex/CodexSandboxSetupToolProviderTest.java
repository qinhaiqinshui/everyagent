package dev.everyagent.plugin.sandbox.codex;

import dev.everyagent.plugin.sandbox.codex.fw.FirewallInstaller;
import dev.everyagent.plugin.sandbox.codex.setup.SetupMarker;
import dev.everyagent.plugin.sandbox.codex.setup.SetupPayload;
import dev.everyagent.plugin.sandbox.codex.win.NetApi32Ex;

import com.sun.jna.Platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.ai.tool.ToolCallback;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * {@link CodexSandboxSetupToolProvider}：status 摘要纯函数格式 +
 * 工具集形态；setup 工具的非 Windows 拒绝路径。
 */
class CodexSandboxSetupToolProviderTest {

    @TempDir
    Path tempDir;

    private CodexSandboxOptions options() {
        return new CodexSandboxOptions(tempDir, "EveryAgentCodex", "auto",
                List.of(8080), false, "");
    }

    private SetupMarker.Model marker() {
        SetupMarker.Model marker = new SetupMarker.Model();
        marker.version = SetupPayload.SETUP_VERSION;
        marker.offlineUsername = "EveryAgentCodexOffline";
        marker.onlineUsername = "EveryAgentCodexOnline";
        marker.createdAt = "2026-01-01T00:00:00+08:00";
        return marker;
    }

    @Test
    void statusSummaryCompleteShape() {
        String summary = CodexSandboxSetupToolProvider.statusSummary(
                true, options(), marker(), true, 0x10011, 0x10011);
        assertTrue(summary.startsWith("codex 沙箱状态:"));
        assertTrue(summary.contains("- 平台: Windows(原生可用)"));
        assertTrue(summary.contains("- setup: 已完成(marker 版本 " + SetupPayload.SETUP_VERSION
                + ", 创建于 2026-01-01T00:00:00+08:00)"), summary);
        assertTrue(summary.contains("- codexHome: " + tempDir));
        assertTrue(summary.contains("offline=EveryAgentCodexOffline(正常)"));
        assertTrue(summary.contains("online=EveryAgentCodexOnline(正常)"));
        assertTrue(summary.contains("- 凭据文件(DPAPI): 存在"));
        assertTrue(summary.contains("共 " + FirewallInstaller.allRuleNames().size() + " 条"));
        assertTrue(summary.contains(FirewallInstaller.BLOCK_OUTBOUND_NAME));
    }

    @Test
    void statusSummaryIncompleteMentionsMissingPiecesAndGuidance() {
        String summary = CodexSandboxSetupToolProvider.statusSummary(
                false, options(), null, false, null, null);
        assertTrue(summary.contains("- 平台: 非 Windows(不可用)"));
        assertTrue(summary.contains("- setup: 未完成(缺 marker; 凭据文件缺失)"), summary);
        assertTrue(summary.contains("codex_sandbox_setup 完成(会弹 UAC)"), "给出显式指引");
        assertTrue(summary.contains("offline=EveryAgentCodexOffline(不存在/未知)"));
        assertTrue(summary.contains("- 凭据文件(DPAPI): 缺失"));
    }

    @Test
    void statusSummaryFlagsDisabledAccounts() {
        String summary = CodexSandboxSetupToolProvider.statusSummary(
                true, options(), marker(), true, NetApi32Ex.UF_ACCOUNTDISABLE, null);
        assertTrue(summary.contains("offline=EveryAgentCodexOffline(已禁用)"),
                "禁用位(修复路径中间态)可见");
        assertTrue(summary.contains("online=EveryAgentCodexOnline(不存在/未知)"));
    }

    @Test
    void appliesAlwaysAndCreatesSetupAndStatusTools() {
        CodexSandboxSetupToolProvider provider = new CodexSandboxSetupToolProvider(
                new CodexSandboxManager(options(), 30_000));
        assertTrue(provider.appliesTo(TestFixtures.ctx("wsl-ubuntu", tempDir, null)),
                "setup/status 与当前后端无关");
        assertTrue(provider.appliesTo(TestFixtures.ctx(null, tempDir, null)));
        assertEquals("sandbox-windows-codex", provider.pluginId());
        List<ToolCallback> tools = provider.createTools(TestFixtures.ctx("codex", tempDir, null));
        assertEquals(2, tools.size());
        assertEquals("codex_sandbox_setup", tools.get(0).getToolDefinition().name());
        assertEquals("codex_sandbox_status", tools.get(1).getToolDefinition().name());
        assertTrue(tools.get(0).getToolDefinition().description().contains("UAC"),
                "setup 描述写清会弹 UAC");
    }

    @Test
    void setupToolRejectsNonWindowsWithoutSideEffects() {
        assumeFalse(Platform.isWindows(), "Windows 上会真弹 UAC,只在非 Windows 固化拒绝文案");
        CodexSandboxSetupToolProvider.CodexSetupTools tools =
                new CodexSandboxSetupToolProvider.CodexSetupTools(
                        new CodexSandboxManager(options(), 30_000), tempDir);
        String result = tools.setup(true);
        assertTrue(result.startsWith("[codex sandbox 仅在 Windows 上可用"), result);
        assertFalse(result.contains("setup 完成"), "绝不能报告成功");
    }
}
