package dev.everyagent.plugin.sandbox.codex.accounts;

import com.sun.jna.Platform;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * SandboxAccounts 单测：跨平台部分（命名约定/offline-online 选择/密码形态）
 * + Windows 专属部分（NetUser 与 LookupAccount 系列）assumeTrue 守卫骨架（设计 §8）。
 */
class SandboxAccountsTest {

    @Test
    void accountNamesFollowNamingConvention() {
        assertEquals("EveryAgentCodexOffline", SandboxAccounts.offlineUsername(
                SandboxAccounts.DEFAULT_PREFIX));
        assertEquals("EveryAgentCodexOnline", SandboxAccounts.onlineUsername(
                SandboxAccounts.DEFAULT_PREFIX));
        assertEquals("EveryAgentCodexSandboxUsers", SandboxAccounts.groupName(
                SandboxAccounts.DEFAULT_PREFIX));
        assertEquals("CustomOffline", SandboxAccounts.offlineUsername("Custom"));
    }

    @Test
    void networkOfflineSelectsOfflineIdentity() {
        // 对齐 SandboxNetworkIdentity::from_permissions：断网/强制代理 → Offline
        assertEquals(SandboxAccounts.NetworkIdentity.OFFLINE,
                SandboxAccounts.NetworkIdentity.from(true));
        assertEquals(SandboxAccounts.NetworkIdentity.ONLINE,
                SandboxAccounts.NetworkIdentity.from(false));
        assertEquals("EveryAgentCodexOffline", SandboxAccounts.usernameFor(
                SandboxAccounts.DEFAULT_PREFIX, SandboxAccounts.NetworkIdentity.OFFLINE));
        assertEquals("EveryAgentCodexOnline", SandboxAccounts.usernameFor(
                SandboxAccounts.DEFAULT_PREFIX, SandboxAccounts.NetworkIdentity.ONLINE));
    }

    @Test
    void randomPasswordMatchesCodexShape() {
        for (int i = 0; i < 32; i++) {
            String password = SandboxAccounts.randomPassword();
            assertEquals(24, password.length(), "24 字符（对齐 random_password）");
            assertTrue(password.chars().allMatch(c ->
                    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*()-_=+"
                            .indexOf(c) >= 0), "字符表内: " + password);
        }
    }

    // ---- Windows 专属（assumeTrue 守卫；设计 §8） ----

    @Test
    void windowsEnsureGroupAndUserRoundTrip() {
        Assumptions.assumeTrue(Platform.isWindows(), "Windows 专属");
        // TODO(windows-admin)：提权环境断言——建组/建户/flags 读写/删除；
        //  非 admin 环境下 NetLocalGroupAdd 会被拒（fail-closed 抛 IllegalStateException）。
        try {
            String sid = SandboxAccounts.ensureGroup("EveryAgentCodexTestGroup");
            assertTrue(sid.startsWith("S-1-5-21-") || sid.startsWith("S-1-12-1-"),
                    "本地组 SID: " + sid);
        } catch (RuntimeException e) {
            fail("ensureGroup 需管理员权限运行: " + e.getMessage());
        }
    }

    @Test
    void windowsLocalUserFlagsReturnsNullForMissingAccount() {
        Assumptions.assumeTrue(Platform.isWindows(), "Windows 专属");
        assertEquals(null, SandboxAccounts.localUserFlags(
                "EveryAgentCodexNoSuchUser_" + System.nanoTime()));
    }

    @Test
    void windowsSidStringResolvesBuiltinUsers() {
        Assumptions.assumeTrue(Platform.isWindows(), "Windows 专属");
        String sid = SandboxAccounts.sidString("Users");
        assertEquals(SandboxAccounts.SID_BUILTIN_USERS, sid, "内建 Users S-1-5-32-545");
    }
}
