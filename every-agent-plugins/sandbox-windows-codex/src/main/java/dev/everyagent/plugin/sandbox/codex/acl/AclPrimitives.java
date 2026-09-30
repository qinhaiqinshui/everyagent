package dev.everyagent.plugin.sandbox.codex.acl;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Advapi32;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.PointerByReference;

import dev.everyagent.plugin.sandbox.codex.win.Advapi32Ex;
import dev.everyagent.plugin.sandbox.codex.win.struct.AclStructs;
import dev.everyagent.plugin.sandbox.codex.win.struct.AclStructs.EXPLICIT_ACCESS_W;
import dev.everyagent.plugin.sandbox.codex.win.struct.AclStructs.TRUSTEE_W;

/**
 * Win32 ACL 原语（读取/查询/授权部分）——对齐 codex {@code acl.rs} 的
 * {@code fetch_dacl_handle} / {@code path_mask_allows} /
 * {@code path_write_aces_need_refresh} / {@code ensure_allow_*} 族。
 * 掩码常量见 {@link AclMasks}；deny/revoke/根保护见 {@link DenyAcePrimitives}。
 *
 * <p>PSID 以裸 {@link Pointer} 传入（调用方持有 {@link com.sun.jna.Memory} 防 GC；
 * SID 字符串→PSID 见 {@link WindowsAclOperations#psid(String)}）。
 *
 * <p><b>deny-before-allow</b>：{@code SetEntriesInAclW} 会把新建 deny ACE 置于
 * allow ACE 之前，天然维持 Windows「deny 先赢」的 DACL 求值顺序——调用方无需自行排序，
 * 旧 allow 也不会遮蔽新 deny（acl.rs add_deny_read_ace 的文档注释）。
 */
public final class AclPrimitives {

    private AclPrimitives() {
    }

    /** GetSecurityInfo 产物（AutoCloseable：descriptor 由 LocalFree 释放）。 */
    public static final class FetchedDacl implements AutoCloseable {
        /** DACL 只读视图（null DACL 表示全员放行）。 */
        public final AclDaclView view;
        private final Pointer descriptor;

        FetchedDacl(Pointer dacl, Pointer descriptor) {
            this.view = AclDaclView.of(dacl);
            this.descriptor = descriptor;
        }

        @Override
        public void close() {
            if (descriptor != null) {
                Kernel32.INSTANCE.LocalFree(descriptor);
            }
        }
    }

    /**
     * 取 DACL——对齐 {@code fetch_dacl_handle}：READ_CONTROL + FILE_FLAG_BACKUP_SEMANTICS
     * 打开（天然支持扩展长度路径、不提前解析重解析点）→ GetSecurityInfo(SE_FILE_OBJECT)。
     * 调用方以 try-with-resources 释放。
     */
    public static FetchedDacl fetchDacl(Path path) throws IOException {
        WinNT.HANDLE handle = openTarget(path, AclMasks.READ_CONTROL);
        try {
            AclDaclView.FetchedAcl fetched = fetchOn(handle, path);
            return new FetchedDacl(fetched.dacl(), fetched.descriptor());
        } finally {
            Kernel32.INSTANCE.CloseHandle(handle);
        }
    }

    /** 路径级掩码判定（单次 DACL fetch）——对齐 {@code path_mask_allows}。 */
    public static boolean pathMaskAllows(Path path, List<Pointer> psids, int desiredMask,
            boolean requireAllBits) throws IOException {
        try (FetchedDacl fetched = fetchDacl(path)) {
            return fetched.view.maskAllows(psids, desiredMask, requireAllBits,
                    AclDaclView.Scope.EFFECTIVE);
        }
    }

    /** 任一 SID 的写授权 ACE 是否待刷新——对齐 {@code path_write_aces_need_refresh}。 */
    public static boolean pathWriteAcesNeedRefresh(Path path, List<Pointer> psids)
            throws IOException {
        try (FetchedDacl fetched = fetchDacl(path)) {
            for (Pointer psid : psids) {
                if (allowMaskNeedsRefresh(fetched.view, psid, AclMasks.WRITE_ALLOW_MASK,
                        AclMasks.FILE_DELETE_CHILD)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** allow 掩码未全齐，或同 SID 的显式 ACE 含禁用位（SET_ACCESS 替换不了继承的陈旧授权）。 */
    static boolean allowMaskNeedsRefresh(AclDaclView view, Pointer psid, int allowMask,
            int disallowMask) {
        return !view.maskAllows(List.of(psid), allowMask, true, AclDaclView.Scope.EFFECTIVE)
                || view.maskAllows(List.of(psid), disallowMask, false, AclDaclView.Scope.EXPLICIT);
    }

    /**
     * 写根授权——对齐 {@code ensure_allow_write_aces}：WRITE_ALLOW_MASK、SET_ACCESS、
     * CI|OI、禁用位 FILE_DELETE_CHILD（父目录不授 delete-child）。
     *
     * @return 是否确有 ACE 写入（幂等：全部满足时全程只读）
     */
    public static boolean ensureAllowWriteAces(Path path, List<Pointer> psids) throws IOException {
        return ensureAllowMaskAces(path, psids, AclMasks.WRITE_ALLOW_MASK,
                AclMasks.FILE_DELETE_CHILD, Advapi32Ex.INSTANCE.SET_ACCESS,
                Advapi32Ex.INSTANCE.CONTAINER_INHERIT_ACE | Advapi32Ex.INSTANCE.OBJECT_INHERIT_ACE);
    }

    /**
     * 读根组授权——对齐 {@code ensure_allow_mask_aces}（setup apply_read_acls 用）：
     * RX、SET_ACCESS、CI|OI。
     */
    public static boolean ensureReadExecuteAces(Path path, List<Pointer> psids)
            throws IOException {
        return ensureAllowMaskAces(path, psids, AclMasks.READ_EXECUTE_MASK, 0,
                Advapi32Ex.INSTANCE.SET_ACCESS,
                Advapi32Ex.INSTANCE.CONTAINER_INHERIT_ACE | Advapi32Ex.INSTANCE.OBJECT_INHERIT_ACE);
    }

    /**
     * GRANT 授权（不替换既有条目）——对齐 {@code grant_read_execute_aces}：
     * GRANT_ACCESS 模式先做「护 deny」检查（任意受托人与 allow|GENERIC 相交的 deny
     * ACE 存在即跳过——没有完整令牌就无法证明新 allow 不会越权压过别人的继承 deny）。
     */
    public static boolean grantReadExecuteAces(Path path, List<Pointer> psids, int inheritance)
            throws IOException {
        return ensureAllowMaskAces(path, psids, AclMasks.READ_EXECUTE_MASK, 0,
                Advapi32Ex.INSTANCE.GRANT_ACCESS, inheritance);
    }

    /**
     * 通用授权实现——对齐 {@code ensure_allow_mask_aces_with_inheritance_impl}：
     * 幂等跳过 → SetEntriesInAclW 与旧 DACL 合并 → 仅当确有条目时以
     * READ_CONTROL|WRITE_DAC 重开句柄 SetSecurityInfo（无变更路径全程只读）。
     */
    private static boolean ensureAllowMaskAces(Path path, List<Pointer> psids, int allowMask,
            int disallowMask, int accessMode, int inheritance) throws IOException {
        List<EXPLICIT_ACCESS_W> entries = new ArrayList<>();
        try (FetchedDacl fetched = fetchDacl(path)) {
            for (Pointer psid : psids) {
                if (accessMode == Advapi32Ex.INSTANCE.GRANT_ACCESS && fetched.view.hasAnyDenyMask(
                        allowMask | AclMasks.GENERIC_GUARD_MASK)) {
                    continue; // 护 deny：保留一切现状
                }
                if (!allowMaskNeedsRefresh(fetched.view, psid, allowMask, disallowMask)) {
                    continue;
                }
                entries.add(explicitAccess(psid, allowMask, accessMode, inheritance));
            }
            if (entries.isEmpty()) {
                return false;
            }
            return mergeAndApply(path, entries.toArray(new EXPLICIT_ACCESS_W[0]));
        }
    }

    // ---- 句柄与底层工具 ----

    /** 按 desired access 打开路径（FILE_FLAG_BACKUP_SEMANTICS：目录/重解析点均可开）。 */
    static WinNT.HANDLE openTarget(Path path, int desiredAccess) throws IOException {
        WinNT.HANDLE handle = Kernel32.INSTANCE.CreateFile(path.toString(), desiredAccess,
                AclMasks.FILE_SHARE_ALL, null, AclMasks.OPEN_EXISTING,
                AclMasks.FILE_FLAG_BACKUP_SEMANTICS, null);
        if (WinBase.INVALID_HANDLE_VALUE.equals(handle)) {
            throw new IOException("open ACL target " + path + " failed: "
                    + AclPrimitives.winError(Kernel32.INSTANCE.GetLastError()));
        }
        return handle;
    }

    /** 句柄上取 DACL（授权/deny/revoke 路径共用；descriptor 由调用方 LocalFree）。 */
    static AclDaclView.FetchedAcl fetchOn(WinNT.HANDLE handle, Path path) throws IOException {
        PointerByReference ppDacl = new PointerByReference();
        PointerByReference ppSd = new PointerByReference();
        int code = Advapi32.INSTANCE.GetSecurityInfo(handle, AclMasks.SE_FILE_OBJECT,
                Advapi32Ex.INSTANCE.DACL_SECURITY_INFORMATION, null, null, ppDacl, null, ppSd);
        if (code != 0) {
            Kernel32.INSTANCE.LocalFree(ppSd.getValue());
            throw new IOException("GetSecurityInfo failed for " + path + ": "
                    + AclPrimitives.winError(code));
        }
        return new AclDaclView.FetchedAcl(ppDacl.getValue(), ppSd.getValue());
    }

    /** SetEntriesInAclW 合并旧 DACL 后，以 READ_CONTROL|WRITE_DAC 新句柄写回。 */
    static boolean mergeAndApply(Path path, EXPLICIT_ACCESS_W[] entries) throws IOException {
        WinNT.HANDLE handle = openTarget(path, AclMasks.READ_CONTROL | AclMasks.WRITE_DAC);
        try {
            AclDaclView.FetchedAcl fetched = fetchOn(handle, path);
            try {
                Pointer newDacl = mergeEntries(handle, path, entries, fetched.dacl());
                try {
                    setDacl(handle, path, newDacl, AclMasks.SE_FILE_OBJECT);
                } finally {
                    Kernel32.INSTANCE.LocalFree(newDacl);
                }
                return true;
            } finally {
                Kernel32.INSTANCE.LocalFree(fetched.descriptor());
            }
        } finally {
            Kernel32.INSTANCE.CloseHandle(handle);
        }
    }

    /** SetSecurityInfo 写回新 DACL（DACL_SECURITY_INFORMATION）。 */
    static void setDacl(WinNT.HANDLE handle, Path path, Pointer newDacl, int objectType)
            throws IOException {
        int set = Advapi32.INSTANCE.SetSecurityInfo(handle, objectType,
                Advapi32Ex.INSTANCE.DACL_SECURITY_INFORMATION, null, null, newDacl, null);
        if (set != 0) {
            throw new IOException("SetSecurityInfo failed for " + path + ": "
                    + AclPrimitives.winError(set));
        }
    }

    /** SetEntriesInAclW 合并（产物由调用方 LocalFree）。 */
    static Pointer mergeEntries(WinNT.HANDLE handle, Path path, EXPLICIT_ACCESS_W[] entries,
            Pointer oldDacl) throws IOException {
        PointerByReference ppNew = new PointerByReference();
        int merge = Advapi32Ex.INSTANCE.SetEntriesInAclW(entries.length, entries, oldDacl, ppNew);
        if (merge != 0) {
            throw new IOException("SetEntriesInAclW failed for " + path + ": "
                    + AclPrimitives.winError(merge));
        }
        return ppNew.getValue();
    }

    /** 构造 TRUSTEE_IS_SID 形态的 EXPLICIT_ACCESS_W（ptstrName 直接放 PSID 裸指针）。 */
    static EXPLICIT_ACCESS_W explicitAccess(Pointer psid, int permissions, int accessMode,
            int inheritance) {
        EXPLICIT_ACCESS_W entry = new EXPLICIT_ACCESS_W();
        entry.grfAccessPermissions = permissions;
        entry.grfAccessMode = accessMode;
        entry.grfInheritance = inheritance;
        TRUSTEE_W trustee = entry.Trustee;
        trustee.pMultipleTrustee = null;
        trustee.MultipleTrusteeOperation = 0;
        trustee.TrusteeForm = AclStructs.TRUSTEE_IS_SID;
        trustee.TrusteeType = AclStructs.TRUSTEE_IS_UNKNOWN;
        trustee.ptstrName = psid;
        return entry;
    }

    static String winError(int code) {
        return code + " (0x" + Integer.toUnsignedString(code, 16).toUpperCase() + ")";
    }
}
