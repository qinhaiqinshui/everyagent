package dev.everyagent.plugin.sandbox.codex.setup;

import dev.everyagent.plugin.sandbox.codex.CodexSandboxOptions;
import dev.everyagent.plugin.sandbox.codex.accounts.SandboxAccounts;
import dev.everyagent.plugin.sandbox.codex.accounts.SandboxSecrets;
import dev.everyagent.plugin.sandbox.codex.fw.FirewallInstaller;
import dev.everyagent.plugin.sandbox.codex.win.NetApi32Ex;

/**
 * codex 沙箱 setup 状态摘要工具（纯函数，跨平台可单测）。
 *
 * <p>原为 {@code CodexSandboxSetupToolProvider} 的内部方法，现迁移至此——
 * setup 已移入 {@code CodexSandboxPlugin.activate()} 同步执行，
 * 不再以 AI 工具形式暴露。本类仅供 activate 日志与诊断调用。
 */
public final class SetupStatus {

    private SetupStatus() {
    }

    /**
     * 状态摘要（纯函数，跨平台单测）：平台行 + setup 双闸门行 + codexHome +
     * 账户行（flags null = 不存在/未知；含 UF_ACCOUNTDISABLE 位标注「已禁用」）+
     * 凭据行 + 防火墙规则名清单。
     */
    public static String statusSummary(boolean windows, CodexSandboxOptions options,
            SetupMarker.Model marker, boolean secretsPresent,
            Integer offlineFlags, Integer onlineFlags) {
        StringBuilder sb = new StringBuilder("codex 沙箱状态:\n");
        sb.append("- 平台: ").append(windows ? "Windows(原生可用)" : "非 Windows(不可用)")
                .append('\n');
        boolean complete = marker != null && marker.version == SetupPayload.SETUP_VERSION
                && marker.offlineUsername != null && marker.onlineUsername != null
                && secretsPresent;
        sb.append("- setup: ");
        if (complete) {
            sb.append("已完成(marker 版本 ").append(marker.version);
            if (marker.createdAt != null) {
                sb.append(", 创建于 ").append(marker.createdAt);
            }
            sb.append(')');
        } else {
            sb.append("未完成(")
                    .append(marker == null ? "缺 marker" : "marker 版本/账户不匹配")
                    .append(secretsPresent ? "" : "; 凭据文件缺失")
                    .append(");需重新激活插件以完成 setup(会弹 UAC)");
        }
        sb.append('\n');
        sb.append("- codexHome: ").append(options.codexHome()).append('\n');
        sb.append("- 账户: offline=")
                .append(describeAccount(
                        SandboxAccounts.offlineUsername(options.accountPrefix()), offlineFlags))
                .append(", online=")
                .append(describeAccount(
                        SandboxAccounts.onlineUsername(options.accountPrefix()), onlineFlags))
                .append('\n');
        sb.append("- 凭据文件(DPAPI): ").append(secretsPresent ? "存在" : "缺失").append('\n');
        sb.append("- 防火墙规则(setup 安装,共 ").append(FirewallInstaller.allRuleNames().size())
                .append(" 条): ").append(String.join(", ", FirewallInstaller.allRuleNames()));
        return sb.toString();
    }

    /** Windows 账户 flags 探测（null = 不存在/未知；异常按未知吞掉，status 不抛错）。 */
    public static Integer accountFlags(CodexSandboxOptions options, boolean online) {
        try {
            return SandboxAccounts.localUserFlags(SandboxAccounts.usernameFor(
                    options.accountPrefix(),
                    online ? SandboxAccounts.NetworkIdentity.ONLINE
                            : SandboxAccounts.NetworkIdentity.OFFLINE));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String describeAccount(String username, Integer flags) {
        if (flags == null) {
            return username + "(不存在/未知)";
        }
        return username + ((flags & NetApi32Ex.UF_ACCOUNTDISABLE) != 0 ? "(已禁用)" : "(正常)");
    }
}
