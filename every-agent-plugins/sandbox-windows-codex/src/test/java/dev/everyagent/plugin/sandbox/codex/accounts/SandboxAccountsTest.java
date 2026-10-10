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
        // 期望值从前缀派生：前缀曾因 EveryAgentCodexOffline 长 22 超 SAM 20 上限
        // （NetUserAdd 直接拒绝）而缩短为 EACodex，硬编码字面量会在下次改前缀时再次腐烂
        String p = SandboxAccounts.DEFAULT_PREFIX;
        assertEquals(p + "Offline", SandboxAccounts.offlineUsername(p));
        assertEquals(p + "Online", SandboxAccounts.onlineUsername(p));
        assertEquals(p + "SandboxUsers", SandboxAccounts.groupName(p));
        assertEquals("CustomOffline", SandboxAccounts.offlineUsername("Custom"));
    }

    /**
     * 护栏：默认前缀派生的账户名/组名必须落在 Windows SAM 20 字符上限内。
     * 历史教训：前缀为 EveryAgentCodex 时 EveryAgentCodexOffline 长 22，
     * {@code ensureUser} 直接抛异常（NetUserAdd 也会拒绝），故缩短为 EACodex。
     * 组名当前 19 字符已贴边，前缀再加长即溢出——把约束固定成断言，不靠人工记忆。
     */
    @Test
    void defaultDerivedNamesFitSamNameLimit() {
        String p = SandboxAccounts.DEFAULT_PREFIX;
        for (String name : java.util.List.of(
                SandboxAccounts.offlineUsername(p),
                SandboxAccounts.onlineUsername(p),
                SandboxAccounts.groupName(p))) {
            assertTrue(name.length() <= SandboxAccounts.MAX_USERNAME_LEN,
                    name + " (" + name.length() + " 字符) 超过 SAM 上限 "
                            + SandboxAccounts.MAX_USERNAME_LEN);
        }
    }

    @Test
    void networkOfflineSelectsOfflineIdentity() {
        // 对齐 SandboxNetworkIdentity::from_permissions：断网/强制代理 → Offline
        assertEquals(SandboxAccounts.NetworkIdentity.OFFLINE,
                SandboxAccounts.NetworkIdentity.from(true));
        assertEquals(SandboxAccounts.NetworkIdentity.ONLINE,
                SandboxAccounts.NetworkIdentity.from(false));
        String p = SandboxAccounts.DEFAULT_PREFIX;
        assertEquals(p + "Offline", SandboxAccounts.usernameFor(
                p, SandboxAccounts.NetworkIdentity.OFFLINE));
        assertEquals(p + "Online", SandboxAccounts.usernameFor(
                p, SandboxAccounts.NetworkIdentity.ONLINE));
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
