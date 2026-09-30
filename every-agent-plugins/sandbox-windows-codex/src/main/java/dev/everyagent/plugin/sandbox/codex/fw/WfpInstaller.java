package dev.everyagent.plugin.sandbox.codex.fw;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Guid;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.ptr.PointerByReference;

import dev.everyagent.plugin.sandbox.codex.setup.SetupErrorReport;
import dev.everyagent.plugin.sandbox.codex.win.Advapi32Ex;
import dev.everyagent.plugin.sandbox.codex.win.Fwpuclnt;
import dev.everyagent.plugin.sandbox.codex.win.NetFwCom;
import dev.everyagent.plugin.sandbox.codex.win.WinErr;
import dev.everyagent.plugin.sandbox.codex.win.struct.AclStructs.EXPLICIT_ACCESS_W;
import dev.everyagent.plugin.sandbox.codex.win.struct.FwpTypes;
import dev.everyagent.plugin.sandbox.codex.win.struct.FwpTypes.FWP_BYTE_BLOB;
import dev.everyagent.plugin.sandbox.codex.win.struct.FwpTypes.FWP_CONDITION_VALUE0;
import dev.everyagent.plugin.sandbox.codex.win.struct.FwpmTypes;
import dev.everyagent.plugin.sandbox.codex.win.struct.FwpmTypes.FWPM_FILTER0;
import dev.everyagent.plugin.sandbox.codex.win.struct.FwpmTypes.FWPM_FILTER_CONDITION0;
import dev.everyagent.plugin.sandbox.codex.win.struct.FwpmTypes.FWPM_PROVIDER0;
import dev.everyagent.plugin.sandbox.codex.win.struct.FwpmTypes.FWPM_SESSION0;
import dev.everyagent.plugin.sandbox.codex.win.struct.FwpmTypes.FWPM_SUBLAYER0;

/**
 * WFP 持久过滤器安装器（对应 codex wfp.rs + wfp/filter_specs.rs，分析文档 §5.2.3；
 * 设计文档 §2.5 WfpInstaller——防火墙之后的第二道防线，GPO 清掉防火墙规则时仍拦截）。
 *
 * <p>事务包裹（不变量③）：FwpmEngineOpen0(RPC_C_AUTHN_DEFAULT) → TransactionBegin0 →
 * SubLayer Add0（容 ALREADY_EXISTS）→ 每条 filter 先 DeleteByKey0（容
 * NOT_FOUND）再 Add0 → Commit0；任何失败 Abort0。仅 ALE_AUTH_CONNECT /
 * ALE_RESOURCE_ASSIGNMENT × v4/v6 四层、12 条 BLOCK filter（ICMP/ICMPv6、DNS53、
 * DoT853、SMB445/139），条件 = ALE_USER_ID（SD blob 匹配 offline 账户）+ 协议/端口。
 * NAME_RESOLUTION_CACHE 层有意省略（静态 filter 会 FWP_E_OUT_OF_BOUNDS，对齐 codex 注释）。
 *
 * <p>与 codex 的记录差异：WFP provider 对象暂不注册——既有 FwpmTypes.FWPM_PROVIDER0
 * 绑定缺 flags 字段无法表达 PERSISTENT（provider 只剩会话期身份，无安全价值）；
 * filter 与 sublayer 各自携带 PERSISTENT 标志，重启存续不受影响。绑定补齐后以
 * {@link #PROVIDER_KEY} 接入 provider 注册/删除。
 *
 * <p>sublayer/12 filter 的 key GUID 是本插件新生成的自有标识（不抄 codex 的
 * GUID——WFP 按固定 GUID 识别持久对象，永不重生成以免孤儿化旧对象）。
 */
public final class WfpInstaller {

    private static final String SESSION_NAME = "EveryAgent Codex Windows Sandbox WFP";
    private static final String PROVIDER_NAME = "EveryAgent Codex Windows Sandbox WFP";
    private static final String PROVIDER_DESCRIPTION =
            "Persistent WFP provider for EveryAgent Codex sandbox filters";
    private static final String SUBLAYER_DESCRIPTION =
            "Persistent WFP sublayer for EveryAgent Codex sandbox filters";

    /** 自有 provider GUID（本插件新生成，写死后永不重生成）。 */
    public static final Guid.GUID PROVIDER_KEY =
            guid("62ceb7bd-5211-42d0-a933-4af0039fcb10");
    /** 自有 sublayer GUID。 */
    public static final Guid.GUID SUBLAYER_KEY =
            guid("704033a7-f0c1-4b4b-8a73-c793611caed2");

    /** SUBLAYER weight（0x8000，对齐 codex ensure_sublayer）。 */
    private static final short SUBLAYER_WEIGHT = (short) 0x8000;

    /**
     * ALE_USER_ID 条件访问权（对齐 fwp.h FWP_ACTRL_MATCH_FILTER；windows metadata
     * 取值 1——如与 SDK 头文件不符，以 windows-admin 单测为准修正本常量）。
     */
    public static final int FWP_ACTRL_MATCH_FILTER = 0x00000001;

    // ---- fwpmu.h 平台常量（层/条件 GUID，平台所有；与 codex wfp.rs 引用的同名常量一致； ----
    // ---- 无 SDK 头文件可核对，windows-admin 单测断言 TODO 兜底） ----

    /** FWPM_LAYER_ALE_AUTH_CONNECT_V4。 */
    public static final Guid.GUID LAYER_ALE_AUTH_CONNECT_V4 =
            guid("c38d57d1-05a7-4c33-904f-7fbceee60e82");
    /** FWPM_LAYER_ALE_AUTH_CONNECT_V6。 */
    public static final Guid.GUID LAYER_ALE_AUTH_CONNECT_V6 =
            guid("4a72393b-319a-4bc3-8b61-87d65b5a5601");
    /** FWPM_LAYER_ALE_RESOURCE_ASSIGNMENT_V4。 */
    public static final Guid.GUID LAYER_ALE_RESOURCE_ASSIGNMENT_V4 =
            guid("d5b465da-8440-43bd-9b7e-8ef5e7847798");
    /** FWPM_LAYER_ALE_RESOURCE_ASSIGNMENT_V6。 */
    public static final Guid.GUID LAYER_ALE_RESOURCE_ASSIGNMENT_V6 =
            guid("5b196d11-14a3-4440-8346-81f1d4b36eb1");
    /** FWPM_CONDITION_IP_PROTOCOL。 */
    public static final Guid.GUID CONDITION_IP_PROTOCOL =
            guid("3971ef2b-623e-4f9a-8cb1-6e79b8065896");
    /** FWPM_CONDITION_IP_REMOTE_PORT。 */
    public static final Guid.GUID CONDITION_IP_REMOTE_PORT =
            guid("c35a604d-d22b-4e1a-91b4-68f674ee674b");
    /** FWPM_CONDITION_ALE_USER_ID。 */
    public static final Guid.GUID CONDITION_ALE_USER_ID =
            guid("ffb9cc57-a69c-47b4-8e83-015d9e03e5c9");

    /** IPPROTO_ICMP / IPPROTO_ICMPV6。 */
    private static final int IPPROTO_ICMP = 1;
    private static final int IPPROTO_ICMPV6 = 58;

    /** 条件规格（对齐 filter_specs.rs ConditionSpec）。 */
    sealed interface ConditionSpec permits ConditionSpec.User, ConditionSpec.Protocol,
            ConditionSpec.RemotePort {
        record User() implements ConditionSpec {
        }

        record Protocol(int protocol) implements ConditionSpec {
        }

        record RemotePort(int port) implements ConditionSpec {
        }
    }

    /** 过滤器规格（对齐 filter_specs.rs FilterSpec）。 */
    public record FilterSpec(Guid.GUID key, String name, String description, Guid.GUID layerKey,
            ConditionSpec[] conditions) {
    }

    /** 12 条 filter（语义/顺序对齐 FILTER_SPECS；GUID 为本插件新生成）。 */
    public static final FilterSpec[] FILTER_SPECS = {
        new FilterSpec(guid("b51cb1ae-5ad4-49c3-b138-e6396fab2cb9"),
                "everyagent_wfp_icmp_connect_v4", "Block sandbox-account ICMP connect v4",
                LAYER_ALE_AUTH_CONNECT_V4,
                new ConditionSpec[] { new ConditionSpec.User(),
                        new ConditionSpec.Protocol(IPPROTO_ICMP) }),
        new FilterSpec(guid("5d66822d-4fff-4a1e-9a06-dc54402cac5b"),
                "everyagent_wfp_icmp_connect_v6", "Block sandbox-account ICMP connect v6",
                LAYER_ALE_AUTH_CONNECT_V6,
                new ConditionSpec[] { new ConditionSpec.User(),
                        new ConditionSpec.Protocol(IPPROTO_ICMPV6) }),
        new FilterSpec(guid("088bbcaa-59dc-4726-98c9-3928b26a352c"),
                "everyagent_wfp_icmp_assign_v4", "Block sandbox-account ICMP resource assignment v4",
                LAYER_ALE_RESOURCE_ASSIGNMENT_V4,
                new ConditionSpec[] { new ConditionSpec.User(),
                        new ConditionSpec.Protocol(IPPROTO_ICMP) }),
        new FilterSpec(guid("b3ee8102-e398-4615-acf6-a43f4aab003a"),
                "everyagent_wfp_icmp_assign_v6", "Block sandbox-account ICMP resource assignment v6",
                LAYER_ALE_RESOURCE_ASSIGNMENT_V6,
                new ConditionSpec[] { new ConditionSpec.User(),
                        new ConditionSpec.Protocol(IPPROTO_ICMPV6) }),
        new FilterSpec(guid("fa5b3e63-1a4c-4111-8c55-ed4347eb5007"),
                "everyagent_wfp_dns_53_v4", "Block sandbox-account DNS TCP or UDP port 53 v4",
                LAYER_ALE_AUTH_CONNECT_V4,
                new ConditionSpec[] { new ConditionSpec.User(),
                        new ConditionSpec.RemotePort(53) }),
        new FilterSpec(guid("0fc03eb7-a077-4ec5-8c12-7186eb3084cc"),
                "everyagent_wfp_dns_53_v6", "Block sandbox-account DNS TCP or UDP port 53 v6",
                LAYER_ALE_AUTH_CONNECT_V6,
                new ConditionSpec[] { new ConditionSpec.User(),
                        new ConditionSpec.RemotePort(53) }),
        new FilterSpec(guid("d907ea91-8a84-4f6c-a746-857564c810f4"),
                "everyagent_wfp_dns_853_v4", "Block sandbox-account DNS-over-TLS port 853 v4",
                LAYER_ALE_AUTH_CONNECT_V4,
                new ConditionSpec[] { new ConditionSpec.User(),
                        new ConditionSpec.RemotePort(853) }),
        new FilterSpec(guid("f6c69c83-3638-4a14-868c-a29b5fd8ea7a"),
                "everyagent_wfp_dns_853_v6", "Block sandbox-account DNS-over-TLS port 853 v6",
                LAYER_ALE_AUTH_CONNECT_V6,
                new ConditionSpec[] { new ConditionSpec.User(),
                        new ConditionSpec.RemotePort(853) }),
        new FilterSpec(guid("42b9ebbc-b836-4377-9c70-5d1276adfb67"),
                "everyagent_wfp_smb_445_v4", "Block sandbox-account SMB port 445 v4",
                LAYER_ALE_AUTH_CONNECT_V4,
                new ConditionSpec[] { new ConditionSpec.User(),
                        new ConditionSpec.RemotePort(445) }),
        new FilterSpec(guid("97af29fd-1cac-4545-8749-6c0d4d24258b"),
                "everyagent_wfp_smb_445_v6", "Block sandbox-account SMB port 445 v6",
                LAYER_ALE_AUTH_CONNECT_V6,
                new ConditionSpec[] { new ConditionSpec.User(),
                        new ConditionSpec.RemotePort(445) }),
        new FilterSpec(guid("80b0178e-1a02-4fbd-90da-41306314cd90"),
                "everyagent_wfp_smb_139_v4", "Block sandbox-account SMB port 139 v4",
                LAYER_ALE_AUTH_CONNECT_V4,
                new ConditionSpec[] { new ConditionSpec.User(),
                        new ConditionSpec.RemotePort(139) }),
        new FilterSpec(guid("07428c9e-7241-4d2f-9196-9559859b326c"),
                "everyagent_wfp_smb_139_v6", "Block sandbox-account SMB port 139 v6",
                LAYER_ALE_AUTH_CONNECT_V6,
                new ConditionSpec[] { new ConditionSpec.User(),
                        new ConditionSpec.RemotePort(139) }),
    };

    /** 安装路径的事务等待（INFINITE）；卸载路径给 1s 退避。 */
    private static final int TXN_WAIT_INSTALL_MS = -1; // 0xFFFFFFFF
    private static final int TXN_WAIT_REMOVE_MS = 1_000;

    private WfpInstaller() {
    }

    /** 为 offline 账户安装全部持久 filter（对齐 install_wfp_filters_for_account）。 */
    public static int installForAccount(String offlineAccount) {
        WinNT.HANDLE engine = openEngine(TXN_WAIT_INSTALL_MS);
        try {
            ensureSuccess(Fwpuclnt.INSTANCE.FwpmTransactionBegin0(engine, 0),
                    "FwpmTransactionBegin0");
            boolean committed = false;
            // SD blob 生命周期覆盖 Add 与 Commit（对齐 codex：UserMatchCondition 在
            // 事务提交后才 Drop）
            UserMatchCondition user = UserMatchCondition.forAccount(offlineAccount);
            try {
                ensureSublayer(engine);
                for (FilterSpec spec : FILTER_SPECS) {
                    deleteFilterIfPresent(engine, spec.key());
                    addFilter(engine, spec, user);
                }
                ensureSuccess(Fwpuclnt.INSTANCE.FwpmTransactionCommit0(engine),
                        "FwpmTransactionCommit0");
                committed = true;
                return FILTER_SPECS.length;
            } finally {
                user.close();
                if (!committed) {
                    Fwpuclnt.INSTANCE.FwpmTransactionAbort0(engine);
                }
            }
        } finally {
            Fwpuclnt.INSTANCE.FwpmEngineClose0(engine);
        }
    }

    /** 卸载路径：事务内删全部 filter + sublayer（容 NOT_FOUND 家族）。 */
    public static void remove() {
        WinNT.HANDLE engine = openEngine(TXN_WAIT_REMOVE_MS);
        try {
            ensureSuccess(Fwpuclnt.INSTANCE.FwpmTransactionBegin0(engine, 0),
                    "FwpmTransactionBegin0");
            boolean committed = false;
            try {
                for (FilterSpec spec : FILTER_SPECS) {
                    deleteFilterIfPresent(engine, spec.key());
                }
                // 容忍「对象不存在」族（FWP_E_NOT_FOUND；子层缺失视为已清——
                // FWP_E_SUBLAYER_NOT_FOUND 的精确值无法在本环境核对，windows-admin
                // 单测 TODO 核对后并入容忍集，对齐 codex 的双码容忍）。
                ensureSuccessOr(Fwpuclnt.INSTANCE.FwpmSubLayerDeleteByKey0(engine, SUBLAYER_KEY),
                        "FwpmSubLayerDeleteByKey0", WinErr.FWP_E_NOT_FOUND);
                ensureSuccess(Fwpuclnt.INSTANCE.FwpmTransactionCommit0(engine),
                        "FwpmTransactionCommit0");
                committed = true;
            } finally {
                if (!committed) {
                    Fwpuclnt.INSTANCE.FwpmTransactionAbort0(engine);
                }
            }
        } finally {
            Fwpuclnt.INSTANCE.FwpmEngineClose0(engine);
        }
    }

    // ---- 引擎/事务 ----

    private static WinNT.HANDLE openEngine(int txnWaitTimeoutMs) {
        FWPM_SESSION0 session = new FWPM_SESSION0();
        session.displayName = SESSION_NAME;
        session.flags = 0;
        session.txnWatchdogTimeoutInMSec = txnWaitTimeoutMs;
        session.write();
        WinNT.HANDLEByReference handleRef = new WinNT.HANDLEByReference();
        ensureSuccess(Fwpuclnt.INSTANCE.FwpmEngineOpen0(null, Fwpuclnt.RPC_C_AUTHN_DEFAULT,
                null, session, handleRef), "FwpmEngineOpen0");
        return handleRef.getValue();
    }

    private static void ensureSublayer(WinNT.HANDLE engine) {
        FWPM_SUBLAYER0 sublayer = new FWPM_SUBLAYER0();
        sublayer.subLayerKey = SUBLAYER_KEY;
        sublayer.displayData.name = PROVIDER_NAME;
        sublayer.displayData.description = SUBLAYER_DESCRIPTION;
        sublayer.flags = FwpmTypes.FWPM_SUBLAYER_FLAG_PERSISTENT;
        sublayer.providerKey = null; // 不挂 provider（见类注释：provider 注册暂缓）
        sublayer.weight = SUBLAYER_WEIGHT;
        sublayer.write();
        ensureSuccessOr(Fwpuclnt.INSTANCE.FwpmSubLayerAdd0(engine, sublayer, null),
                "FwpmSubLayerAdd0", WinErr.FWP_E_ALREADY_EXISTS);
    }

    private static void addFilter(WinNT.HANDLE engine, FilterSpec spec,
            UserMatchCondition user) {
        FWPM_FILTER_CONDITION0[] conditions = buildConditions(spec.conditions(), user);
        FWPM_FILTER0 filter = new FWPM_FILTER0();
        filter.filterKey = spec.key();
        filter.displayData.name = spec.name();
        filter.displayData.description = spec.description();
        filter.flags = FwpmTypes.FWPM_FILTER_FLAG_PERSISTENT;
        filter.providerKey = null; // 不挂 provider（见类注释：provider 注册暂缓）
        filter.layerKey = spec.layerKey();
        filter.subLayerKey = SUBLAYER_KEY;
        filter.numFilterConditions = conditions.length;
        filter.filterCondition = conditions[0].getPointer();
        filter.action.type = Fwpuclnt.FWP_ACTION_BLOCK;
        filter.write();
        ensureSuccess(Fwpuclnt.INSTANCE.FwpmFilterAdd0(engine, filter, null,
                new LongByReference()), "FwpmFilterAdd0(" + spec.name() + ")");
    }

    private static FWPM_FILTER_CONDITION0[] buildConditions(ConditionSpec[] specs,
            UserMatchCondition user) {
        FWPM_FILTER_CONDITION0[] out = (FWPM_FILTER_CONDITION0[])
                new FWPM_FILTER_CONDITION0().toArray(specs.length);
        for (int i = 0; i < specs.length; i++) {
            FWPM_FILTER_CONDITION0 c = out[i];
            c.matchType = FwpTypes.FWP_MATCH_EQUAL;
            FWP_CONDITION_VALUE0 value = c.conditionValue;
            if (specs[i] instanceof ConditionSpec.User) {
                c.fieldKey = CONDITION_ALE_USER_ID;
                value.matchType = FwpTypes.FWP_SECURITY_DESCRIPTOR_TYPE;
                value.value = user.blob.getPointer();
            } else if (specs[i] instanceof ConditionSpec.Protocol p) {
                c.fieldKey = CONDITION_IP_PROTOCOL;
                value.matchType = FwpTypes.FWP_UINT8;
                value.value = new Pointer(p.protocol());
            } else if (specs[i] instanceof ConditionSpec.RemotePort rp) {
                c.fieldKey = CONDITION_IP_REMOTE_PORT;
                value.matchType = FwpTypes.FWP_UINT16;
                value.value = new Pointer(rp.port());
            }
            c.write();
        }
        return out;
    }

    private static void deleteFilterIfPresent(WinNT.HANDLE engine, Guid.GUID key) {
        ensureSuccessOr(Fwpuclnt.INSTANCE.FwpmFilterDeleteByKey0(engine, key),
                "FwpmFilterDeleteByKey0", WinErr.FWP_E_FILTER_NOT_FOUND, WinErr.FWP_E_NOT_FOUND);
    }

    /** ALE_USER_ID 的 SD blob（BuildSecurityDescriptorW 产物；close 时 LocalFree）。 */
    private static final class UserMatchCondition implements AutoCloseable {
        private final Pointer securityDescriptor;
        private final FWP_BYTE_BLOB blob = new FWP_BYTE_BLOB();

        static UserMatchCondition forAccount(String account) {
            EXPLICIT_ACCESS_W access = new EXPLICIT_ACCESS_W();
            Advapi32Ex.INSTANCE.BuildExplicitAccessWithNameW(access, account,
                    FWP_ACTRL_MATCH_FILTER, Advapi32Ex.GRANT_ACCESS, 0);
            access.read();
            IntByReference sdSize = new IntByReference();
            PointerByReference sdRef = new PointerByReference();
            int result = Advapi32Ex.INSTANCE.BuildSecurityDescriptorW(null, null, 1,
                    new EXPLICIT_ACCESS_W[] { access }, 0, null, null, 0, sdSize, sdRef);
            if (result != 0) {
                throw new SetupErrorReport.SetupException(SetupErrorReport.HELPER_WFP_INSTALL_FAILED,
                        "BuildSecurityDescriptorW failed: " + result);
            }
            UserMatchCondition condition = new UserMatchCondition(sdRef.getValue());
            condition.blob.size = sdSize.getValue();
            condition.blob.data = condition.securityDescriptor;
            condition.blob.write();
            return condition;
        }

        private UserMatchCondition(Pointer securityDescriptor) {
            this.securityDescriptor = securityDescriptor;
        }

        @Override
        public void close() {
            if (securityDescriptor != null) {
                Kernel32.INSTANCE.LocalFree(securityDescriptor);
            }
        }
    }

    private static void ensureSuccess(int result, String operation) {
        ensureSuccessOr(result, operation);
    }

    private static void ensureSuccessOr(int result, String operation, int... allowed) {
        if (result == 0) {
            return;
        }
        for (int ok : allowed) {
            if (result == ok) {
                return;
            }
        }
        throw new SetupErrorReport.SetupException(SetupErrorReport.HELPER_WFP_INSTALL_FAILED,
                operation + " failed: 0x" + Integer.toUnsignedString(result, 16));
    }

    static Guid.GUID guid(String s) {
        return NetFwCom.guid(s);
    }

    /** SDDL 模板互转之外的 GUID 工具（小序布局/字符串互转复用 jna-platform）。 */
    static Guid.GUID.ByReference guidByReference(Guid.GUID source) {
        return new Guid.GUID.ByReference(source);
    }
}
