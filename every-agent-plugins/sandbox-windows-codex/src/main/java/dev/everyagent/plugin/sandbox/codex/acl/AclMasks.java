package dev.everyagent.plugin.sandbox.codex.acl;

/**
 * ACL 掩码/标志常量——数值逐一与 codex {@code acl.rs}（windows-sys）核对，
 * docs/parts/02-token-acl.md 附录 A 速查表。
 *
 * <p>独立成类：单文件 ≤400 行约束下从 {@link AclPrimitives} 拆出；
 * jna-platform WinNT 虽有同名常量，此处显式集中声明以便与 acl.rs 对照审计。
 */
public final class AclMasks {

    private AclMasks() {
    }

    // ---- GENERIC 位 ----

    /** GENERIC_READ（deny-read 掩码组成部分）。 */
    public static final int GENERIC_READ_MASK = 0x80000000;
    /** GENERIC_WRITE（deny-write 掩码组成部分）。 */
    public static final int GENERIC_WRITE_MASK = 0x40000000;
    /** GENERIC_EXECUTE。 */
    public static final int GENERIC_EXECUTE_MASK = 0x20000000;
    /** GENERIC_ALL（护 deny 检查的并集之一）。 */
    public static final int GENERIC_ALL_MASK = 0x10000000;

    // ---- FILE_GENERIC_*（winnt.h 折算值） ----

    /** FILE_GENERIC_READ = 0x00120089。 */
    public static final int FILE_GENERIC_READ = 0x00120089;
    /** FILE_GENERIC_WRITE = 0x00120116。 */
    public static final int FILE_GENERIC_WRITE = 0x00120116;
    /** FILE_GENERIC_EXECUTE = 0x001200A0。 */
    public static final int FILE_GENERIC_EXECUTE = 0x001200A0;
    /** FILE_ALL_ACCESS = 0x001F01FF（MapGenericMask 的 GenericAll 映射目标）。 */
    public static final int FILE_ALL_ACCESS = 0x001F01FF;

    // ---- 具体权限位 ----

    /** DELETE（删对象本身）。 */
    public static final int DELETE = 0x00010000;
    /** FILE_DELETE_CHILD（删父目录下子项——写授权刻意不含它）。 */
    public static final int FILE_DELETE_CHILD = 0x00000040;
    /** READ_CONTROL（读 SD / 打开句柄探 DACL）。 */
    public static final int READ_CONTROL = 0x00020000;
    /** WRITE_DAC（改 DACL / 写回句柄）。 */
    public static final int WRITE_DAC = 0x00040000;
    /** FILE_WRITE_DATA。 */
    public static final int FILE_WRITE_DATA = 0x00000002;
    /** FILE_APPEND_DATA。 */
    public static final int FILE_APPEND_DATA = 0x00000004;
    /** FILE_WRITE_EA。 */
    public static final int FILE_WRITE_EA = 0x00000010;
    /** FILE_WRITE_ATTRIBUTES。 */
    public static final int FILE_WRITE_ATTRIBUTES = 0x00000100;

    // ---- 组合掩码（acl.rs 语义核心） ----

    /**
     * 写授权掩码 = FILE_GENERIC_READ|WRITE|EXECUTE|DELETE——刻意<b>不含</b>
     * FILE_DELETE_CHILD：对每个继承子孙授 DELETE 而非对父目录授 delete-child，
     * 否则父目录的 delete-child 会绕过 {@code .git} 等受保护子对象上的直接 deny-write ACE
     * （acl.rs WRITE_ALLOW_MASK 注释）。
     */
    public static final int WRITE_ALLOW_MASK =
            FILE_GENERIC_READ | FILE_GENERIC_WRITE | FILE_GENERIC_EXECUTE | DELETE;

    /** deny-write 掩码——删除类权利一并封死，堵住「删掉重建」绕过（acl.rs DenyAceKind::Write）。 */
    public static final int DENY_WRITE_MASK = FILE_GENERIC_WRITE | FILE_WRITE_DATA | FILE_APPEND_DATA
            | FILE_WRITE_EA | FILE_WRITE_ATTRIBUTES | GENERIC_WRITE_MASK | DELETE | FILE_DELETE_CHILD;

    /** deny-read 掩码（acl.rs DenyAceKind::Read）。 */
    public static final int DENY_READ_MASK = FILE_GENERIC_READ | GENERIC_READ_MASK;

    /** 读+执行掩码（读根组授权 / 内建主体已持检查）。 */
    public static final int READ_EXECUTE_MASK = FILE_GENERIC_READ | FILE_GENERIC_EXECUTE;

    /** GRANT 模式「护 deny」检查的并集：授权掩码 ∪ 四个 GENERIC 位。 */
    public static final int GENERIC_GUARD_MASK = GENERIC_READ_MASK | GENERIC_WRITE_MASK
            | GENERIC_EXECUTE_MASK | GENERIC_ALL_MASK;

    // ---- 打开/对象类型/设备常量 ----

    /** FILE_FLAG_BACKUP_SEMANTICS（按名打开目录/重解析点句柄必备）。 */
    public static final int FILE_FLAG_BACKUP_SEMANTICS = 0x02000000;
    /** OPEN_EXISTING。 */
    public static final int OPEN_EXISTING = 3;
    /** FILE_ATTRIBUTE_NORMAL。 */
    public static final int FILE_ATTRIBUTE_NORMAL = 0x00000080;
    /** FILE_SHARE_READ|WRITE|DELETE。 */
    public static final int FILE_SHARE_ALL = 0x7;
    /** SE_FILE_OBJECT（Get/SetSecurityInfo 的文件对象类型）。 */
    public static final int SE_FILE_OBJECT = 1;
    /** SE_KERNEL_OBJECT（\\.\NUL 设备对象类型）。 */
    public static final int SE_KERNEL_OBJECT = 6;
    /** GetFinalPathNameByHandleW：不带卷名（根形如单个 '\'）。 */
    public static final int VOLUME_NAME_NONE = 0x0;
    /** \\.\NUL 设备（stdout/stderr 重定向兜底授权对象）。 */
    public static final String NUL_DEVICE = "\\\\.\\NUL";
}
