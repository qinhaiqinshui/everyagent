package dev.everyagent.plugin.sandbox.codex.setup;

import dev.everyagent.plugin.sandbox.codex.accounts.SandboxAccounts;
import dev.everyagent.plugin.sandbox.codex.fw.FirewallInstaller;
import dev.everyagent.plugin.sandbox.codex.fw.WfpInstaller;
import dev.everyagent.plugin.sandbox.codex.win.NetApi32Ex;
import dev.everyagent.plugin.sandbox.codex.win.UserenvEx;
import dev.everyagent.plugin.sandbox.codex.win.WinErr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.sun.jna.platform.win32.Netapi32;

/**
 * 两阶段卸载（对应 codex uninstall_windows.rs；分析文档 §5.3，不变量⑤）。
 *
 * <p>阶段一 {@link #prepare}：锁（短超时 5s，setup 正在跑则报错）→ 记录 original
 * flags+SID 后 {@code flags|UF_ACCOUNTDISABLE} 禁登录 → 停沙箱账户进程（按 SID 枚举，
 * 本步骤留 TODO 给 6c，当前 no-op）→ 任一步失败还原 flags（此时未移除任何保护）。
 *
 * <p>阶段二 {@link Prepared#finish}（持锁 + 禁用态）：validate_current（SID 未变且仍
 * 禁用，防同名顶替）→ 删三目录 → 删 WFP → 删防火墙规则 → unhide → DeleteProfileW +
 * NetUserDel → 无错误才 NetLocalGroupDel。资源删除顺序保证：先停进程后删保护——
 * 中途失败不会出现「账户可登录但无网络限制」的窗口（除非账户仍禁用，保护永不先于
 * 禁用解除）；每步独立容错、错误聚合。
 *
 * <p>须在提权上下文调用（worker 侧经 {@code SetupOrchestrator.ensureSetup} 的
 * Remove 模式走同一 UAC helper 通道）。
 */
public final class Uninstaller {

    /** prepare 的锁等待（5s：setup 正在跑则快速失败）。 */
    private static final int PREPARE_LOCK_TIMEOUT_MS = 5_000;

    private Uninstaller() {
    }

    /** 一次性两阶段卸载（对齐 clean_up_packaged_windows_sandbox）。 */
    public static void run(SetupPayload payload) {
        try (Prepared prepared = prepare(payload)) {
            List<String> errors = prepared.finish(payload);
            if (!errors.isEmpty()) {
                throw new SetupErrorReport.SetupException(SetupErrorReport.HELPER_UNKNOWN_ERROR,
                        String.join("; ", errors));
            }
        }
    }

    /** 阶段一：禁用账户 + 停进程（不删任何资源；失败自动还原 flags）。 */
    public static Prepared prepare(SetupPayload payload) {
        SetupPayload.Model model = payload.model();
        SetupLock lock = SetupLock.acquire(PREPARE_LOCK_TIMEOUT_MS);
        List<CapturedUser> users = new ArrayList<>();
        try {
            for (String username : List.of(model.offlineUsername, model.onlineUsername)) {
                Integer flags = SandboxAccounts.localUserFlags(username);
                if (flags == null) {
                    continue; // 账户不存在：已清
                }
                String sid = SandboxAccounts.sidString(username);
                users.add(new CapturedUser(username, flags, sid));
                SandboxAccounts.setLocalUserFlags(username, flags | NetApi32Ex.UF_ACCOUNTDISABLE);
            }
            stopSandboxProcesses(users);
        } catch (RuntimeException e) {
            List<String> errors = new ArrayList<>(List.of(e.getMessage()));
            for (CapturedUser user : users) {
                try {
                    SandboxAccounts.setLocalUserFlags(user.username, user.originalFlags);
                } catch (RuntimeException restoreError) {
                    errors.add(restoreError.getMessage());
                }
            }
            lock.close();
            throw new SetupErrorReport.SetupException(SetupErrorReport.HELPER_UNKNOWN_ERROR,
                    String.join("; ", errors));
        }
        return new Prepared(lock, users, model);
    }

    /**
     * 停沙箱账户进程（uninstall_windows/processes.rs 语义：按 token user SID 枚举
     * 进程并 TerminateProcess）。TODO(6c)：进程域接入后实现；当前 no-op——
     * 账户已禁用（无法新登录），残留进程随 runner 会话收束。
     */
    private static void stopSandboxProcesses(List<CapturedUser> users) {
        if (!users.isEmpty()) {
            System.getLogger(Uninstaller.class.getName()).log(System.Logger.Level.INFO,
                    "process stop for sandbox accounts deferred to step 6c (accounts disabled)");
        }
    }

    /** 已捕获的禁用账户（finish 阶段复验身份防同名顶替）。 */
    private record CapturedUser(String username, int originalFlags, String sid) {
    }

    /** prepare 与 finish 之间的静止态守卫（账户禁用 + 锁持有；close 只释放锁）。 */
    public static final class Prepared implements AutoCloseable {
        private final SetupLock lock;
        private final List<CapturedUser> users;
        private final SetupPayload.Model model;

        private Prepared(SetupLock lock, List<CapturedUser> users, SetupPayload.Model model) {
            this.lock = lock;
            this.users = users;
            this.model = model;
        }

        /** 阶段二：逐步删资源（每步独立容错，错误聚合返回）。 */
        public List<String> finish(SetupPayload payload) {
            List<String> errors = new ArrayList<>();
            validateCurrent(errors);
            removeSandboxDirs(errors);
            try {
                WfpInstaller.remove();
            } catch (RuntimeException e) {
                errors.add("remove WFP filters: failed, " + e.getMessage());
            }
            try {
                FirewallInstaller.removeSandboxRules();
            } catch (RuntimeException e) {
                errors.add("remove firewall rules: failed, " + e.getMessage());
            }
            try {
                HideUsers.unhide(new String[] { model.offlineUsername, model.onlineUsername });
            } catch (RuntimeException e) {
                errors.add("remove hidden-user entries: failed, " + e.getMessage());
            }
            removeUsers(errors);
            if (errors.isEmpty()) {
                int status = NetApi32Ex.INSTANCE.NetLocalGroupDel(null, model.groupName);
                if (status != WinErr.NERR_Success && status != WinErr.NERR_GroupNotFound) {
                    errors.add("remove sandbox group: failed, NetLocalGroupDel code " + status);
                }
            }
            return errors;
        }

        /** 复验（对齐 validate_current：SID 不变且仍禁用，防中途被替换/复用）。 */
        private void validateCurrent(List<String> errors) {
            for (CapturedUser user : users) {
                Integer flags = SandboxAccounts.localUserFlags(user.username);
                if (flags == null) {
                    continue;
                }
                boolean stillDisabled = (flags & NetApi32Ex.UF_ACCOUNTDISABLE) != 0;
                boolean sameIdentity;
                try {
                    sameIdentity = user.sid.equals(SandboxAccounts.sidString(user.username));
                } catch (RuntimeException e) {
                    sameIdentity = false;
                }
                if (!stillDisabled || !sameIdentity) {
                    errors.add("sandbox account changed after being disabled: " + user.username);
                }
            }
        }

        private void removeSandboxDirs(List<String> errors) {
            Path codexHome = Path.of(model.codexHome);
            for (Path dir : List.of(SandboxDirs.sandboxDir(codexHome),
                    SandboxDirs.sandboxSecretsDir(codexHome), SandboxDirs.sandboxBinDir(codexHome))) {
                try {
                    deleteRecursively(dir);
                } catch (NoSuchFileException ignored) {
                    // 已清
                } catch (IOException e) {
                    errors.add("remove " + dir.getFileName() + ": failed, " + e.getMessage());
                }
            }
        }

        private void removeUsers(List<String> errors) {
            for (CapturedUser user : users) {
                // profile 不存在/DeleteProfileW 失败不阻断账户删除（错误聚合）
                int profile = UserenvEx.INSTANCE.DeleteProfileW(user.sid, null, null);
                if (profile == 0) {
                    System.getLogger(Prepared.class.getName()).log(System.Logger.Level.WARNING,
                            "DeleteProfileW for {0} returned 0 (profile may be absent)",
                            user.username);
                }
                int status = Netapi32.INSTANCE.NetUserDel(null, user.username);
                if (status != WinErr.NERR_Success && status != WinErr.NERR_UserNotFound) {
                    errors.add("remove sandbox user " + user.username + ": failed, code "
                            + status);
                }
            }
        }

        @Override
        public void close() {
            lock.close();
        }
    }

    /** Files.deleteRecursive 尚无标准 API（JDK 25 前置目录流遍历）。 */
    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            throw new NoSuchFileException(dir.toString());
        }
        try (var stream = Files.walk(dir)) {
            var paths = stream.sorted(java.util.Comparator.reverseOrder()).toList();
            for (Path p : paths) {
                Files.deleteIfExists(p);
            }
        }
    }
}
