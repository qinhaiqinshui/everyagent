package dev.everyagent.plugin.sandbox.codex.fw;

import com.sun.jna.Pointer;

import dev.everyagent.plugin.sandbox.codex.setup.SetupErrorReport;
import dev.everyagent.plugin.sandbox.codex.win.NetFwCom;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Offline 账户防火墙规则族（对应 codex setup_provisioning/firewall.rs，
 * 分析文档 §5.2.3；设计文档 §2.5 FirewallInstaller）。
 *
 * <p>四条 block 规则按用户 SID 限定（{@code LocalUserAuthorizedList =
 * "O:LSD:(A;;CC;;;<offline_sid>"}）、NET_FW_ACTION_BLOCK、全 profile：
 * block_outbound/block_inbound（非环回地址段）、block_loopback_tcp（先全禁再收窄到
 * 代理端口补集——收窄失败则保持全禁，fail-closed）、block_loopback_udp（环回全禁）。
 * 规则名用本插件自有前缀 {@code everyagent_codex_}（不与 codex 本体规则互删）。
 *
 * <p>有效性自检（不变量②）：{@code INetFwPolicy2::LocalPolicyModifyState} 必须
 * {@code S_OK + NET_FW_MODIFY_STATE_OK}——GPO 覆盖或部分 profile 生效（S_FALSE）即拒绝
 * （HELPER_FIREWALL_POLICY_INEFFECTIVE，宁可不装也不留无效规则）；
 * 写后读回 LocalUserAuthorizedList 校验含预期 SID（对齐 codex 的
 * HelperFirewallRuleVerifyFailed 路径）。COM 细节见 {@link NetFwCom}（IDispatch 按名调用）。
 */
public final class FirewallInstaller {

    /** 出站 block（非环回）。 */
    public static final String BLOCK_OUTBOUND_NAME = "everyagent_codex_offline_block_outbound";
    /** 入站 block（非环回）。 */
    public static final String BLOCK_INBOUND_NAME = "everyagent_codex_offline_block_inbound";
    /** 环回 TCP block（代理端口补集）。 */
    public static final String BLOCK_LOOPBACK_TCP_NAME =
            "everyagent_codex_offline_block_loopback_tcp";
    /** 环回 UDP block（全禁）。 */
    public static final String BLOCK_LOOPBACK_UDP_NAME =
            "everyagent_codex_offline_block_loopback_udp";
    /** 旧版重叠 allow 规则名（fail-closed 顺序中的删除对象）。 */
    public static final String PROXY_ALLOW_NAME = "everyagent_codex_offline_allow_loopback_proxy";

    /** 环回地址段（协议字面量，对齐 firewall.rs LOOPBACK_REMOTE_ADDRESSES）。 */
    public static final String LOOPBACK_REMOTE_ADDRESSES = "127.0.0.0/8,::/127";
    /** 非环回地址段（127/8 与 ::1 的补集式表达，对齐 NON_LOOPBACK_REMOTE_ADDRESSES）。 */
    public static final String NON_LOOPBACK_REMOTE_ADDRESSES =
            "0.0.0.0-126.255.255.255,128.0.0.0-255.255.255.255,"
                    + "::,::2-ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff";

    /** LocalUserAuthorizedList SDDL 模板（按用户 SID 限定）。 */
    public static final String LOCAL_USER_SPEC_FORMAT = "O:LSD:(A;;CC;;;%s)";

    // ---- NET_FW_* 常量（netfw.h；枚举按 int 传） ----

    /** NET_FW_RULE_DIR_OUT。 */
    public static final int NET_FW_RULE_DIR_OUT = 2;
    /** NET_FW_RULE_DIR_IN。 */
    public static final int NET_FW_RULE_DIR_IN = 1;
    /** NET_FW_IP_PROTOCOL_ANY（256）。 */
    public static final int NET_FW_IP_PROTOCOL_ANY = 256;
    /** NET_FW_IP_PROTOCOL_TCP（6）。 */
    public static final int NET_FW_IP_PROTOCOL_TCP = 6;
    /** NET_FW_IP_PROTOCOL_UDP（17）。 */
    public static final int NET_FW_IP_PROTOCOL_UDP = 17;
    /** NET_FW_ACTION_BLOCK。 */
    public static final int NET_FW_ACTION_BLOCK = 0;
    /** NET_FW_PROFILE2_ALL。 */
    public static final int NET_FW_PROFILE2_ALL = 0x7FFFFFFF;
    /** NET_FW_MODIFY_STATE_OK。 */
    public static final int NET_FW_MODIFY_STATE_OK = 0;

    /** S_OK / S_FALSE。 */
    private static final int S_OK = 0;
    private static final int S_FALSE = 1;

    private FirewallInstaller() {
    }

    /** 环回代理放行面（对齐 ensure_offline_proxy_allowlist 的 fail-closed 顺序）。 */
    public static void ensureOfflineProxyAllowlist(String offlineSid, List<Integer> proxyPorts,
            boolean allowLocalBinding) {
        String localUserSpec = String.format(LOCAL_USER_SPEC_FORMAT, offlineSid);
        try (NetFwCom.Apartment apt = NetFwCom.Apartment.initialize()) {
            Pointer rules = rulesOf(newPolicy());
            if (allowLocalBinding) {
                // local-binding 模式：先删 legacy allow 再删环回 block，防残留例外。
                removeRuleIfPresent(rules, PROXY_ALLOW_NAME);
                removeRuleIfPresent(rules, BLOCK_LOOPBACK_UDP_NAME);
                removeRuleIfPresent(rules, BLOCK_LOOPBACK_TCP_NAME);
                return;
            }
            ensureBlockRule(rules, BLOCK_LOOPBACK_UDP_NAME,
                    "EveryAgent Codex Sandbox Offline - Block Loopback UDP",
                    NET_FW_RULE_DIR_OUT, NET_FW_IP_PROTOCOL_UDP, localUserSpec, offlineSid,
                    LOOPBACK_REMOTE_ADDRESSES, null);
            // 先全禁环回 TCP，再收窄到代理端口补集；收窄失败保持全禁（fail-closed）。
            ensureBlockRule(rules, BLOCK_LOOPBACK_TCP_NAME,
                    "EveryAgent Codex Sandbox Offline - Block Loopback TCP (Except Proxy)",
                    NET_FW_RULE_DIR_OUT, NET_FW_IP_PROTOCOL_TCP, localUserSpec, offlineSid,
                    LOOPBACK_REMOTE_ADDRESSES, null);
            removeRuleIfPresent(rules, PROXY_ALLOW_NAME);
            String blockedPorts = blockedLoopbackTcpRemotePorts(proxyPorts);
            if (blockedPorts != null) {
                ensureBlockRule(rules, BLOCK_LOOPBACK_TCP_NAME,
                        "EveryAgent Codex Sandbox Offline - Block Loopback TCP (Except Proxy)",
                        NET_FW_RULE_DIR_OUT, NET_FW_IP_PROTOCOL_TCP, localUserSpec, offlineSid,
                        LOOPBACK_REMOTE_ADDRESSES, blockedPorts);
            }
        } catch (SetupErrorReport.SetupException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new SetupErrorReport.SetupException(
                    SetupErrorReport.HELPER_FIREWALL_RULE_CREATE_OR_ADD_FAILED, e.getMessage());
        }
    }

    /** 出/入站非环回 block（对齐 ensure_offline_network_blocks）。 */
    public static void ensureOfflineNetworkBlocks(String offlineSid) {
        String localUserSpec = String.format(LOCAL_USER_SPEC_FORMAT, offlineSid);
        try (NetFwCom.Apartment apt = NetFwCom.Apartment.initialize()) {
            Pointer rules = rulesOf(newPolicy());
            ensureBlockRule(rules, BLOCK_OUTBOUND_NAME,
                    "EveryAgent Codex Sandbox Offline - Block Non-Loopback Outbound",
                    NET_FW_RULE_DIR_OUT, NET_FW_IP_PROTOCOL_ANY, localUserSpec, offlineSid,
                    NON_LOOPBACK_REMOTE_ADDRESSES, null);
            ensureBlockRule(rules, BLOCK_INBOUND_NAME,
                    "EveryAgent Codex Sandbox Offline - Block Non-Loopback Inbound",
                    NET_FW_RULE_DIR_IN, NET_FW_IP_PROTOCOL_ANY, localUserSpec, offlineSid,
                    NON_LOOPBACK_REMOTE_ADDRESSES, null);
        } catch (SetupErrorReport.SetupException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new SetupErrorReport.SetupException(
                    SetupErrorReport.HELPER_FIREWALL_RULE_CREATE_OR_ADD_FAILED, e.getMessage());
        }
    }

    /** 卸载路径：按固定规则名删除（不碰无关规则；对齐 cleanup_firewall_rules）。 */
    public static void removeSandboxRules() {
        List<String> errors = new ArrayList<>();
        try (NetFwCom.Apartment apt = NetFwCom.Apartment.initialize()) {
            Pointer rules = rulesOf(newPolicy());
            for (String name : allRuleNames()) {
                try {
                    removeRuleIfPresent(rules, name);
                } catch (RuntimeException e) {
                    errors.add("remove sandbox firewall rule " + name + ": " + e.getMessage());
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new SetupErrorReport.SetupException(
                    SetupErrorReport.HELPER_FIREWALL_RULE_CREATE_OR_ADD_FAILED,
                    String.join("; ", errors));
        }
    }

    /** 全部自有规则名（4 条 block + legacy allow）。 */
    public static List<String> allRuleNames() {
        return List.of(BLOCK_OUTBOUND_NAME, BLOCK_INBOUND_NAME, BLOCK_LOOPBACK_TCP_NAME,
                BLOCK_LOOPBACK_UDP_NAME, PROXY_ALLOW_NAME);
    }

    // ---- LocalPolicyModifyState 自检（纯逻辑，单测覆盖） ----

    /** 校验 LocalPolicyModifyState 结果（对齐 validate_local_policy_modify_result）。 */
    public static void validateLocalPolicyModifyResult(int hr, int modifyState) {
        if (hr != S_OK && hr != S_FALSE) {
            throw new SetupErrorReport.SetupException(
                    SetupErrorReport.HELPER_FIREWALL_POLICY_ACCESS_FAILED,
                    "INetFwPolicy2::LocalPolicyModifyState failed: 0x"
                            + Integer.toUnsignedString(hr, 16));
        }
        if (hr == S_FALSE) {
            throw new SetupErrorReport.SetupException(
                    SetupErrorReport.HELPER_FIREWALL_POLICY_INEFFECTIVE,
                    "local firewall policy modifications do not apply to every current profile"
                            + " (S_FALSE)");
        }
        if (modifyState != NET_FW_MODIFY_STATE_OK) {
            throw new SetupErrorReport.SetupException(
                    SetupErrorReport.HELPER_FIREWALL_POLICY_INEFFECTIVE,
                    "local firewall policy modifications will not take effect:"
                            + " LocalPolicyModifyState=" + modifyState);
        }
    }

    // ---- 代理端口补集（纯逻辑，单测覆盖） ----

    /**
     * 环回 TCP block 的 RemotePorts（对齐 blocked_loopback_tcp_remote_ports）：
     * 放行端口排序去重（0 剔除），返回 1..65535 的补集区间串；全放行返回 null。
     */
    public static String blockedLoopbackTcpRemotePorts(List<Integer> proxyPorts) {
        TreeSet<Integer> allowed = new TreeSet<>();
        for (Integer port : proxyPorts) {
            if (port != null && port != 0) {
                allowed.add(port);
            }
        }
        List<String> ranges = new ArrayList<>();
        long start = 1;
        for (int port : allowed) {
            if (port < start) {
                continue;
            }
            if (port > start) {
                ranges.add(portRangeString(start, port - 1));
            }
            start = port + 1L;
        }
        if (start <= 0xFFFF) {
            ranges.add(portRangeString(start, 0xFFFF));
        }
        return ranges.isEmpty() ? null : String.join(",", ranges);
    }

    static String portRangeString(long start, long end) {
        return start == end ? Long.toString(start) : start + "-" + end;
    }

    // ---- COM 细节（IDispatch 按名调用） ----

    private static Pointer newPolicy() {
        try {
            return NetFwCom.coCreateInstance(NetFwCom.CLSID_NET_FW_POLICY2,
                    NetFwCom.IID_INET_FW_POLICY2);
        } catch (RuntimeException e) {
            throw new SetupErrorReport.SetupException(
                    SetupErrorReport.HELPER_FIREWALL_POLICY_ACCESS_FAILED, e.getMessage());
        }
    }

    private static Pointer rulesOf(Pointer policy) {
        validateLocalPolicyModifyResult(S_OK, NetFwCom.getInt(policy,
                "LocalPolicyModifyState"));
        try {
            return NetFwCom.getDispatch(policy, "Rules");
        } catch (RuntimeException e) {
            throw new SetupErrorReport.SetupException(
                    SetupErrorReport.HELPER_FIREWALL_POLICY_ACCESS_FAILED,
                    "INetFwPolicy2::Rules failed: " + e.getMessage());
        }
    }

    private static void removeRuleIfPresent(Pointer rules, String name) {
        Pointer existing = NetFwCom.callWithStringArgReturningDispatch(rules, "Item", name);
        if (existing == null) {
            return; // 不存在即已清
        }
        NetFwCom.release(existing);
        int hr = NetFwCom.callWithStringArg(rules, "Remove", name);
        if (hr != S_OK) {
            throw new SetupErrorReport.SetupException(
                    SetupErrorReport.HELPER_FIREWALL_RULE_CREATE_OR_ADD_FAILED,
                    "Rules::Remove failed for " + name + ": 0x"
                            + Integer.toUnsignedString(hr, 16));
        }
    }

    /** 幂等装规则：存在复用，否则 CoCreate 后 Add；总是重刷全字段 + 读回校验。 */
    private static void ensureBlockRule(Pointer rules, String name, String description,
            int direction, int protocol, String localUserSpec, String offlineSid,
            String remoteAddresses, String remotePorts) {
        Pointer rule = findOrAdd(rules, name);
        try {
            NetFwCom.putBstr(rule, "Description", description);
            NetFwCom.putI4(rule, "Direction", direction);
            NetFwCom.putI4(rule, "Protocol", protocol);
            NetFwCom.putI4(rule, "Action", NET_FW_ACTION_BLOCK);
            NetFwCom.putBool(rule, "Enabled", true);
            NetFwCom.putI4(rule, "Profiles", NET_FW_PROFILE2_ALL);
            NetFwCom.putBstr(rule, "RemoteAddresses", remoteAddresses);
            if (remotePorts != null) {
                NetFwCom.putBstr(rule, "RemotePorts", remotePorts);
            }
            NetFwCom.putBstr(rule, "LocalUserAuthorizedList", localUserSpec);
            String actual = NetFwCom.getString(rule, "LocalUserAuthorizedList");
            if (!actual.contains(offlineSid)) {
                throw new SetupErrorReport.SetupException(
                        SetupErrorReport.HELPER_FIREWALL_RULE_VERIFY_FAILED,
                        "offline firewall rule user scope mismatch: expected SID " + offlineSid
                                + ", got " + actual);
            }
        } finally {
            NetFwCom.release(rule);
        }
    }

    private static Pointer findOrAdd(Pointer rules, String name) {
        Pointer existing = NetFwCom.callWithStringArgReturningDispatch(rules, "Item", name);
        if (existing != null) {
            return existing;
        }
        Pointer rule = NetFwCom.coCreateInstance(NetFwCom.CLSID_NET_FW_RULE,
                NetFwCom.IID_IDISPATCH);
        try {
            NetFwCom.putBstr(rule, "Name", name);
            NetFwCom.callWithDispatchArg(rules, "Add", rule);
            return rule; // 调用方用毕 Release（规则已入集合，对象仍持引用）
        } catch (RuntimeException e) {
            NetFwCom.release(rule);
            throw new SetupErrorReport.SetupException(
                    SetupErrorReport.HELPER_FIREWALL_RULE_CREATE_OR_ADD_FAILED,
                    "create firewall rule " + name + " failed: " + e.getMessage());
        }
    }
}
