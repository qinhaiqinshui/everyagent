package dev.everyagent.plugin.sandbox.codex.runner;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Advapi32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

import java.util.ArrayList;
import java.util.List;

import dev.everyagent.plugin.sandbox.codex.win.Advapi32Ex;
import dev.everyagent.plugin.sandbox.codex.win.Kernel32Ex;
import dev.everyagent.plugin.sandbox.codex.win.struct.AclStructs;
import dev.everyagent.plugin.sandbox.codex.win.struct.AclStructs.EXPLICIT_ACCESS_W;
import dev.everyagent.plugin.sandbox.codex.win.struct.AclStructs.SID_AND_ATTRIBUTES_PTR;
import dev.everyagent.plugin.sandbox.codex.win.struct.AclStructs.TOKEN_DEFAULT_DACL;
import dev.everyagent.plugin.sandbox.codex.win.struct.AclStructs.TRUSTEE_W;

/**
 * runner 进程内受限令牌工厂（设计文档 §2.6 TokenFactory，对齐
 * codex token.rs::create_token_with_caps_from / create_workspace_write_token_with_caps_and_user_from）。
 *
 * <p>流程：OpenProcessToken(自身) → CreateRestrictedToken(
 * {@code DISABLE_MAX_PRIVILEGE|LUA_TOKEN|WRITE_RESTRICTED}=0x01|0x04|0x08)，
 * restricting SIDs 顺序 <b>caps → 令牌 user SID → logon SID → Everyone</b>（全部
 * Attributes=0，user SID 是 elevated 模式下即沙箱账户）→ SetTokenInformation(
 * TokenDefaultDacl：logon SID GENERIC_ALL + OWNER RIGHTS S-1-3-4 仅 READ_CONTROL) →
 * AdjustTokenPrivileges 恢复 SeChangeNotifyPrivilege。
 *
 * <p>失败安全：CreateRestrictedToken 成功后任一步失败立即 CloseHandle——半成品令牌
 * 绝不外泄（codex 同款约束）。仅 Windows 运行时调用。
 */
public final class SandboxTokenFactory {

    /** Everyone S-1-1-0（等价 codex world_sid() 的 CreateWellKnownSid(WinWorldSid)）。 */
    private static final String WORLD_SID = "S-1-1-0";
    /** OWNER RIGHTS（codex LocalSid::from_string("S-1-3-4")）。 */
    private static final String OWNER_RIGHTS_SID = "S-1-3-4";
    /** GENERIC_ALL=0x10000000（WinNT.GENERIC_ALL）。 */
    private static final int GENERIC_ALL = 0x10000000;
    /** GetTokenInformation 信息类（jna-platform WinNT 未收录 TokenLinkedToken）。 */
    private static final int TOKEN_USER_CLASS = 1;
    private static final int TOKEN_GROUPS_CLASS = 2;
    private static final int TOKEN_LINKED_TOKEN_CLASS = 19;
    /** SID 二进制上限（revision 1 + 15 子授权 = 68 字节，codex token_groups 同界）。 */
    private static final int MAX_SID_BYTES = 68;

    private SandboxTokenFactory() {
    }

    /**
     * 以当前进程令牌为基座派生受限令牌；调用方负责 CloseHandle 返回值。
     *
     * @param capSidStrings capability SID 字符串列表（至少 1 个，空即拒绝）
     */
    public static WinNT.HANDLE createRestrictedTokenWithCaps(List<String> capSidStrings) {
        if (capSidStrings == null || capSidStrings.isEmpty()) {
            throw new IllegalStateException("no capability SIDs provided");
        }
        WinNT.HANDLEByReference out = new WinNT.HANDLEByReference();
        WinNT.HANDLE base = openCurrentTokenForRestriction();
        // 所有 SID 统一复制进 JNA Memory（GC 回收，不走 LocalFree——LocalFree 只用于
        // ConvertStringSidToSid/SetEntriesInAclW 的 LocalAlloc 产物，混用会破坏堆）
        List<Pointer> owned = new ArrayList<>();
        try {
            for (String sid : capSidStrings) {
                owned.add(convertSid(sid));
            }
            owned.add(userSidOf(base));    // 额外 restricting 首位：令牌 user SID
            Pointer logonSid = logonSidOf(base);
            owned.add(logonSid);
            owned.add(convertSid(WORLD_SID));

            int flags = Advapi32Ex.DISABLE_MAX_PRIVILEGE | Advapi32Ex.LUA_TOKEN
                    | Advapi32Ex.WRITE_RESTRICTED;
            SID_AND_ATTRIBUTES_PTR[] entries = restrictingEntries(owned);
            if (!Advapi32Ex.INSTANCE.CreateRestrictedToken(base, flags,
                    0, null, 0, null, entries.length, entries[0].getPointer(), out)) {
                throw Win32Exception.of("CreateRestrictedToken");
            }
            WinNT.HANDLE token = out.getValue();
            // set_default_dacl 与恢复特权任一失败即销毁半成品令牌
            try {
                setDefaultDacl(token, logonSid);
                enableSinglePrivilege(token, "SeChangeNotifyPrivilege");
            } catch (RuntimeException e) {
                Kernel32Ex.INSTANCE.CloseHandle(token);
                throw e;
            }
            return token;
        } finally {
            Kernel32Ex.INSTANCE.CloseHandle(base);
        }
    }

    /** OpenProcessToken(自身)：token.rs::get_current_token_for_restriction 的访问集。 */
    private static WinNT.HANDLE openCurrentTokenForRestriction() {
        int desired = WinNT.TOKEN_DUPLICATE | WinNT.TOKEN_QUERY | WinNT.TOKEN_ASSIGN_PRIMARY
                | WinNT.TOKEN_ADJUST_DEFAULT | WinNT.TOKEN_ADJUST_SESSIONID
                | WinNT.TOKEN_ADJUST_PRIVILEGES;
        WinNT.HANDLEByReference ref = new WinNT.HANDLEByReference();
        if (!Advapi32Ex.INSTANCE.OpenProcessToken(
                Kernel32Ex.INSTANCE.GetCurrentProcess(), desired, ref)) {
            throw Win32Exception.of("OpenProcessToken");
        }
        return ref.getValue();
    }

    /**
     * SID 字符串 → 自有 Memory 副本（LocalSid::from_string 语义，但所有权统一为
     * JNA Memory：ConvertStringSidToSidW 的 LocalAlloc 产物立即复制并 LocalFree）。
     */
    private static Pointer convertSid(String sidString) {
        WinNT.PSIDByReference ref = new WinNT.PSIDByReference();
        if (!Advapi32.INSTANCE.ConvertStringSidToSid(sidString, ref)) {
            throw Win32Exception.of("ConvertStringSidToSidW(" + sidString + ")");
        }
        Pointer psid = ref.getValue().getPointer();
        try {
            if (psid.getByte(0) != 1) {
                throw new IllegalStateException("invalid SID revision: " + sidString);
            }
            int sidLen = 8 + (psid.getByte(1) & 0xFF) * 4;
            if (sidLen > MAX_SID_BYTES) {
                throw new IllegalStateException("invalid SID size: " + sidString);
            }
            byte[] copy = psid.getByteArray(0, sidLen);
            Memory owned = new Memory(sidLen);
            owned.write(0, copy, 0, sidLen);
            return owned;
        } finally {
            Kernel32Ex.INSTANCE.LocalFree(psid);
        }
    }

    /** restricting 数组（连续 Memory 平铺；Sid 裸指针防 JNA 改写目标内存）。 */
    private static SID_AND_ATTRIBUTES_PTR[] restrictingEntries(List<Pointer> sids) {
        SID_AND_ATTRIBUTES_PTR[] entries = (SID_AND_ATTRIBUTES_PTR[])
                new SID_AND_ATTRIBUTES_PTR().toArray(sids.size());
        for (int i = 0; i < sids.size(); i++) {
            entries[i].Sid = sids.get(i);
            entries[i].Attributes = 0;
            entries[i].write();
        }
        return entries;
    }

    /** 令牌 user SID（TOKEN_USER 首字段即 SID_AND_ATTRIBUTES）。 */
    private static Pointer userSidOf(WinNT.HANDLE token) {
        Memory buf = queryTokenInformation(token, TOKEN_USER_CLASS);
        return copySidBounded(buf, buf.getPointer(0));
    }

    /** logon SID：扫 TOKEN_GROUPS 的 SE_GROUP_LOGON_ID 组，缺则查 TokenLinkedToken。 */
    private static Pointer logonSidOf(WinNT.HANDLE token) {
        Pointer sid = scanGroupsForLogonSid(token);
        if (sid != null) {
            return sid;
        }
        IntByReference need = new IntByReference();
        Advapi32Ex.INSTANCE.GetTokenInformation(token, TOKEN_LINKED_TOKEN_CLASS, null, 0, need);
        if (need.getValue() >= Native.POINTER_SIZE) {
            Memory buf = queryTokenInformation(token, TOKEN_LINKED_TOKEN_CLASS);
            WinNT.HANDLE linked = new WinNT.HANDLE(buf.getPointer(0));
            if (Pointer.nativeValue(linked.getPointer()) != 0) {
                try {
                    sid = scanGroupsForLogonSid(linked);
                    if (sid != null) {
                        return sid;
                    }
                } finally {
                    Kernel32Ex.INSTANCE.CloseHandle(linked);
                }
            }
        }
        throw new IllegalStateException("Logon SID not present on token");
    }

    private static Pointer scanGroupsForLogonSid(WinNT.HANDLE token) {
        Memory buf = queryTokenInformation(token, TOKEN_GROUPS_CLASS);
        int groupsOffset = new TokenGroupsProbe().offsetOf("Group0");
        // SID_AND_ATTRIBUTES 布局确定：Sid(指针) 在 0，Attributes 紧随其后；
        // stride 用 Structure#size() 实测（x64=16 含尾填充，x86=8）
        long stride = new SID_AND_ATTRIBUTES_PTR().size();
        int attributesOffset = Native.POINTER_SIZE;
        int count = buf.getInt(0);
        for (int i = 0; i < count; i++) {
            long entry = groupsOffset + i * stride;
            if (entry + stride > buf.size()) {
                throw new IllegalStateException("truncated token groups");
            }
            int attributes = buf.getInt(entry + attributesOffset);
            if ((attributes & Advapi32Ex.SE_GROUP_LOGON_ID) == Advapi32Ex.SE_GROUP_LOGON_ID) {
                return copySidBounded(buf, buf.getPointer(entry));
            }
        }
        return null;
    }

    /** JNA Structure#fieldOffset 是 protected，子类提出来做布局探测。 */
    private static final class TokenGroupsProbe extends WinNT.TOKEN_GROUPS {
        int offsetOf(String field) {
            return super.fieldOffset(field);
        }
    }

    /**
     * GetTokenInformation 通用查询：先探长度再取载荷，原始字节落 {@link Memory}
     * （壳 Structure 只当长度载体，内核整块覆盖；缓冲至少为壳声明尺寸，防
     * Structure 参数自动同步时越界写）。
     */
    private static Memory queryTokenInformation(WinNT.HANDLE token, int infoClass) {
        IntByReference need = new IntByReference();
        Advapi32Ex.INSTANCE.GetTokenInformation(token, infoClass, null, 0, need);
        int len = need.getValue();
        if (len <= 0) {
            throw Win32Exception.of("GetTokenInformation(size, class=" + infoClass + ")");
        }
        WinNT.TOKEN_GROUPS probe = new WinNT.TOKEN_GROUPS();
        int alloc = Math.max(len, probe.size());
        Memory buf = new Memory(alloc);
        WinNT.TOKEN_GROUPS shell = new WinNT.TOKEN_GROUPS(buf);
        if (!Advapi32Ex.INSTANCE.GetTokenInformation(token, infoClass, shell, alloc, need)) {
            throw Win32Exception.of("GetTokenInformation(class=" + infoClass + ")");
        }
        return buf;
    }

    /** 越界校验 + 复制 SID 到自有内存（codex decode_token_groups 的边界规则）。 */
    private static Pointer copySidBounded(Memory buf, Pointer sid) {
        long rel = Pointer.nativeValue(sid) - Pointer.nativeValue(buf);
        if (rel < 0 || rel + 8 > buf.size()) {
            throw new IllegalStateException("invalid token group SID pointer");
        }
        if (buf.getByte(rel) != 1) {
            throw new IllegalStateException("invalid token group SID revision");
        }
        int sidLen = 8 + (buf.getByte(rel + 1) & 0xFF) * 4;
        if (sidLen > MAX_SID_BYTES || rel + sidLen > buf.size()) {
            throw new IllegalStateException("invalid token group SID size");
        }
        byte[] copy = buf.getByteArray(rel, sidLen);
        Memory owned = new Memory(sidLen);
        owned.write(0, copy, 0, sidLen);
        return owned;
    }

    /** TokenDefaultDacl：logon SID GENERIC_ALL + OWNER RIGHTS 仅 READ_CONTROL。 */
    private static void setDefaultDacl(WinNT.HANDLE token, Pointer logonSid) {
        // 两枚 SID 均为自有 Memory 副本（存活至本方法返回即可）
        EXPLICIT_ACCESS_W[] entries = {
                accessEntry(logonSid, GENERIC_ALL),
                accessEntry(convertSid(OWNER_RIGHTS_SID), WinNT.READ_CONTROL)
        };
        PointerByReference newAcl = new PointerByReference();
        int rc = Advapi32Ex.INSTANCE.SetEntriesInAclW(entries.length, entries, null, newAcl);
        if (rc != 0) {
            throw new Win32Exception("SetEntriesInAclW", rc);
        }
        TOKEN_DEFAULT_DACL info = new TOKEN_DEFAULT_DACL();
        info.TokenDefaultDacl = newAcl.getValue();
        if (!Advapi32Ex.INSTANCE.SetTokenInformation(token,
                Advapi32Ex.TokenDefaultDacl, info, info.size())) {
            int err = Kernel32Ex.INSTANCE.GetLastError();
            Kernel32Ex.INSTANCE.LocalFree(newAcl.getValue());
            throw new Win32Exception("SetTokenInformation(TokenDefaultDacl)", err);
        }
        Kernel32Ex.INSTANCE.LocalFree(newAcl.getValue());
    }

    private static EXPLICIT_ACCESS_W accessEntry(Pointer sid, int mask) {
        EXPLICIT_ACCESS_W e = new EXPLICIT_ACCESS_W();
        e.grfAccessPermissions = mask;
        e.grfAccessMode = Advapi32Ex.GRANT_ACCESS;
        e.grfInheritance = 0;
        TRUSTEE_W t = e.Trustee;
        t.pMultipleTrustee = null;
        t.MultipleTrusteeOperation = 0;
        t.TrusteeForm = AclStructs.TRUSTEE_IS_SID;
        t.TrusteeType = AclStructs.TRUSTEE_IS_UNKNOWN;
        t.ptstrName = sid;
        return e;
    }

    /** LookupPrivilegeValueW + AdjustTokenPrivileges（SE_PRIVILEGE_ENABLED）；last error 非 0 同判失败。 */
    private static void enableSinglePrivilege(WinNT.HANDLE token, String name) {
        WinNT.LUID luid = new WinNT.LUID();
        if (!Advapi32.INSTANCE.LookupPrivilegeValue(null, name, luid)) {
            throw Win32Exception.of("LookupPrivilegeValueW(" + name + ")");
        }
        WinNT.TOKEN_PRIVILEGES tp = new WinNT.TOKEN_PRIVILEGES();
        tp.PrivilegeCount = new WinDef.DWORD(1);
        tp.Privileges = new WinNT.LUID_AND_ATTRIBUTES[] {
                new WinNT.LUID_AND_ATTRIBUTES(luid, new WinDef.DWORD(WinNT.SE_PRIVILEGE_ENABLED)) };
        if (!Advapi32.INSTANCE.AdjustTokenPrivileges(token, false, tp, 0, null, null)) {
            throw Win32Exception.of("AdjustTokenPrivileges(" + name + ")");
        }
        int err = Kernel32Ex.INSTANCE.GetLastError();
        if (err != 0) {
            throw new Win32Exception("AdjustTokenPrivileges(" + name + ")", err);
        }
    }
}
