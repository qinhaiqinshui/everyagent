package dev.everyagent.plugin.sandbox.codex.setup;

import dev.everyagent.plugin.sandbox.codex.accounts.CapSids;
import dev.everyagent.plugin.sandbox.codex.accounts.SandboxAccounts;
import dev.everyagent.plugin.sandbox.codex.accounts.SandboxSecrets;
import dev.everyagent.plugin.sandbox.codex.fw.FirewallInstaller;
import dev.everyagent.plugin.sandbox.codex.fw.WfpInstaller;
import dev.everyagent.plugin.sandbox.codex.win.NetApi32Ex;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ServiceLoader;

/**
 * UAC 提权后的 setup helper 入口（对应 codex setup_provisioning.rs::real_main +
 * run_setup_full；分析文档 §5.1/§5.2，设计文档 §2.4）。
 *
 * <p>命令行：{@code --setup-payload <base64>}（argv 单参数，对齐 codex）或
 * {@code --setup-payload-file <path>}（超 24,000 UTF-16 单位的回退）。
 * 与 runner 是同一 jar 的两个 main——由 {@link SetupOrchestrator} 以当前 java.exe
 * {@code -cp <jar>} 提权拉起，或已在提权进程内时直接调用 {@link #executePayload}。
 *
 * <p>Full/ProvisionOnly 顺序（顺序本身即安全属性，对齐 run_setup_full）：
 * 锁 {@code Global\EveryAgentCodexSetup}（DACL SY+BA）→ marker 空 sentinel 两阶段
 * 阶段一 → 修复检测（禁用账户残留则新建账户带 UF_ACCOUNTDISABLE，不变量④）→
 * 建组/户/凭据 → 隐藏账户 → 防火墙（LocalPolicyModifyState 自检 + SID 读回）→
 * WFP（事务；修复路径失败即中止不解禁）→ 修复路径解禁 → ACL 授权
 * （{@link AclApplier}，步骤 6c 接入；未注册实现时可选跳过）→ 三目录 DACL 锁定 →
 * marker 阶段二提交。任何失败写 setup_error.json 后以非 0 退出码结束。
 */
public final class SetupHelperMain {

    private static final System.Logger LOG =
            System.getLogger(SetupHelperMain.class.getName());

    private SetupHelperMain() {
    }

    /** helper main（UAC 提权 JVM 的入口）。 */
    public static void main(String[] args) {
        int exit = 0;
        try {
            SetupPayload payload = parseArgs(args);
            executePayload(payload);
        } catch (SetupErrorReport.SetupException e) {
            LOG.log(System.Logger.Level.ERROR, "setup helper failed: {0}", e.getMessage());
            exit = 1;
        } catch (Exception e) {
            LOG.log(System.Logger.Level.ERROR, "setup helper failed", e);
            exit = 1;
        }
        System.exit(exit);
    }

    /** 解析 helper 参数（payload 原则：所有策略数据经载荷传入）。 */
    static SetupPayload parseArgs(String[] args) throws IOException {
        if (args.length == 2 && "--setup-payload".equals(args[0])) {
            try {
                return SetupPayload.decodeBase64(args[1]);
            } catch (IOException e) {
                throw new SetupErrorReport.SetupException(
                        SetupErrorReport.HELPER_REQUEST_ARGS_FAILED,
                        "failed to parse payload: " + e.getMessage());
            }
        }
        if (args.length == 2 && "--setup-payload-file".equals(args[0])) {
            Path file = Path.of(args[1]);
            try {
                SetupPayload payload = SetupPayload.readPayloadFile(file);
                Files.deleteIfExists(file); // 一次性载荷
                return payload;
            } catch (IOException e) {
                throw new SetupErrorReport.SetupException(
                        SetupErrorReport.HELPER_REQUEST_ARGS_FAILED,
                        "failed to read payload file " + file + ": " + e.getMessage());
            }
        }
        throw new SetupErrorReport.SetupException(SetupErrorReport.HELPER_REQUEST_ARGS_FAILED,
                "expected --setup-payload <base64> or --setup-payload-file <path>");
    }

    /** 提权载荷执行（模式分派；失败时写 setup_error.json 并重抛）。 */
    public static void executePayload(SetupPayload payload) throws IOException {
        try {
            SetupPayload.Model model = payload.model();
            if (payload.mode() == SetupPayload.Mode.REMOVE) {
                Uninstaller.run(payload);
                return;
            }
            runProvisioning(payload, model);
        } catch (SetupErrorReport.SetupException e) {
            writeErrorReport(payload, e.code(), e.getMessage());
            throw e;
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            writeErrorReport(payload, SetupErrorReport.HELPER_UNKNOWN_ERROR, message);
            throw e instanceof RuntimeException runtime ? runtime
                    : new SetupErrorReport.SetupException(
                            SetupErrorReport.HELPER_UNKNOWN_ERROR, message, e);
        }
    }

    private static void runProvisioning(SetupPayload payload, SetupPayload.Model model)
            throws IOException {
        Path codexHome = Path.of(model.codexHome);
        try {
            Files.createDirectories(SandboxDirs.sandboxDir(codexHome));
        } catch (IOException e) {
            throw new SetupErrorReport.SetupException(
                    SetupErrorReport.HELPER_SANDBOX_DIR_CREATE_FAILED,
                    "failed to create sandbox dir " + SandboxDirs.sandboxDir(codexHome)
                            + ": " + e.getMessage());
        }
        try (SetupLock lock = SetupLock.acquire(SetupLock.WAIT_INFINITE)) {
            boolean refreshOnly = model.refreshOnly;
            if (refreshOnly) {
                // refresh 不碰账户/网络（对齐 run_setup_refresh 语义：不提权不重建）。
                applyAcls(payload, codexHome, ensureGroupSid(model));
                lockSandboxDirs(codexHome, model);
                return;
            }
            SetupMarker.prepare(codexHome, model.realUser); // 两阶段：空 sentinel 先行
            provisionAccountsAndNetwork(payload, model, codexHome);
            applyAcls(payload, codexHome, ensureGroupSid(model));
            lockSandboxDirs(codexHome, model);
            SetupMarker.commit(codexHome, SetupPayload.SETUP_VERSION, model.offlineUsername,
                    model.onlineUsername, model.proxyPorts, model.allowLocalBinding);
        }
    }

    /** 账户 + 网络（对齐 provision_sandbox；修复路径不变量④：WFP 恢复成功才解禁）。 */
    private static void provisionAccountsAndNetwork(SetupPayload payload,
            SetupPayload.Model model, Path codexHome) throws IOException {
        boolean repairing = false;
        for (String username : List.of(model.offlineUsername, model.onlineUsername)) {
            Integer flags = SandboxAccounts.localUserFlags(username);
            if (flags != null && (flags & NetApi32Ex.UF_ACCOUNTDISABLE) != 0) {
                repairing = true; // 中断清理的残留：新建/重置账户也保持禁用
            }
        }
        int newUserFlags = repairing ? NetApi32Ex.UF_ACCOUNTDISABLE : 0;

        String groupSid = SandboxAccounts.ensureGroup(model.groupName);
        String offlinePassword = SandboxAccounts.randomPassword();
        String onlinePassword = SandboxAccounts.randomPassword();
        try {
            SandboxAccounts.ensureUser(model.offlineUsername, offlinePassword, newUserFlags,
                    model.groupName);
            SandboxAccounts.ensureUser(model.onlineUsername, onlinePassword, newUserFlags,
                    model.groupName);
        } catch (RuntimeException e) {
            throw new SetupErrorReport.SetupException(
                    SetupErrorReport.HELPER_USER_CREATE_OR_UPDATE_FAILED, e.getMessage());
        }
        try {
            SandboxSecrets.write(codexHome, SetupPayload.SETUP_VERSION,
                    model.offlineUsername, offlinePassword,
                    model.onlineUsername, onlinePassword);
        } catch (Exception e) {
            throw new SetupErrorReport.SetupException(
                    SetupErrorReport.HELPER_DPAPI_PROTECT_FAILED, e.getMessage());
        }
        HideUsers.hide(new String[] { model.offlineUsername, model.onlineUsername });

        String offlineSid = SandboxAccounts.sidString(model.offlineUsername);
        FirewallInstaller.ensureOfflineProxyAllowlist(offlineSid, model.proxyPorts,
                model.allowLocalBinding);
        FirewallInstaller.ensureOfflineNetworkBlocks(offlineSid);

        try {
            WfpInstaller.installForAccount(model.offlineUsername);
        } catch (RuntimeException e) {
            // 修复路径必须恢复 WFP 才能解禁（防半修复状态放开被封锁账户）；
            // 普通路径按 codex best-effort（防火墙已挡大部分流量），只记日志。
            if (repairing) {
                throw new SetupErrorReport.SetupException(
                        SetupErrorReport.HELPER_WFP_INSTALL_FAILED, e.getMessage(), e);
            }
            LOG.log(System.Logger.Level.WARNING, "WFP install skipped (best-effort): {0}",
                    e.getMessage());
        }
        if (repairing) {
            for (String username : List.of(model.offlineUsername, model.onlineUsername)) {
                Integer flags = SandboxAccounts.localUserFlags(username);
                if (flags != null) {
                    SandboxAccounts.setLocalUserFlags(username,
                            flags & ~NetApi32Ex.UF_ACCOUNTDISABLE);
                }
            }
        }
    }

    /** ACL 授权阶段（{@link AclApplier} 由 acl 包在步骤 6c 注册实现）。 */
    private static void applyAcls(SetupPayload payload, Path codexHome, String groupSid)
            throws IOException {
        var appliers = ServiceLoader.load(AclApplier.class,
                SetupHelperMain.class.getClassLoader()).iterator();
        if (!appliers.hasNext()) {
            LOG.log(System.Logger.Level.INFO,
                    "no AclApplier registered; skipping ACL provisioning stage");
            return;
        }
        CapSids capSids = CapSids.loadOrCreate(codexHome);
        while (appliers.hasNext()) {
            try {
                appliers.next().applyProvisioning(payload, groupSid, capSids);
            } catch (Exception e) {
                throw new SetupErrorReport.SetupException(SetupErrorReport.HELPER_ACL_APPLY_FAILED,
                        e.getMessage(), e);
            }
        }
    }

    private static String ensureGroupSid(SetupPayload.Model model) {
        try {
            return SandboxAccounts.sidString(model.groupName);
        } catch (RuntimeException e) {
            throw new SetupErrorReport.SetupException(
                    SetupErrorReport.HELPER_SID_RESOLVE_FAILED,
                    "resolve sandbox users group SID failed: " + e.getMessage());
        }
    }

    private static void lockSandboxDirs(Path codexHome, SetupPayload.Model model) {
        String groupSid = SandboxAccounts.sidString(model.groupName);
        SandboxDirs.lockBinDir(codexHome, groupSid, model.realUser);
        SandboxDirs.lockSandboxDir(codexHome, groupSid, model.realUser);
        SandboxDirs.lockSecretsDir(codexHome, groupSid, model.realUser);
        SandboxSecrets.denyGroupFullAccess(SandboxDirs.sandboxSecretsDir(codexHome), groupSid);
    }

    private static void writeErrorReport(SetupPayload payload, String code, String message) {
        try {
            SetupErrorReport.write(Path.of(payload.model().codexHome), code, message);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.ERROR, "setup error report write failed: {0}",
                    e.getMessage());
        }
    }
}
