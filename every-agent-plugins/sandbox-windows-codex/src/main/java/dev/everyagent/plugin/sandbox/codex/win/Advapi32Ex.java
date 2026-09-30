package dev.everyagent.plugin.sandbox.codex.win;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.Advapi32;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinReg;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.W32APIOptions;

import dev.everyagent.plugin.sandbox.codex.win.struct.AclStructs.EXPLICIT_ACCESS_W;

/**
 * advapi32 补齐映射（设计文档 §2.1/§3）。
 *
 * <p>jna-platform 5.16 的 {@link Advapi32} 已提供 CreateProcessWithLogonW、LogonUser、
 * OpenProcessToken、GetTokenInformation、AdjustTokenPrivileges、LookupPrivilegeValue、
 * LookupAccountName/Sid、ConvertSidToStringSid/ConvertStringSidToSid、
 * Get/SetNamedSecurityInfo、Get/SetSecurityInfo、RegCreateKeyExW/RegSetValueExW/
 * RegDeleteValueW——直接复用；本接口补齐其余自映射原语
 * （对照 mic 的 Win32Ex.SandboxAdvapi32 惯用法）。
 */
public interface Advapi32Ex extends Advapi32 {

    Advapi32Ex INSTANCE = Native.load("advapi32", Advapi32Ex.class, W32APIOptions.UNICODE_OPTIONS);

    /**
     * CreateRestrictedToken——对应 token.rs::create_token_with_caps_from：
     * flags = {@link #DISABLE_MAX_PRIVILEGE}|{@link #LUA_TOKEN}|{@link #WRITE_RESTRICTED}
     * （0x01|0x04|0x08）；restricting 数组顺序 caps → 额外（令牌 user SID 首位）→
     * logon SID → Everyone，全部 Attributes=0。数组以 {@code Memory} 平铺传入
     * （SID ≤68 字节、Sid 为裸 Pointer 防 JNA 改写目标内存，同 mic 的
     * TOKEN_MANDATORY_LABEL 教训），disable/delete 两数组传 null。
     */
    boolean CreateRestrictedToken(WinNT.HANDLE hExistingToken, int flags,
            int disableSidCount, Pointer sidToDisable,
            int deletePrivCount, Pointer privilegesToDelete,
            int restrictSidCount, Pointer sidsToRestrict,
            WinNT.HANDLEByReference phNewToken);

    /**
     * SetTokenInformation——对应 token.rs::set_default_dacl：信息类
     * {@link #TokenDefaultDacl}（logon SID GENERIC_ALL + OWNER RIGHTS S-1-3-4 仅
     * READ_CONTROL）。载荷声明为 Structure：JNA 对 Structure 参数自动 write/read 同步
     * （声明为 Pointer 则不会同步，内核解引用垃圾指针报 ERROR_NOACCESS=998）。
     */
    boolean SetTokenInformation(WinNT.HANDLE tokenHandle, int tokenInformationClass,
            Structure tokenInformation, int tokenInformationLength);

    /** 构造 well-known SID（OWNER RIGHTS S-1-3-4、Everyone 等，acl.rs/token.rs 用）。 */
    boolean AllocateAndInitializeSid(Pointer pIdentifierAuthority, byte nSubAuthority,
            int dwSubAuthority0, int dwSubAuthority1, int dwSubAuthority2, int dwSubAuthority3,
            int dwSubAuthority4, int dwSubAuthority5, int dwSubAuthority6, int dwSubAuthority7,
            PointerByReference pSid);

    /** 释放 AllocateAndInitializeSid 产物（其内存不在堆上）。 */
    void FreeSid(Pointer pSid);

    /** 判断 SID 是否在令牌组内——logon SID/capability SID 快速自检（token_groups.rs 语义）。 */
    boolean CheckTokenMembership(WinNT.HANDLE tokenHandle, Pointer sidToCheck,
            IntByReference isMember);

    /** SDDL 字符串 → 自相对安全描述符（LocalAlloc 分配，用后 LocalFree）——管道 DACL 等。 */
    boolean ConvertStringSecurityDescriptorToSecurityDescriptorW(String sddl,
            int sddlRevision, PointerByReference ppSecurityDescriptor,
            IntByReference pSecurityDescriptorSize);

    /**
     * SetEntriesInAclW——acl.rs 全族（add_allow_ace/add_deny_ace/remove_ace/
     * set_default_dacl/lock_sandbox_dir）的写回原语：新 deny ACE 自动置于 allow 之前
     * （deny 先赢），EXPLICIT_ACCESS_W 数组由 JNA 自动平铺为连续数组。
     *
     * <p>注意返回值是 Win32 错误码（{@code ERROR_SUCCESS}=0 表示成功），不是 BOOL——
     * 映射成 boolean 会把成功判成失败，必须以 int 接收并判 0。
     */
    int SetEntriesInAclW(int cCountOfExplicitEntries,
            EXPLICIT_ACCESS_W[] pListOfExplicitEntries, Pointer oldAcl,
            PointerByReference newAcl);

    /** 填充 EXPLICIT_ACCESS_W（trustee 名 + 掩码 + 模式 + 继承）——acl.rs 构造 ACE 前置。 */
    void BuildExplicitAccessWithNameW(EXPLICIT_ACCESS_W pExplicitAccess, String pTrusteeName,
            int accessPermissions, int accessMode, int inheritance);

    /**
     * BuildSecurityDescriptorW——wfp.rs 用户条件 SD blob：owner=账户 SID +
     * 一条 {@code BuildExplicitAccessWithNameW(FWP_ACTRL_MATCH_FILTER)} 条目 →
     * 自相对 SD，供 FWP_CONDITION_ALE_USER_ID 匹配（FWP_SECURITY_DESCRIPTOR_TYPE）。
     * 返回 ERROR_SUCCESS=0；产物 LocalFree 释放。
     */
    int BuildSecurityDescriptorW(Pointer owner, Pointer group,
            int cCountOfAccessEntries, EXPLICIT_ACCESS_W[] listOfAccessEntries,
            int cCountOfAuditEntries, EXPLICIT_ACCESS_W[] listOfAuditEntries,
            Pointer oldSD, int oldSDSize,
            IntByReference newSDSize, PointerByReference newSD);

    /** 刷新注册表 hive（hide_users.rs 写 UserList 后落盘）。 */
    int RegFlushKey(WinReg.HKEY hKey);

    // ---- 常量（jna-platform 未收录的部分） ----

    /** CreateRestrictedToken：剥离全部特权（codex 三连 flags 之一）。 */
    int DISABLE_MAX_PRIVILEGE = 0x00000001;
    /** CreateRestrictedToken：LUA 令牌（0x04）。 */
    int LUA_TOKEN = 0x00000004;
    /** CreateRestrictedToken：写受限（0x08，写通道按 restricting SID 二次评估）。 */
    int WRITE_RESTRICTED = 0x00000008;

    /** SetTokenInformation 信息类：默认 DACL（token.rs::set_default_dacl）。 */
    int TokenDefaultDacl = 6;

    /** SID_AND_ATTRIBUTES.Attributes：logon SID 组标志（token_groups.rs 扫描用）。 */
    int SE_GROUP_LOGON_ID = 0xC0000000;

    /** SDDL 版本（管道/描述符转换用）。 */
    int SDDL_REVISION_1 = 1;

    /** EXPLICIT_ACCESS_W.grfAccessMode：授予（不替换既有条目）。 */
    int GRANT_ACCESS = 0x00000001;
    /** EXPLICIT_ACCESS_W.grfAccessMode：替换该 SID 全部条目。 */
    int SET_ACCESS = 0x00000002;
    /** EXPLICIT_ACCESS_W.grfAccessMode：拒绝（deny ACE 压制读/写）。 */
    int DENY_ACCESS = 0x00000003;
    /** EXPLICIT_ACCESS_W.grfAccessMode：移除该 SID 全部条目。 */
    int REVOKE_ACCESS = 0x00000004;

    /** EXPLICIT_ACCESS_W.grfInheritance：子对象继承。 */
    int OBJECT_INHERIT_ACE = 0x00000001;
    /** EXPLICIT_ACCESS_W.grfInheritance：容器继承（codex 固定 CI|OI）。 */
    int CONTAINER_INHERIT_ACE = 0x00000002;
    /** EXPLICIT_ACCESS_W.grfInheritance：仅继承不生效于自身。 */
    int INHERIT_ONLY_ACE = 0x00000008;

    /** SetNamedSecurityInfo：DACL 信息。 */
    int DACL_SECURITY_INFORMATION = 0x00000004;
    /** SetNamedSecurityInfo：切断继承（Protected DACL，SandboxDirLocker 用）。 */
    int PROTECTED_DACL_SECURITY_INFORMATION = 0x00000800;
}
