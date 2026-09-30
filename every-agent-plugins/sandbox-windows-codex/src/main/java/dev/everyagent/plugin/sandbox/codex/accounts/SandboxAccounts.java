package dev.everyagent.plugin.sandbox.codex.accounts;

import com.sun.jna.Structure;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.LMAccess;
import com.sun.jna.platform.win32.Netapi32;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

import dev.everyagent.plugin.sandbox.codex.win.NetApi32Ex;
import dev.everyagent.plugin.sandbox.codex.win.WinErr;

import java.security.SecureRandom;

/**
 * 沙箱本地账户/组供给（对应 codex setup_provisioning/sandbox_users.rs + winutil.rs，
 * 设计文档 §2.3 AccountProvisioner）。
 *
 * <p>命名约定：组 {@code EveryAgentCodexSandboxUsers}，账户
 * {@code EveryAgentCodexOffline}/{@code EveryAgentCodexOnline}（前缀可配置）。
 * 修复路径（不变量④）：检测到禁用账户残留时新建账户带 UF_ACCOUNTDISABLE，
 * 网络限制恢复成功后才解禁。
 *
 * <p>NET_API_STATUS 非 Win32 错误码（不走 GetLastError）；缓冲区 NetApiBufferFree 释放。
 */
public final class SandboxAccounts {

    /** 默认账户/组前缀（设计文档 §7 codex.account-prefix）。 */
    public static final String DEFAULT_PREFIX = "EveryAgentCodex";
    /** 沙箱组名（对齐 SANDBOX_USERS_GROUP）。 */
    public static final String DEFAULT_GROUP = DEFAULT_PREFIX + "SandboxUsers";
    private static final String GROUP_COMMENT = "EveryAgent Codex sandbox internal group (managed)";
    /** 内建 Users 组 SID（ensure_local_user 的普通用户主体保障）。 */
    public static final String SID_BUILTIN_USERS = "S-1-5-32-545";

    /** USER_INFO_1.usri1_priv：普通用户。 */
    private static final int USER_PRIV_USER = 1;
    /** NetUserSetInfo level 1008：只改 flags（禁用/解禁，对齐 winutil.rs set_local_user_flags）。 */
    private static final int USER_INFO_LEVEL_1008 = 1008;
    /** NetUserGetInfo level 1。 */
    private static final int USER_INFO_LEVEL_1 = 1;

    private static final String PASSWORD_CHARS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*()-_=+";
    private static final int PASSWORD_LEN = 24;
    private static final SecureRandom RNG = new SecureRandom();

    /** 网络身份（对齐 SandboxNetworkIdentity：offline 断网账户 / online 联网账户）。 */
    public enum NetworkIdentity {
        OFFLINE, ONLINE;

        /** 对齐 from_permissions：networkOffline（断网或强制代理）→ Offline。 */
        public static NetworkIdentity from(boolean networkOffline) {
            return networkOffline ? OFFLINE : ONLINE;
        }
    }

    private SandboxAccounts() {
    }

    /** offline 账户名。 */
    public static String offlineUsername(String prefix) {
        return prefix + "Offline";
    }

    /** online 账户名。 */
    public static String onlineUsername(String prefix) {
        return prefix + "Online";
    }

    /** 组名（默认前缀 = {@code <prefix>SandboxUsers}）。 */
    public static String groupName(String prefix) {
        return prefix + "SandboxUsers";
    }

    /** 按网络身份取账户名。 */
    public static String usernameFor(String prefix, NetworkIdentity identity) {
        return identity == NetworkIdentity.OFFLINE ? offlineUsername(prefix) : onlineUsername(prefix);
    }

    /** 建组（对齐 ensure_sandbox_users_group：已存在视为成功，返回组 SID 字符串）。 */
    public static String ensureGroup(String groupName) {
        LMAccess.LOCALGROUP_INFO_1 info = new LMAccess.LOCALGROUP_INFO_1();
        info.lgrui1_name = groupName;
        info.lgrui1_comment = GROUP_COMMENT;
        info.write();
        int status = NetApi32Ex.INSTANCE.NetLocalGroupAdd(null,
                NetApi32Ex.LOCALGROUP_INFO_LEVEL_1, info, new IntByReference());
        if (status != WinErr.NERR_Success && status != WinErr.NERR_GroupExists
                && status != WinErr.ERROR_ALIAS_EXISTS) {
            throw new IllegalStateException("NetLocalGroupAdd failed for " + groupName
                    + " code " + status);
        }
        return sidString(groupName);
    }

    /**
     * 建户（对齐 ensure_local_user）：NetUserAdd USER_INFO_1
     * （UF_SCRIPT|UF_DONT_EXPIRE_PASSWD|extraFlags，密码一次到位）；失败回退
     * NetUserSetInfo(1003) 只重置密码（flags 可能属企业策略所有，两步分开防中间态）。
     * 随后幂等加入沙箱组与内建 Users。
     */
    public static void ensureUser(String username, String password, int extraFlags,
            String groupName) {
        LMAccess.USER_INFO_1 info = new LMAccess.USER_INFO_1();
        info.usri1_name = username;
        info.usri1_password = password;
        info.usri1_password_age = 0;
        info.usri1_priv = USER_PRIV_USER;
        info.usri1_home_dir = null;
        info.usri1_comment = null;
        info.usri1_flags = NetApi32Ex.UF_SCRIPT | NetApi32Ex.UF_DONT_EXPIRE_PASSWD | extraFlags;
        info.usri1_script_path = null;
        info.write();
        int status = Netapi32.INSTANCE.NetUserAdd(null, USER_INFO_LEVEL_1, info,
                new IntByReference());
        if (status != WinErr.NERR_Success) {
            NetApi32Ex.USER_INFO_1003 pw = new NetApi32Ex.USER_INFO_1003();
            pw.usri1003_password = password;
            pw.write();
            int upd = NetApi32Ex.INSTANCE.NetUserSetInfo(null, username,
                    NetApi32Ex.USER_INFO_LEVEL_PASSWORD, pw.getPointer(), null);
            if (upd != WinErr.NERR_Success) {
                throw new IllegalStateException("failed to create/update user " + username
                        + ", code " + status + "/" + upd);
            }
        }
        ensureGroupMember(groupName, username);
        String builtinUsers = builtinUsersGroupName();
        if (builtinUsers != null) {
            // 幂等：已在组中时的错误码按 codex 忽略（ensure_local_user 同款）。
            addMemberIgnoreAlreadyPresent(builtinUsers, username);
        }
    }

    /** 把成员加入本地组（对齐 ensure_local_group_member：level 3 域\名，幂等）。 */
    public static void ensureGroupMember(String groupName, String memberName) {
        addMemberIgnoreAlreadyPresent(groupName, memberName);
    }

    private static void addMemberIgnoreAlreadyPresent(String groupName, String memberName) {
        NetApi32Ex.LOCALGROUP_MEMBERS_INFO_3[] members =
                (NetApi32Ex.LOCALGROUP_MEMBERS_INFO_3[])
                        new NetApi32Ex.LOCALGROUP_MEMBERS_INFO_3().toArray(1);
        members[0].lgrui3_domainandname = memberName;
        members[0].write();
        // 返回值忽略：成员已存在等错误码按 codex 语义不影响结果。
        NetApi32Ex.INSTANCE.NetLocalGroupAddMembers(null, groupName,
                NetApi32Ex.LOCALGROUP_MEMBERS_LEVEL_NAME, members[0].getPointer(), 1);
    }

    /** LookupAccountName 取 SID 字符串（对齐 resolve_sid → string_from_sid_bytes）。 */
    public static String sidString(String accountName) {
        Advapi32Util.Account account = Advapi32Util.getAccountByName(accountName);
        if (account.sidString == null) {
            throw new IllegalStateException("resolve SID for " + accountName + " failed");
        }
        return account.sidString;
    }

    /** 读账户 flags（对齐 local_user_flags：不存在返回 null）。 */
    public static Integer localUserFlags(String username) {
        PointerByReference buf = new PointerByReference();
        int status = Netapi32.INSTANCE.NetUserGetInfo(null, username, USER_INFO_LEVEL_1, buf);
        if (status == WinErr.NERR_UserNotFound) {
            return null;
        }
        if (status != WinErr.NERR_Success) {
            throw new IllegalStateException("read local sandbox user " + username
                    + " failed, code " + status);
        }
        try {
            LMAccess.USER_INFO_1 info = new LMAccess.USER_INFO_1(buf.getValue());
            info.read();
            return info.usri1_flags;
        } finally {
            Netapi32.INSTANCE.NetApiBufferFree(buf.getValue());
        }
    }

    /** 只改账户 flags（对齐 set_local_user_flags：level 1008，禁用/解禁共用）。 */
    public static void setLocalUserFlags(String username, int flags) {
        UserInfo1008 info = new UserInfo1008();
        info.usri1008_flags = flags;
        info.write();
        int status = NetApi32Ex.INSTANCE.NetUserSetInfo(null, username,
                USER_INFO_LEVEL_1008, info.getPointer(), null);
        if (status != WinErr.NERR_Success) {
            throw new IllegalStateException("set local sandbox user " + username
                    + " flags failed, code " + status);
        }
    }

    /** 生成 24 字符随机密码（对齐 random_password 字符表）。 */
    public static String randomPassword() {
        StringBuilder sb = new StringBuilder(PASSWORD_LEN);
        for (int i = 0; i < PASSWORD_LEN; i++) {
            sb.append(PASSWORD_CHARS.charAt(RNG.nextInt(PASSWORD_CHARS.length())));
        }
        return sb.toString();
    }

    /** 内建 Users 组本地化名（LookupAccountSidW(S-1-5-32-545)）。 */
    private static String builtinUsersGroupName() {
        try {
            return Advapi32Util.getAccountBySid(SID_BUILTIN_USERS).name;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** NetUserSetInfo level 1008 载荷（jna-platform 未收录；4 字节平铺）。 */
    @Structure.FieldOrder({ "usri1008_flags" })
    private static final class UserInfo1008 extends Structure {
        public int usri1008_flags;
    }
}
