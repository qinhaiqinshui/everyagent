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
    /** TRUSTEE_TYPE：组（EveryAgentCodexUsers 基线 ACE）。 */
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

    /** 显式访问条目（SetEntriesInAclW/BuildSecurityDescriptorW 的输入）。 */
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
