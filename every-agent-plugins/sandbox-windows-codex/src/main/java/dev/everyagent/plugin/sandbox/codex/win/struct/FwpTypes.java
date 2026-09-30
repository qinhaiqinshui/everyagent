package dev.everyagent.plugin.sandbox.codex.win.struct;

import com.sun.jna.Pointer;
import com.sun.jna.Structure;

/**
 * FWP 基础类型（fwptypes.h；fwpuclnt 无类型库，全部手写 JNA Structure——
 * 设计文档 §2.1 Fwpuclnt 行、§3「FWPM_FILTER0 的 union 用 Structure 手动读写」拆分而来）。
 *
 * <p>tagged-union（FWP_VALUE0/FWP_CONDITION_VALUE0）以「int type + Pointer value」表达：
 * 本插件只用指针型变体（UINT64 指针、BYTE_BLOB、SID、SD、UNICODE_STRING、V4/V6_ADDR_MASK），
 * 标量型（UINT8/16/32、INT*、FLOAT）不使用；调用方须按 {@code setAutoSynch(false)}
 * 手动 read/write（设计文档 §3），内存布局由 windows-admin 单测断言偏移兜底。
 */
public final class FwpTypes {

    private FwpTypes() {
    }

    // ---- FWP_DATA_TYPE（FWP_VALUE0.type / FWP_CONDITION_VALUE0 的取值类型） ----

    public static final int FWP_EMPTY = 0;
    public static final int FWP_UINT8 = 1;
    public static final int FWP_UINT16 = 2;
    public static final int FWP_UINT32 = 3;
    /** 注意：UINT64 变体传的是指向 64 位值的指针（wfp.rs 的 weight 即此形态）。 */
    public static final int FWP_UINT64 = 4;
    public static final int FWP_INT8 = 5;
    public static final int FWP_INT16 = 6;
    public static final int FWP_INT32 = 7;
    public static final int FWP_INT64 = 8;
    public static final int FWP_FLOAT = 9;
    public static final int FWP_DOUBLE = 10;
    public static final int FWP_BYTE_ARRAY16_TYPE = 11;
    /** FWPM_FILTER0.providerData 等用。 */
    public static final int FWP_BYTE_BLOB_TYPE = 12;
    public static final int FWP_SID = 13;
    /** FWP_CONDITION_ALE_USER_ID 的载荷类型（SD blob 匹配账户）。 */
    public static final int FWP_SECURITY_DESCRIPTOR_TYPE = 14;
    public static final int FWP_TOKEN_INFORMATION_TYPE = 15;
    public static final int FWP_TOKEN_ACCESS_INFORMATION_TYPE = 16;
    public static final int FWP_UNICODE_STRING_TYPE = 17;
    public static final int FWP_BYTE_ARRAY6_TYPE = 18;
    public static final int FWP_V4_ADDR_MASK = 19;
    public static final int FWP_V6_ADDR_MASK = 20;
    public static final int FWP_RANGE_TYPE = 21;

    // ---- FWP_MATCH_TYPE（FWPM_FILTER_CONDITION0.matchType；filter_specs.rs 用 EQUAL） ----

    public static final int FWP_MATCH_EQUAL = 0;
    public static final int FWP_MATCH_GREATER = 1;
    public static final int FWP_MATCH_LESS = 2;
    public static final int FWP_MATCH_GREATER_OR_EQUAL = 3;
    public static final int FWP_MATCH_LESS_OR_EQUAL = 4;
    public static final int FWP_MATCH_RANGE = 5;
    public static final int FWP_MATCH_FLAGS_ALL_SET = 6;
    public static final int FWP_MATCH_FLAGS_ANY_SET = 7;
    public static final int FWP_MATCH_FLAGS_NONE_SET = 8;

    /** 字节块（FWP_BYTE_BLOB；SD blob/providerData 载体）。 */
    @Structure.FieldOrder({ "size", "data" })
    public static final class FWP_BYTE_BLOB extends Structure {
        /** 元素个数（字节）。 */
        public int size;
        /** 指向数据的裸指针。 */
        public Pointer data;
    }

    /** 16 字节数组（IPv6 地址等；不常用，占位保持完整类型面）。 */
    @Structure.FieldOrder({ "byteArray16" })
    public static final class FWP_BYTE_ARRAY16 extends Structure {
        public byte[] byteArray16 = new byte[16];
    }

    /** IPv4 地址+掩码（本插件 filter 不用，占位）。 */
    @Structure.FieldOrder({ "addr", "mask" })
    public static final class FWP_V4_ADDR_AND_MASK extends Structure {
        /** 网络字节序 UINT32。 */
        public int addr;
        public int mask;
    }

    /** IPv6 地址+掩码（本插件 filter 不用，占位）。 */
    @Structure.FieldOrder({ "addr", "mask" })
    public static final class FWP_V6_ADDR_AND_MASK extends Structure {
        public byte[] addr = new byte[16];
        public byte[] mask = new byte[16];
    }

    /**
     * FWP_VALUE0——tagged union：{@code type（4B）+ pad（4B）+ value（8B）= 16B}。
     * weight 常见用法：{@code type=FWP_UINT64}，value 指向一个 64 位静态值。
     */
    @Structure.FieldOrder({ "type", "value" })
    public static final class FWP_VALUE0 extends Structure {
        /** FWP_DATA_TYPE 之一。 */
        public int type;
        /** union 载荷（指针型变体共用一个槽位）。 */
        public Pointer value;
    }

    /**
     * FWP_CONDITION_VALUE0——条件侧 tagged union：
     * {@code matchType（4B）+ pad（4B）+ value（8B）= 16B}。
     * ALE_USER_ID 条件：value 指向 BuildSecurityDescriptorW 产出的自相对 SD。
     */
    @Structure.FieldOrder({ "matchType", "value" })
    public static final class FWP_CONDITION_VALUE0 extends Structure {
        /** FWP_MATCH_TYPE 之一。 */
        public int matchType;
        public Pointer value;
    }
}
