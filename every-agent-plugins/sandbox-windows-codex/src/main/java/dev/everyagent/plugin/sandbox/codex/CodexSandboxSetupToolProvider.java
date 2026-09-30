package dev.everyagent.plugin.sandbox.codex;

import dev.everyagent.plugin.api.spi.ToolContext;
import dev.everyagent.plugin.api.spi.ToolProvider;
import dev.everyagent.plugin.sandbox.codex.accounts.SandboxAccounts;
import dev.everyagent.plugin.sandbox.codex.accounts.SandboxSecrets;
import dev.everyagent.plugin.sandbox.codex.fw.FirewallInstaller;
import dev.everyagent.plugin.sandbox.codex.setup.SetupErrorReport;
import dev.everyagent.plugin.sandbox.codex.setup.SetupMarker;
import dev.everyagent.plugin.sandbox.codex.setup.SetupOrchestrator;
import dev.everyagent.plugin.sandbox.codex.setup.SetupPayload;
import dev.everyagent.plugin.sandbox.codex.win.NetApi32Ex;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * codex 沙箱的 setup/status 小工具集（设计文档 §5：setup 是<b>显式用户动作</b>，
 * 允许弹 UAC——与 Provider.isAvailable 的「绝不自动触发」互补）。
 *
 * <p>appliesTo 恒 true：setup 完成前 codex 后端不会被选中，这两个工具正是把
 * 后端拉起来的显式入口（status 只读、setup 需用户在 UAC 弹窗当场同意）。
 */
public class CodexSandboxSetupToolProvider implements ToolProvider {

    private static final boolean WINDOWS =
            System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");

    private final CodexSandboxManager manager;

    public CodexSandboxSetupToolProvider(CodexSandboxManager manager) {
        this.manager = manager;
    }

    @Override
    public String pluginId() {
        return "sandbox-windows-codex";
    }

    @Override
    public boolean appliesTo(ToolContext ctx) {
        return true; // setup/status 与当前后端无关（这正是切换到 codex 的入口）
    }

    @Override
    public List<ToolCallback> createTools(ToolContext ctx) {
        return List.of(ToolCallbacks.from(new CodexSetupTools(manager, ctx.workspaceRoot())));
    }

    /** 两个显式工具的实现（独立类便于 ToolCallbacks 注解扫描）。 */
    public static class CodexSetupTools {

        private final CodexSandboxManager manager;
        private final Path workspaceRoot;

        CodexSetupTools(CodexSandboxManager manager, Path workspaceRoot) {
            this.manager = manager;
            this.workspaceRoot = workspaceRoot;
        }

        /** 触发完整 setup（显式用户动作，会弹 UAC）。 */
        @Tool(name = "codex_sandbox_setup", description = "初始化/修复 Windows codex 沙箱:"
                + "建本地双账户(offline/online)与组、账户级 ACL、防火墙与 WFP 规则、"
                + "DPAPI 凭据与目录锁定。"
                + "<b>会弹出 UAC 提权确认窗,需用户当场同意</b>;"
                + "幂等:已完成时立即返回;账户/凭据失配时可用本工具修复;")
        public String setup(
                @ToolParam(description = "是否包含当前工作区为写根(true 默认,推荐)")
                Boolean includeWorkspaceRoot) {
            if (!WINDOWS) {
                return "[codex sandbox 仅在 Windows 上可用,当前平台: "
                        + System.getProperty("os.name") + "]";
            }
            CodexSandboxOptions options = manager.options();
            List<String> roots = new ArrayList<>();
            for (Path root : manager.writeRoots()) {
                roots.add(root.toString());
            }
            if ((includeWorkspaceRoot == null || includeWorkspaceRoot)
                    && workspaceRoot != null) {
                roots.add(workspaceRoot.toString());
            }
            SetupPayload payload = SetupPayload.create()
                    .mode(SetupPayload.Mode.FULL)
                    .accounts(options.accountPrefix(), options.codexHome().toString(),
                            System.getProperty("user.name"))
                    .writeRoots(roots)
                    .proxyPorts(options.proxyPorts());
            payload.model().allowLocalBinding = options.allowLocalBinding();
            try {
                SetupOrchestrator.ensureSetup(payload);
            } catch (SetupErrorReport.SetupException e) {
                return "[codex sandbox setup 失败] code=" + e.code() + " " + e.getMessage()
                        + (SetupErrorReport.ORCHESTRATOR_HELPER_LAUNCH_CANCELED
                                .equals(e.code()) ? "(用户在 UAC 弹窗拒绝了提权)" : "");
            }
            return "codex sandbox setup 完成(marker 版本 " + SetupPayload.SETUP_VERSION
                    + ",codexHome=" + options.codexHome() + ");"
                    + "后端在下次沙箱选择时生效(auto: 就绪即按最高可用优先选中)。";
        }

        /** 只读状态摘要。 */
        @Tool(name = "codex_sandbox_status", description = "查看 Windows codex 沙箱状态摘要:"
                + "setup marker/凭据/账户禁用位/防火墙规则名;只读,不弹 UAC;")
        public String status() {
            CodexSandboxOptions options = manager.options();
            return statusSummary(WINDOWS, options,
                    SetupMarker.read(options.codexHome()),
                    SandboxSecrets.exists(options.codexHome()),
                    WINDOWS ? accountFlags(options, false) : null,
                    WINDOWS ? accountFlags(options, true) : null);
        }
    }

    /** Windows 账户 flags 探测（null = 不存在/未知；异常按未知吞掉，status 不抛错）。 */
    private static Integer accountFlags(CodexSandboxOptions options, boolean online) {
        try {
            return SandboxAccounts.localUserFlags(SandboxAccounts.usernameFor(
                    options.accountPrefix(),
                    online ? SandboxAccounts.NetworkIdentity.ONLINE
                            : SandboxAccounts.NetworkIdentity.OFFLINE));
        } catch (RuntimeException e) {
            return null;
        }
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
                    .append(");调用 codex_sandbox_setup 完成(会弹 UAC)");
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

    private static String describeAccount(String username, Integer flags) {
        if (flags == null) {
            return username + "(不存在/未知)";
        }
        return username + ((flags & NetApi32Ex.UF_ACCOUNTDISABLE) != 0 ? "(已禁用)" : "(正常)");
    }
}
