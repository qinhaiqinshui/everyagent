package dev.everyagent.plugin.sandbox.codex.win.struct;

import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.Guid;

import dev.everyagent.plugin.sandbox.codex.win.struct.FwpTypes.FWP_BYTE_BLOB;
import dev.everyagent.plugin.sandbox.codex.win.struct.FwpTypes.FWP_CONDITION_VALUE0;
import dev.everyagent.plugin.sandbox.codex.win.struct.FwpTypes.FWP_VALUE0;

/**
 * FWPM 对象结构体（fwptypes.h；设计文档 §2.1 Fwpuclnt 行拆分）。
 *
 * <p>对应 codex wfp.rs/filter_specs.rs 的 12 条持久 filter 与自有 provider/sublayer：
 * GUID 按 Windows 布局 16 字节（{@link Guid.GUID} Data1 小序 int + Data2/3 小序 short +
 * Data4 8 字节原序）；全部对象 FLAG_PERSISTENT，事务内 Add/DeleteByKey。
 * 成员偏移由 windows-admin 单测断言兜底（设计文档 §8）。
 */
public final class FwpmTypes {

    private FwpmTypes() {
    }

    // ---- 持久化/会话标志（fwptypes.h；codex 全用 PERSISTENT） ----

    public static final int FWPM_SESSION_FLAG_DYNAMIC = 0x00000001;
    public static final int FWPM_PROVIDER_FLAG_PERSISTENT = 0x00000001;
    public static final int FWPM_SUBLAYER_FLAG_PERSISTENT = 0x00000001;
    public static final int FWPM_FILTER_FLAG_PERSISTENT = 0x00000001;

    /** 显示信息（unicode 字符串对）。 */
    @Structure.FieldOrder({ "name", "description" })
    public static final class FWPM_DISPLAY_DATA0 extends Structure {
        public String name;
        public String description;
    }

    /**
     * 引擎会话——wfp.rs::Engine::open：displayName 固定会话名，
     * txnWatchdogTimeoutInMSec 事务看门狗（INFINITE 传 0xFFFFFFFF），flags 常为 0。
     */
    @Structure.FieldOrder({ "sessionKey", "displayName", "flags", "txnWatchdogTimeoutInMSec",
            "processId", "sid", "username", "kernelMode", "vendorData" })
    public static final class FWPM_SESSION0 extends Structure {
        /** 会话 GUID（值语义；传 0 由引擎生成）。 */
        public Guid.GUID sessionKey;
        public String displayName;
        public int flags;
        public int txnWatchdogTimeoutInMSec;
        public int processId;
        /** PSID（输出字段，输入传 null）。 */
        public Pointer sid;
        public String username;
        public byte kernelMode;
        /** SDK 为 FWP_BYTE_BLOB16*，本插件不使用，按裸指针占位（8B 槽位）。 */
        public Pointer vendorData;

        public FWPM_SESSION0() {
            sessionKey = new Guid.GUID();
        }
    }

    /** provider——wfp.rs::ensure_provider：固定自有 GUID + PERSISTENT（容 ALREADY_EXISTS）。 */
    @Structure.FieldOrder({ "providerKey", "displayData", "vendorData", "serviceName" })
    public static final class FWPM_PROVIDER0 extends Structure {
        /** GUID*（ByReference = 指针语义）。 */
        public Guid.GUID.ByReference providerKey;
        public FWPM_DISPLAY_DATA0 displayData;
        public FWP_BYTE_BLOB vendorData;
        public String serviceName;

        public FWPM_PROVIDER0() {
            displayData = new FWPM_DISPLAY_DATA0();
        }
    }

    /** sublayer——wfp.rs::ensure_sublayer：固定自有 GUID + PERSISTENT，weight 排序键。 */
    @Structure.FieldOrder({ "subLayerKey", "displayData", "flags", "providerKey",
            "providerData", "weight" })
    public static final class FWPM_SUBLAYER0 extends Structure {
        /** 值语义 GUID。 */
        public Guid.GUID subLayerKey;
        public FWPM_DISPLAY_DATA0 displayData;
        public short flags;
        public Guid.GUID.ByReference providerKey;
        public FWP_BYTE_BLOB providerData;
        public short weight;

        public FWPM_SUBLAYER0() {
            subLayerKey = new Guid.GUID();
            displayData = new FWPM_DISPLAY_DATA0();
        }
    }

    /** 动作——12 条 filter 全为 FWP_ACTION_BLOCK（BLOCK 不用 union，calloutKey 清零）。 */
    @Structure.FieldOrder({ "type", "calloutKey" })
    public static final class FWPM_ACTION0 extends Structure {
        /** FWP_ACTION_TYPE（FWP_ACTION_BLOCK/PERMIT/CONTINUE）。 */
        public int type;
        /** union{GUID calloutKey; GUID providerContextKey}；值语义 GUID，BLOCK 时零。 */
        public Guid.GUID calloutKey;

        public FWPM_ACTION0() {
            calloutKey = new Guid.GUID();
        }
    }

    /**
     * 过滤条件——codex 12 条 filter 的条件：
     * ALE_USER_ID（SD blob 匹配账户）+ IP_PROTOCOL + IP_REMOTE_PORT（ICMP/DNS/DoT/SMB）。
     */
    @Structure.FieldOrder({ "fieldKey", "matchType", "conditionValue" })
    public static final class FWPM_FILTER_CONDITION0 extends Structure {
        /** FWPM_CONDITION_* GUID（常量 GUID 由后续 filter_specs 等价物定义）。 */
        public Guid.GUID fieldKey;
        /** FWP_MATCH_*（本插件均 EQUAL）。 */
        public int matchType;
        public FWP_CONDITION_VALUE0 conditionValue;

        public FWPM_FILTER_CONDITION0() {
            fieldKey = new Guid.GUID();
            conditionValue = new FWP_CONDITION_VALUE0();
        }
    }

    /**
     * filter——FwpmFilterAdd0 的载荷。数组字段 filterCondition 为
     * {@code FWPM_FILTER_CONDITION0*}（指向 N 元素数组的指针）：声明为裸 Pointer，
     * 由调用方把 Structure[] 平铺进 Memory 后填首地址；context 为
     * union{UINT64 rawContext; GUID providerContextKey}（16B 槽位，本插件不用，
     * 按值语义 GUID 占位保证 reserved/filterId/effectiveWeight 偏移正确）。
     */
    @Structure.FieldOrder({ "filterKey", "displayData", "flags", "providerKey", "providerData",
            "layerKey", "subLayerKey", "weight", "numFilterConditions", "filterCondition",
            "action", "context", "reserved", "filterId", "effectiveWeight" })
    public static final class FWPM_FILTER0 extends Structure {
        /** 值语义：固定自有 filter GUID（DeleteByKey0 先删旧副本再重加）。 */
        public Guid.GUID filterKey;
        public FWPM_DISPLAY_DATA0 displayData;
        public int flags;
        public Guid.GUID.ByReference providerKey;
        public FWP_BYTE_BLOB providerData;
        /** 层 GUID：FWPM_LAYER_ALE_AUTH_CONNECT_V4/V6、ALE_RESOURCE_ASSIGNMENT_V4/V6。 */
        public Guid.GUID layerKey;
        public Guid.GUID subLayerKey;
        public FWP_VALUE0 weight;
        public int numFilterConditions;
        /** FWPM_FILTER_CONDITION0*（见类注释）。 */
        public Pointer filterCondition;
        public FWPM_ACTION0 action;
        /** union{UINT64 rawContext; GUID providerContextKey}——GUID 占位 16B。 */
        public Guid.GUID context;
        public String reserved;
        /** 引擎输出的 filterId（添加时输入 0）。 */
        public long filterId;
        public FWP_VALUE0 effectiveWeight;

        public FWPM_FILTER0() {
            filterKey = new Guid.GUID();
            displayData = new FWPM_DISPLAY_DATA0();
            layerKey = new Guid.GUID();
            subLayerKey = new Guid.GUID();
            weight = new FWP_VALUE0();
            action = new FWPM_ACTION0();
            context = new Guid.GUID();
            effectiveWeight = new FWP_VALUE0();
        }
    }
}
