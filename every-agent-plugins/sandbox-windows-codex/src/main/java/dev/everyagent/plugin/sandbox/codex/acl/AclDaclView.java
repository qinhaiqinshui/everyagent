package dev.everyagent.plugin.sandbox.codex.acl;

import java.util.List;
import java.util.function.Consumer;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Advapi32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;


/**
 * DACL 只读视图——对齐 codex {@code acl.rs} 的 {@code dacl_*_for_sid} /
 * {@code dacl_mask_allows_with_scope} 查询族：直接解析 PACL 原始内存
 * （ACL 头 8 字节：AclRevision/Sbz1/AclSize(2)/AceCount(2)/Sbz2；ACE 自偏移 8 起，
 * {@code ACE_HEADER{type,flags,size(2)}} + Mask(4) + 内联 SID），
 * 不经 JNA Structure 封送（避免 PSID/ACL 结构体写回覆盖目标内存的坑，
 * 同 mic 的 TOKEN_MANDATORY_LABEL 教训）。
 *
 * <p>视图不持有内存：底层 DACL 由 GetSecurityInfo 分配、调用方 LocalFree，
 * 视图只在 fetch→free 窗口内使用。
 */
final class AclDaclView {

    /** PACL 头部大小（AceCount 位于偏移 4）。 */
    private static final int ACL_HEADER_SIZE = 8;
    /** ACE_HEADER 大小（其后是 Mask(4) 与内联 SidStart）。 */
    private static final int ACE_HEADER_SIZE = 4;
    /** Mask 相对 ACE 起点的偏移。 */
    private static final int ACE_MASK_OFFSET = ACE_HEADER_SIZE;
    /** 内联 SID 相对 ACE 起点的偏移。 */
    private static final int ACE_SID_OFFSET = ACE_HEADER_SIZE + 4;
    /** 最小合法 ACE（头 + Mask + 修订字节 + 计数字节）。 */
    private static final int MIN_ACE_SIZE = ACE_SID_OFFSET + 2;

    /** ACCESS_ALLOWED_ACE_TYPE。 */
    static final byte ACE_TYPE_ALLOWED = 0;
    /** ACCESS_DENIED_ACE_TYPE。 */
    static final byte ACE_TYPE_DENIED = 1;

    /** AceFlags：仅继承（对本对象不生效）。 */
    static final int FLAG_INHERIT_ONLY = 0x08;
    /** AceFlags：从祖先继承而来（SET_ACCESS 替换不了它）。 */
    static final int FLAG_INHERITED = 0x10;
    /** AceFlags：OBJECT_INHERIT_ACE。 */
    static final int FLAG_OBJECT_INHERIT = 0x01;

    /** 查询范围——对齐 acl.rs 的 {@code AceScope}。 */
    enum Scope {
        /** 生效条目（跳过 inherit-only）。 */
        EFFECTIVE,
        /** 仅显式条目（额外跳过继承 ACE——继承的陈旧授权 SET_ACCESS 替换不掉）。 */
        EXPLICIT,
        /** 生效条目 + 仅对象继承（inherit-only 且带 OI）的条目（子文件视角）。 */
        EFFECTIVE_OR_CHILD_FILE
    }

    /** 单条 ACE 的解析结果（sid 为内联视图指针，随底层 DACL 存活）。 */
    record Ace(int type, int flags, int mask, Pointer sid) {
        boolean isAllowed() {
            return type == ACE_TYPE_ALLOWED;
        }

        boolean isDenied() {
            return type == ACE_TYPE_DENIED;
        }
    }

    private final Pointer acl;

    private AclDaclView(Pointer acl) {
        this.acl = acl;
    }

    /** 包装 DACL 指针；null DACL（全员放行）视作空视图。 */
    static AclDaclView of(Pointer acl) {
        return new AclDaclView(acl);
    }

    boolean isNull() {
        return acl == null;
    }

    /** ACE 数量（ACL 头偏移 4 的 USHORT）。 */
    int aceCount() {
        return acl == null ? 0 : acl.getShort(4) & 0xFFFF;
    }

    void forEach(Consumer<Ace> consumer) {
        if (acl == null) {
            return;
        }
        long offset = ACL_HEADER_SIZE;
        int count = aceCount();
        for (int i = 0; i < count; i++) {
            int size = acl.getShort(offset + 2) & 0xFFFF;
            if (size < MIN_ACE_SIZE) {
                break; // 畸形 ACL 防御：不再继续解引用
            }
            consumer.accept(new Ace(acl.getByte(offset) & 0xFF, acl.getByte(offset + 1) & 0xFF,
                    acl.getInt(offset + ACE_MASK_OFFSET), acl.share(offset + ACE_SID_OFFSET)));
            offset += size;
        }
    }

    /**
     * 掩码判定——对齐 {@code dacl_mask_allows_with_scope}：仅 allow 型 ACE；
     * 按 scope 跳过 inherit-only / 继承条目；SID 命中任一受托人后
     * MapGenericMask 折算 GENERIC 位再比较。
     *
     * @param requireAllBits true 需全部位齐备；false 任一位即真
     */
    boolean maskAllows(List<Pointer> psids, int desiredMask, boolean requireAllBits, Scope scope) {
        if (psids.isEmpty()) {
            return false;
        }
        final boolean[] found = { false };
        forEach(ace -> {
            if (found[0] || !ace.isAllowed()) {
                return;
            }
            if ((ace.flags() & FLAG_INHERIT_ONLY) != 0
                    && (scope != Scope.EFFECTIVE_OR_CHILD_FILE
                            || (ace.flags() & FLAG_OBJECT_INHERIT) == 0)) {
                return;
            }
            if (scope == Scope.EXPLICIT && (ace.flags() & FLAG_INHERITED) != 0) {
                return;
            }
            if (!matchesAnySid(ace.sid(), psids)) {
                return;
            }
            int mask = mapGeneric(ace.mask());
            if ((requireAllBits && (mask & desiredMask) == desiredMask)
                    || (!requireAllBits && (mask & desiredMask) != 0)) {
                found[0] = true;
            }
        });
        return found[0];
    }

    /** 对齐 {@code dacl_has_write_allow_for_sid}：allow 型、忽略 inherit-only、与 FILE_GENERIC_WRITE 相交。 */
    boolean hasWriteAllowForSid(Pointer psid) {
        final boolean[] found = { false };
        forEach(ace -> {
            if (found[0] || !ace.isAllowed() || (ace.flags() & FLAG_INHERIT_ONLY) != 0) {
                return;
            }
            if (sidEquals(ace.sid(), psid) && (ace.mask() & AclMasks.FILE_GENERIC_WRITE) != 0) {
                found[0] = true;
            }
        });
        return found[0];
    }

    /**
     * 对齐 {@code dacl_has_write_deny_for_sid} / {@code dacl_has_read_deny_for_sid}
     * （统一为 {@code dacl_has_deny_mask} 的 EffectiveForSid 分支）：deny 型、
     * 忽略 inherit-only、SID 精确匹配、与掩码相交。
     */
    boolean hasDenyMaskForSid(Pointer psid, int denyMask) {
        final boolean[] found = { false };
        forEach(ace -> {
            if (found[0] || !ace.isDenied() || (ace.flags() & FLAG_INHERIT_ONLY) != 0) {
                return;
            }
            if (sidEquals(ace.sid(), psid) && (ace.mask() & denyMask) != 0) {
                found[0] = true;
            }
        });
        return found[0];
    }

    /**
     * 对齐 {@code dacl_has_deny_mask} 的 {@code DenyAceScope::Any} 分支：
     * 任意受托人的 deny ACE 与掩码相交即真（不跳过 inherit-only——
     * GRANT 模式「护 deny」检查用：没有完整令牌就无法证明新 allow 不会越权）。
     */
    boolean hasAnyDenyMask(int denyMask) {
        final boolean[] found = { false };
        forEach(ace -> {
            if (found[0] || !ace.isDenied()) {
                return;
            }
            if ((ace.mask() & denyMask) != 0) {
                found[0] = true;
            }
        });
        return found[0];
    }

    private static boolean matchesAnySid(Pointer aceSid, List<Pointer> psids) {
        for (Pointer psid : psids) {
            if (sidEquals(aceSid, psid)) {
                return true;
            }
        }
        return false;
    }

    /** GENERIC 位折算——对齐 acl.rs 的 GENERIC_MAPPING + MapGenericMask。 */
    private static int mapGeneric(int mask) {
        if ((mask & 0xC0000000) == 0 && (mask & 0x20000000) == 0 && (mask & 0x10000000) == 0) {
            return mask; // 快路径：无 GENERIC 位
        }
        WinNT.GENERIC_MAPPING mapping = new WinNT.GENERIC_MAPPING();
        mapping.genericRead = new WinDef.DWORD(AclMasks.FILE_GENERIC_READ);
        mapping.genericWrite = new WinDef.DWORD(AclMasks.FILE_GENERIC_WRITE);
        mapping.genericExecute = new WinDef.DWORD(AclMasks.FILE_GENERIC_EXECUTE);
        mapping.genericAll = new WinDef.DWORD(AclMasks.FILE_ALL_ACCESS);
        WinDef.DWORDByReference ref = new WinDef.DWORDByReference(new WinDef.DWORD(mask));
        Advapi32.INSTANCE.MapGenericMask(ref, mapping);
        return ref.getValue().intValue();
    }

    /** SID 字节级比较（修订/计数/授权机构/子授权），不经 EqualSid 封送。 */
    static boolean sidEquals(Pointer a, Pointer b) {
        if (a == null || b == null) {
            return false;
        }
        int lenA = sidLength(a);
        int lenB = sidLength(b);
        if (lenA <= 0 || lenA != lenB) {
            return false;
        }
        for (int i = 0; i < lenA; i++) {
            if (a.getByte(i) != b.getByte(i)) {
                return false;
            }
        }
        return true;
    }

    /** SID 字节数（8 + 4×子授权数）；修订号异常返回 -1。 */
    static int sidLength(Pointer sid) {
        if (sid == null) {
            return -1;
        }
        int revision = sid.getByte(0) & 0xFF;
        int subCount = sid.getByte(1) & 0xFF;
        if (revision != 1 || subCount > 15) {
            return -1;
        }
        return 8 + 4 * subCount;
    }

    /** GetSecurityInfo 原始产物（descriptor 由调用方 LocalFree）。 */
    record FetchedAcl(Pointer dacl, Pointer descriptor) {
    }
}
