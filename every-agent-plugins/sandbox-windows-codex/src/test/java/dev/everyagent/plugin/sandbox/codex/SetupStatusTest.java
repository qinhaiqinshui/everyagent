package dev.everyagent.plugin.sandbox.codex;

import dev.everyagent.plugin.sandbox.codex.fw.FirewallInstaller;
import dev.everyagent.plugin.sandbox.codex.setup.SetupMarker;
import dev.everyagent.plugin.sandbox.codex.setup.SetupPayload;
import dev.everyagent.plugin.sandbox.codex.setup.SetupStatus;
import dev.everyagent.plugin.sandbox.codex.win.NetApi32Ex;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SetupStatus}：status 摘要纯函数格式测试。
 *
 * <p>原 {@code CodexSandboxSetupToolProvider} 已删除——setup 延迟到
 * {@link CodexSandboxProvider#create} 被调用时执行（仅当 codex 被选为最高
 * 优先级沙箱时），不再在 activate() 中也不以 AI 工具形式暴露。
 * 本测试只覆盖纯函数 {@link SetupStatus#statusSummary}。
 */
class SetupStatusTest {

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
        String summary = SetupStatus.statusSummary(
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
        String summary = SetupStatus.statusSummary(
                false, options(), null, false, null, null);
        assertTrue(summary.contains("- 平台: 非 Windows(不可用)"));
        assertTrue(summary.contains("- setup: 未完成(缺 marker; 凭据文件缺失)"), summary);
        assertTrue(summary.contains("setup 应在 codex 后端被选中时自动触发"),
                "给出延迟 setup 指引");
        assertTrue(summary.contains("offline=EveryAgentCodexOffline(不存在/未知)"));
        assertTrue(summary.contains("- 凭据文件(DPAPI): 缺失"));
    }

    @Test
    void statusSummaryFlagsDisabledAccounts() {
        String summary = SetupStatus.statusSummary(
                true, options(), marker(), true, NetApi32Ex.UF_ACCOUNTDISABLE, null);
        assertTrue(summary.contains("offline=EveryAgentCodexOffline(已禁用)"),
                "禁用位(修复路径中间态)可见");
        assertTrue(summary.contains("online=EveryAgentCodexOnline(不存在/未知)"));
    }
}
