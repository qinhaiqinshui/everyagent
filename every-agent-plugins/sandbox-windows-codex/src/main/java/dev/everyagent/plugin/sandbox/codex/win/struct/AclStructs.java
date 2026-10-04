package dev.everyagent.plugin.sandbox.codex.win.struct;

import com.sun.jna.Pointer;
import com.sun.jna.Structure;

/**
 * ACL/令牌载荷结构体（jna-platform 未收录 EXPLICIT_ACCESS_W/TRUSTEE_W 与裸指针版
 * SID_AND_ATTRIBUTES；设计文档 §2.1 WinStructs 拆分而来）。
 *
 * <p>EXPLICIT_ACCESS 的 ACCESS_MODE/继承标志常量见
 * {@code dev.everyagent.plugin.sandbox.codex.win.Advapi32Ex}。
 */
public final class AclStructs {

    private AclStructs() {
    }

    /** TRUSTEE_FORM：ptstrName 为 SID 裸指针（BuildExplicitAccessWithNameW 用名字形态）。 */
    public static final int TRUSTEE_IS_SID = 0;
    /** TRUSTEE_FORM：ptstrName 为名字字符串。 */
    public static final int TRUSTEE_IS_NAME = 1;
    /** TRUSTEE_TYPE：未知。 */
    public static final int TRUSTEE_IS_UNKNOWN = 0;
    /** TRUSTEE_TYPE：用户（沙箱账户/组用 GROUP）。 */
    public static final int TRUSTEE_IS_USER = 1;
    /** TRUSTEE_TYPE：组（沙箱组基线 ACE）。 */
    public static final int TRUSTEE_IS_GROUP = 2;

    /**
     * 受托人（W 变体；codex acl.rs/token.rs 用 TRUSTEE_IS_SID 形态直接挂 SID 指针，
     * pMultipleTrustee 恒 null）。
     */
    @Structure.FieldOrder({ "pMultipleTrustee", "MultipleTrusteeOperation",
            "TrusteeForm", "TrusteeType", "ptstrName" })
    public static final class TRUSTEE_W extends Structure {
        public Pointer pMultipleTrustee;
        public int MultipleTrusteeOperation;
        public int TrusteeForm;
        public int TrusteeType;
        /**
         * 受托人指针。SID 形态（{@link #TRUSTEE_IS_SID}）下直接放 PSID——
         * 合成 capability SID（S-1-5-21-…）无账户名可查，必须传裸指针，
         * 故声明为 {@link Pointer} 而非 String（String 字段无法承载 PSID）。
         * 名字形态（TRUSTEE_IS_NAME）由调用方自备宽字符内存。
         */
        public Pointer ptstrName;
    }

    /**
     * 显式访问条目（SetEntriesInAclW/BuildSecurityDescriptorW 的输入）。
     *
     * <p><b>数组封送约束（2026-10-03 排障结论）</b>：JNA 把 {@code EXPLICIT_ACCESS_W[]}
     * 传给 native 前会 {@code autoWrite} 并校验元素内存<b>连续</b>——元素经
     * {@code new EXPLICIT_ACCESS_W[]{a, b, ...}} 或逐个 {@code new} 组装的数组各自
     * 持有独立内存，必然触发
     * {@code Structure array elements must use contiguous memory}。多元素数组一律经
     * {@link #contiguous} 拷贝为 {@code toArray()} 连续副本后再传（单元素数组
     * JNA 无连续性校验问题，可直传）。
     */
    @Structure.FieldOrder({ "grfAccessPermissions", "grfAccessMode", "grfInheritance", "Trustee" })
    public static final class EXPLICIT_ACCESS_W extends Structure {
        /** 访问掩码（GENERIC_READ/WRITE/EXECUTE/ALL 或位组合）。 */
        public int grfAccessPermissions;
        /** GRANT/SET/DENY/REVOKE_ACCESS。 */
        public int grfAccessMode;
        /** OBJECT/CONTAINER_INHERIT_ACE 等。 */
        public int grfInheritance;
        /** 内嵌受托人（按值）。 */
        public TRUSTEE_W Trustee;

        public EXPLICIT_ACCESS_W() {
            Trustee = new TRUSTEE_W();
        }
    }

    /**
     * 拷贝为 {@code toArray()} 连续数组（多元素 SetEntriesInAclW 的前置）。
     *
     * <p>字节级拷贝后 {@code read()} 同步字段——否则 JNA 侧 autoWrite 会把
     * 未同步的默认字段值写回、覆盖拷贝内容。trustee.ptstrName 指针值随字节
     * 拷贝带过，其指向的 PSID 内存仍由调用方持有（存活覆盖调用期即可）。
     */
    public static EXPLICIT_ACCESS_W[] contiguous(EXPLICIT_ACCESS_W[] src) {
        if (src == null || src.length <= 1) {
            return src;
        }
        EXPLICIT_ACCESS_W[] out = (EXPLICIT_ACCESS_W[]) new EXPLICIT_ACCESS_W().toArray(src.length);
        for (int i = 0; i < src.length; i++) {
            src[i].write();
            int size = src[i].size();
            byte[] raw = src[i].getPointer().getByteArray(0, size);
            out[i].getPointer().write(0, raw, 0, size);
            out[i].read();
        }
        return out;
    }

    /**
     * 裸指针版 SID_AND_ATTRIBUTES——CreateRestrictedToken 的 restricting 数组元素
     * （Attributes 恒 0）。不复用 jna-platform 的 {@code WinNT.SID_AND_ATTRIBUTES}：
     * 其 Sid 字段是 PSID 结构体类型，序列化时会先写 PSID 内容覆盖目标 SID 内存头部；
     * 此处 Sid 用裸 Pointer，只传指针不改写目标（同 mic 的 TOKEN_MANDATORY_LABEL 教训）。
     */
    @Structure.FieldOrder({ "Sid", "Attributes" })
    public static final class SID_AND_ATTRIBUTES_PTR extends Structure {
        public Pointer Sid;
        public int Attributes;
    }

    /**
     * SetTokenInformation(TokenDefaultDacl) 载荷——token.rs::set_default_dacl：
     * logon SID GENERIC_ALL + OWNER RIGHTS(S-1-3-4) 仅 READ_CONTROL，
     * 把子进程/IPC 对象锁在 runner 登录会话内。
     */
    @Structure.FieldOrder({ "TokenDefaultDacl" })
    public static final class TOKEN_DEFAULT_DACL extends Structure {
        /** PACL 裸指针（SetEntriesInAclW 产物，LocalFree 释放）。 */
        public Pointer TokenDefaultDacl;
    }
}
