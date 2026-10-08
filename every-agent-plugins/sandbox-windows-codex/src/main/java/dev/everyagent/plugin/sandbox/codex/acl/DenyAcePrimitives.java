package dev.everyagent.plugin.sandbox.codex.acl;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Advapi32;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.PointerByReference;

import dev.everyagent.plugin.sandbox.codex.win.Advapi32Ex;
import dev.everyagent.plugin.sandbox.codex.win.struct.AclStructs.EXPLICIT_ACCESS_W;

/**
 * Win32 ACL 原语（deny/revoke/根保护/空设备部分）——对齐 codex {@code acl.rs} 的
 * {@code add_deny_ace} / {@code add_deny_write_ace} / {@code add_deny_read_ace} /
 * {@code revoke_ace} / {@code ensure_handle_is_not_filesystem_root} /
 * {@code allow_null_device}。读取/查询/授权部分见 {@link AclPrimitives}。
 *
 * <p>deny ACE 可继承（CI|OI）：目录上的 deny 自动覆盖其后新建的子孙；
 * {@code SetEntriesInAclW} 把新建 deny ACE 置于 allow 之前（deny 先赢，见类注释）。
 */
public final class DenyAcePrimitives {

    private DenyAcePrimitives() {
    }

    /** deny ACE 种类——对齐 acl.rs DenyAceKind。 */
    private enum DenyAceKind {
        READ(AclMasks.DENY_READ_MASK),
        WRITE(AclMasks.DENY_WRITE_MASK);

        final int mask;

        DenyAceKind(int mask) {
            this.mask = mask;
        }

        boolean present(AclDaclView view, Pointer psid) {
            return view.hasDenyMaskForSid(psid, mask);
        }
    }

    /** 加 deny-write ACE——对齐 {@code add_deny_write_ace}（DENY_ACCESS、CI|OI、幂等）。 */
    public static boolean addDenyWriteAce(Path path, Pointer psid) throws IOException {
        return addDenyAce(path, psid, DenyAceKind.WRITE);
    }

    /**
     * 加 deny-read ACE——对齐 {@code add_deny_read_ace}：可继承（物化目录的 deny
     * 覆盖其后新建的子孙），并对句柄做文件系统根校验（别名/重解析路径不得把机器整体锁死；
     * 与 DenyReadPlanner 的 plan 阶段构成双重拦截）。
     */
    public static boolean addDenyReadAce(Path path, Pointer psid) throws IOException {
        return addDenyAce(path, psid, DenyAceKind.READ);
    }

    private static boolean addDenyAce(Path path, Pointer psid, DenyAceKind kind)
            throws IOException {
        final WinNT.HANDLE handle;
        try {
            handle = AclPrimitives.openTarget(path, AclMasks.READ_CONTROL | AclMasks.WRITE_DAC);
        } catch (IOException writeError) {
            // 打不开写句柄：退只读句柄做存在性检查后回传原错误（对齐 add_deny_ace 降级探测）
            WinNT.HANDLE readHandle = AclPrimitives.openTarget(path, AclMasks.READ_CONTROL);
            try {
                if (kind == DenyAceKind.READ) {
                    ensureNotFilesystemRoot(readHandle, path);
                }
                if (denyAlreadyPresent(readHandle, path, psid, kind)) {
                    return false;
                }
                throw writeError;
            } finally {
                Kernel32.INSTANCE.CloseHandle(readHandle);
            }
        }
        try {
            if (kind == DenyAceKind.READ) {
                ensureNotFilesystemRoot(handle, path);
            }
            if (denyAlreadyPresent(handle, path, psid, kind)) {
                return false; // 幂等跳过，避免无谓的继承重传播
            }
            EXPLICIT_ACCESS_W entry = AclPrimitives.explicitAccess(psid, kind.mask,
                    Advapi32Ex.INSTANCE.DENY_ACCESS,
                    Advapi32Ex.INSTANCE.CONTAINER_INHERIT_ACE | Advapi32Ex.INSTANCE.OBJECT_INHERIT_ACE);
            AclDaclView.FetchedAcl fetched = AclPrimitives.fetchOn(handle, path);
            try {
                Pointer newDacl = mergeSingle(handle, path, entry, fetched.dacl());
                try {
                    AclPrimitives.setDacl(handle, path, newDacl, AclMasks.SE_FILE_OBJECT);
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

    private static boolean denyAlreadyPresent(WinNT.HANDLE handle, Path path, Pointer psid,
            DenyAceKind kind) throws IOException {
        AclDaclView.FetchedAcl fetched = AclPrimitives.fetchOn(handle, path);
        try {
            return kind.present(AclDaclView.of(fetched.dacl()), psid);
        } finally {
            Kernel32.INSTANCE.LocalFree(fetched.descriptor());
        }
    }

    /**
     * 移除该 SID 的全部显式 ACE（含 {@code SetEntriesInAclW} 在容器上写入时拆出的
     * 「对自身生效」+「(OI)(CI)(IO) 仅继承」双条目）。契约对齐 {@code revoke_ace}，但实现
     * <b>偏离 acl.rs</b>：不再走 {@code SetEntriesInAclW(REVOKE)}——容器上带 CI|OI 的
     * deny 写入会被拆成两条 ACE，而 REVOKE 的 (trustee×继承形态) 匹配对两条都配不上，
     * 一条也删不掉（2026-12 探针实证：11 条 DACL 加 deny 落盘成 13 条，REVOKE 合并
     * 13→13 零删除；acl.rs 同一 Win32 语义同病，见 docs/parts/02-token-acl.md §3.5）。
     * 改为手动重建：逐条 SID 精确匹配剔除、其余 ACE 原样字节拷贝（icacls /remove 同款
     * 做法，形态无关）。null DACL 直接成功（换成空 ACL 会全拒）；<b>无该 SID 条目则
     * 不落盘</b>（等价原 AceCount 短路，不触发继承重传播）。
     */
    public static void revokeAce(Path path, Pointer psid) throws IOException {
        PointerByReference ppDacl = new PointerByReference();
        PointerByReference ppSd = new PointerByReference();
        int code = Advapi32.INSTANCE.GetNamedSecurityInfo(path.toString(), AclMasks.SE_FILE_OBJECT,
                Advapi32Ex.INSTANCE.DACL_SECURITY_INFORMATION, null, null, ppDacl, null, ppSd);
        if (code != 0) {
            Kernel32.INSTANCE.LocalFree(ppSd.getValue());
            throw new IOException("GetNamedSecurityInfoW failed for " + path + ": "
                    + AclPrimitives.winError(code));
        }
        Pointer oldDacl = ppDacl.getValue();
        Pointer descriptor = ppSd.getValue();
        try {
            if (oldDacl == null) {
                return; // null DACL 无条目可撤
            }
            List<AclDaclView.Ace> keep = new ArrayList<>();
            boolean[] removed = { false };
            AclDaclView.of(oldDacl).forEach(ace -> {
                if (AclDaclView.sidEquals(ace.sid(), psid)) {
                    removed[0] = true; // deny 双变体/allow 全形态按 SID 一并剔除
                } else {
                    keep.add(ace);
                }
            });
            if (!removed[0]) {
                return; // 无该 SID 条目：不落盘
            }
            int total = 8; // ACL 头：Revision/Sbz1/AclSize/AceCount/Sbz2
            for (AclDaclView.Ace ace : keep) {
                total += ace.size();
            }
            Pointer newDacl = new Memory(total);
            newDacl.setByte(0, oldDacl.getByte(0)); // AclRevision 原样保留
            newDacl.setByte(1, (byte) 0); // Sbz1
            newDacl.setShort(2, (short) total); // AclSize
            newDacl.setShort(4, (short) keep.size()); // AceCount
            newDacl.setShort(6, (short) 0); // Sbz2
            int offset = 8;
            for (AclDaclView.Ace ace : keep) {
                newDacl.write(offset, oldDacl.getByteArray(ace.offset(), ace.size()), 0, ace.size());
                offset += ace.size();
            }
            int set = Advapi32.INSTANCE.SetNamedSecurityInfo(path.toString(),
                    AclMasks.SE_FILE_OBJECT, Advapi32Ex.INSTANCE.DACL_SECURITY_INFORMATION,
                    null, null, newDacl, null);
            if (set != 0) {
                throw new IOException("SetNamedSecurityInfoW failed for " + path + ": "
                        + AclPrimitives.winError(set));
            }
        } finally {
            Kernel32.INSTANCE.LocalFree(descriptor);
        }
    }

    private static Pointer mergeSingle(WinNT.HANDLE handle, Path path, EXPLICIT_ACCESS_W entry,
            Pointer oldDacl) throws IOException {
        PointerByReference ppNew = new PointerByReference();
        int merge = Advapi32Ex.INSTANCE.SetEntriesInAclW(1, new EXPLICIT_ACCESS_W[] { entry },
                oldDacl, ppNew);
        if (merge != 0) {
            throw new IOException(
                    "SetEntriesInAclW failed for " + path + ": " + AclPrimitives.winError(merge));
        }
        return ppNew.getValue();
    }

    /**
     * 给 \\.\NUL 设备授 R/W/E——对齐 {@code allow_null_device}（stdout/stderr 重定向兜底）：
     * SE_KERNEL_OBJECT、SET_ACCESS、不继承、全程尽力而为不抛错。
     */
    public static void allowNullDevice(Pointer psid) {
        WinNT.HANDLE handle = Kernel32.INSTANCE.CreateFile(AclMasks.NUL_DEVICE,
                AclMasks.READ_CONTROL | AclMasks.WRITE_DAC, 0x3, null, AclMasks.OPEN_EXISTING,
                AclMasks.FILE_ATTRIBUTE_NORMAL, null);
        if (WinBase.INVALID_HANDLE_VALUE.equals(handle)) {
            return;
        }
        try {
            PointerByReference ppDacl = new PointerByReference();
            PointerByReference ppSd = new PointerByReference();
            int code = Advapi32.INSTANCE.GetSecurityInfo(handle, AclMasks.SE_KERNEL_OBJECT,
                    Advapi32Ex.INSTANCE.DACL_SECURITY_INFORMATION, null, null, ppDacl, null, ppSd);
            if (code != 0) {
                return;
            }
            try {
                EXPLICIT_ACCESS_W entry = AclPrimitives.explicitAccess(psid,
                        AclMasks.FILE_GENERIC_READ | AclMasks.FILE_GENERIC_WRITE
                                | AclMasks.FILE_GENERIC_EXECUTE,
                        Advapi32Ex.INSTANCE.SET_ACCESS, 0);
                PointerByReference ppNew = new PointerByReference();
                if (Advapi32Ex.INSTANCE.SetEntriesInAclW(1, new EXPLICIT_ACCESS_W[] { entry },
                        ppDacl.getValue(), ppNew) == 0) {
                    Advapi32.INSTANCE.SetSecurityInfo(handle, AclMasks.SE_KERNEL_OBJECT,
                            Advapi32Ex.INSTANCE.DACL_SECURITY_INFORMATION, null, null,
                            ppNew.getValue(), null);
                    Kernel32.INSTANCE.LocalFree(ppNew.getValue());
                }
            } finally {
                Kernel32.INSTANCE.LocalFree(ppSd.getValue());
            }
        } finally {
            Kernel32.INSTANCE.CloseHandle(handle);
        }
    }

    /**
     * 文件系统根校验——对齐 {@code ensure_handle_is_not_filesystem_root}：
     * GetFinalPathNameByHandleW(VOLUME_NAME_NONE) 解析真实路径，
     * 结果恰为单个 '\' 即根 → 拒绝。
     */
    private static void ensureNotFilesystemRoot(WinNT.HANDLE handle, Path path)
            throws IOException {
        char[] buffer = new char[2];
        int length = AclNative.INSTANCE.GetFinalPathNameByHandleW(handle, buffer, buffer.length,
                AclMasks.VOLUME_NAME_NONE);
        if (length == 0) {
            throw new IOException("resolve deny-read ACL target " + path + " failed: "
                    + AclPrimitives.winError(Kernel32.INSTANCE.GetLastError()));
        }
        if (length == 1 && buffer[0] == '\\') {
            throw new IOException("refusing to apply a deny-read ACE to filesystem root " + path);
        }
    }
}
