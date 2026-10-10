package dev.everyagent.plugin.sandbox.codex.win;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.LMAccess;
import com.sun.jna.platform.win32.Netapi32;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.W32APIOptions;

/**
 * netapi32 补齐映射（设计文档 §2.1/§3）。
 *
 * <p>jna-platform 5.16 的 {@link Netapi32} 已提供 NetUserAdd/NetUserDel/NetUserGetInfo、
 * NetApiBufferFree、NetGetDCName，结构体 {@link LMAccess} 已提供
 * USER_INFO_1/USER_INFO_23、LOCALGROUP_INFO_0/LOCALGROUP_INFO_1（W 布局一致）——直接复用；
 * 本接口补齐本地组与 SetInfo 系列。
 *
 * <p>注意：NET_API_STATUS（int 返回值）不是 Win32 错误码，不走 GetLastError；
 * NERR_* 常量见 {@link WinErr}；缓冲区必须用 NetApiBufferFree 释放（与 LocalFree 不可混用）。
 */
public interface NetApi32Ex extends Netapi32 {

    NetApi32Ex INSTANCE = Native.load("netapi32", NetApi32Ex.class, W32APIOptions.UNICODE_OPTIONS);

    /** NetUserAdd 信息级：{@link LMAccess#USER_INFO_1}（建户，flags+密码一次到位）。 */
    int USER_INFO_LEVEL_1 = 1;
    /** NetUserSetInfo 信息级：{@link LMAccess#USER_INFO_1}（只改 flags，如先禁用后解禁）。 */
    int USER_INFO_LEVEL_FLAGS = 1;
    /** NetUserSetInfo 信息级：{@link USER_INFO_1003}（只重置密码，不动 flags——密码轮换语义）。 */
    int USER_INFO_LEVEL_PASSWORD = 1003;
    /** NetLocalGroupAdd 信息级：{@link LMAccess#LOCALGROUP_INFO_1}（组名+注释）。 */
    int LOCALGROUP_INFO_LEVEL_1 = 1;
    /** NetLocalGroupAddMembers/DelMembers 信息级：{@link LOCALGROUP_MEMBERS_INFO_0}（PSID 数组）。 */
    int LOCALGROUP_MEMBERS_LEVEL_SID = 0;
    /** NetLocalGroupAddMembers/DelMembers 信息级：{@link LOCALGROUP_MEMBERS_INFO_3}（域\名）。 */
    int LOCALGROUP_MEMBERS_LEVEL_NAME = 3;

    // ---- 账户 flags（USER_INFO_1.usri1_flags，winutil.rs 用） ----

    /** 账户禁用（修复路径先禁用、网络限制就绪后才解禁）。 */
    int UF_ACCOUNTDISABLE = 0x00000002;
    /** 登录脚本必需（NetUserAdd 的惯例位）。 */
    int UF_SCRIPT = 0x00000001;
    /** 密码永不过期（沙箱账户凭据由 DPAPI 保管）。 */
    int UF_DONT_EXPIRE_PASSWD = 0x00010000;
    /** 密码已过期（readiness 检查识别的中间态）。 */
    int UF_PASSWORD_EXPIRED = 0x80000000;

    /**
     * NetUserSetInfo——对应 sandbox_users.rs：账户已存在时 level 1 只重置 flags、
     * level 1003 只重置密码（flags 可能属企业策略所有，两步分开防止中间态丢凭据）。
     * buf 传 Structure.getPointer()；parmError 可为 null。
     */
    int NetUserSetInfo(String servername, String username, int level, Pointer buf,
            IntByReference parmError);

    /** NetLocalGroupAdd——winutil.rs::ensure_sandbox_users_group（已存在即 NERR_GroupExists，视为成功）。 */
    int NetLocalGroupAdd(String servername, int level, LMAccess.LOCALGROUP_INFO_1 buf,
            IntByReference parmError);

    /**
     * NetLocalGroupAddMembers——ensure_local_group_member：把沙箱账户加入
     * 沙箱组（{@code <前缀>SandboxUsers}，见
     * {@link dev.everyagent.plugin.sandbox.codex.accounts.SandboxAccounts#groupName}）
     * 与内建 Users（S-1-5-32-545），level 0 传 PSID 数组、
     * level 3 传「域\名」字符串数组（调用方平铺进 Memory，本处按 Pointer 传首地址）。
     */
    int NetLocalGroupAddMembers(String servername, String groupname, int level, Pointer buf,
            int totalentries);

    /** NetLocalGroupDelMembers——卸载/修复时移出成员（卸载主路径整组删除，用得少）。 */
    int NetLocalGroupDelMembers(String servername, String groupname, int level, Pointer buf,
            int totalentries);

    /** NetLocalGroupDel——principals.rs::remove_sandbox_principal（容 NERR_GroupNotFound）。 */
    int NetLocalGroupDel(String servername, String groupname);

    /** NetGetAnyDCName——域环境探测（返回的缓冲区 NetApiBufferFree 释放；视需要）。 */
    int NetGetAnyDCName(String servername, String domainname, PointerByReference buf);

    // ---- jna-platform 未收录的结构体 ----

    /** NetUserSetInfo level 1003：usri1003_password（UTF-16 指针，须钉在 Memory 上）。 */
    @com.sun.jna.Structure.FieldOrder({ "usri1003_password" })
    final class USER_INFO_1003 extends com.sun.jna.Structure {
        public String usri1003_password;
    }

    /** NetLocalGroupAdd/DelMembers level 0：成员 SID。 */
    @com.sun.jna.Structure.FieldOrder({ "lgrmi0_sid" })
    final class LOCALGROUP_MEMBERS_INFO_0 extends com.sun.jna.Structure {
        public Pointer lgrmi0_sid;
    }

    /** NetLocalGroupAdd/DelMembers level 3：成员「域\名」。 */
    @com.sun.jna.Structure.FieldOrder({ "lgrui3_domainandname" })
    final class LOCALGROUP_MEMBERS_INFO_3 extends com.sun.jna.Structure {
        public String lgrui3_domainandname;
    }
}
