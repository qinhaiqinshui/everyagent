package dev.everyagent.plugin.sandbox.codex.fw;

import com.sun.jna.Platform;

import dev.everyagent.plugin.sandbox.codex.setup.SetupErrorReport;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FirewallInstaller 单测：地址段常量/端口补集/有效性自检（跨平台纯逻辑）
 * + Windows 专属 COM 面 assumeTrue 守卫骨架（对齐 firewall.rs tests）。
 */
class FirewallInstallerTest {

    @Test
    void remoteAddressLiteralsMatchCodexConstants() {
        // 协议字面量逐字对齐 firewall.rs（COM 侧已由 codex 单测确认可接受）
        assertEquals("127.0.0.0/8,::/127", FirewallInstaller.LOOPBACK_REMOTE_ADDRESSES);
        assertEquals("0.0.0.0-126.255.255.255,128.0.0.0-255.255.255.255,"
                        + "::,::2-ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
                FirewallInstaller.NON_LOOPBACK_REMOTE_ADDRESSES);
        // 非环回段是 127/8（v4）与 ::1（v6）的补集：不包含任何环回地址
        assertTrue(!FirewallInstaller.NON_LOOPBACK_REMOTE_ADDRESSES.contains("127."));
        assertTrue(!FirewallInstaller.NON_LOOPBACK_REMOTE_ADDRESSES.contains("::1,"));
    }

    @Test
    void localUserSpecSddlFormat() {
        assertEquals("O:LSD:(A;;CC;;;S-1-5-21-1-2-3-4)",
                String.format(FirewallInstaller.LOCAL_USER_SPEC_FORMAT, "S-1-5-21-1-2-3-4"));
    }

    @Test
    void ruleNamesUsePluginOwnPrefix() {
        for (String name : FirewallInstaller.allRuleNames()) {
            assertTrue(name.startsWith("everyagent_codex_"),
                    "自有前缀（不与 codex 本体规则互删）: " + name);
        }
        assertEquals(5, FirewallInstaller.allRuleNames().size(), "4 block + legacy allow");
    }

    @Test
    void loopbackTcpComplementForSingleProxyPort() {
        // 补集语义：放行 8080，block 1-8079 与 8081-65535（对齐
        // blocked_loopback_tcp_remote_ports 的端口-1/端口+1 边界）
        assertEquals("1-8079,8081-65535",
                FirewallInstaller.blockedLoopbackTcpRemotePorts(List.of(8080)));
    }

    @Test
    void loopbackTcpComplementHandlesEdgesAndCleanup() {
        assertEquals("1-65535", FirewallInstaller.blockedLoopbackTcpRemotePorts(List.of()));
        assertNull(FirewallInstaller.blockedLoopbackTcpRemotePorts(allPorts()),
                "全放行 → 无端口条件");
        assertEquals("1-65534",
                FirewallInstaller.blockedLoopbackTcpRemotePorts(List.of(65535)));
        assertEquals("2-65535", FirewallInstaller.blockedLoopbackTcpRemotePorts(List.of(1)));
        // 排序去重 + 0 剔除（对齐 blocked_loopback_tcp_remote_ports）
        assertEquals("1-8079,8081-9089,9091-65535",
                FirewallInstaller.blockedLoopbackTcpRemotePorts(
                        List.of(9090, 8080, 9090, 0)));
        assertEquals("1-65535",
                FirewallInstaller.blockedLoopbackTcpRemotePorts(List.of(0)),
                "0 剔除后等价于全禁（无放行面）");
    }

    @Test
    void portRangeStringCollapsesSinglePort() {
        assertEquals("53", FirewallInstaller.portRangeString(53, 53));
        assertEquals("1-52", FirewallInstaller.portRangeString(1, 52));
    }

    @Test
    void localPolicyModifyStateAcceptsEffectivePolicy() {
        FirewallInstaller.validateLocalPolicyModifyResult(0 /* S_OK */,
                FirewallInstaller.NET_FW_MODIFY_STATE_OK);
    }

    @Test
    void localPolicyModifyStateRejectsIneffectivePolicy() {
        // GP 覆盖（S_OK + 非 OK 状态）
        SetupErrorReport.SetupException e = assertThrows(SetupErrorReport.SetupException.class,
                () -> FirewallInstaller.validateLocalPolicyModifyResult(0, 1 /* GP_OVERRIDE */));
        assertEquals(SetupErrorReport.HELPER_FIREWALL_POLICY_INEFFECTIVE, e.code());
    }

    @Test
    void localPolicyModifyStateRejectsPartialProfileCoverage() {
        // S_FALSE：仅部分 profile 生效（对齐 codex 测试三分支）
        SetupErrorReport.SetupException e = assertThrows(SetupErrorReport.SetupException.class,
                () -> FirewallInstaller.validateLocalPolicyModifyResult(1 /* S_FALSE */, 0));
        assertEquals(SetupErrorReport.HELPER_FIREWALL_POLICY_INEFFECTIVE, e.code());
    }

    @Test
    void localPolicyModifyStateRejectsFailedQuery() {
        SetupErrorReport.SetupException e = assertThrows(SetupErrorReport.SetupException.class,
                () -> FirewallInstaller.validateLocalPolicyModifyResult(0x80004005 /* E_FAIL */,
                        0));
        assertEquals(SetupErrorReport.HELPER_FIREWALL_POLICY_ACCESS_FAILED, e.code());
    }

    // ---- Windows 专属（assumeTrue 守卫；设计 §8——需要 COM/admin 的走 windows-admin） ----

    @Test
    void windowsComApartmentInitializesAndToleratesChangedMode() {
        Assumptions.assumeTrue(Platform.isWindows(), "Windows 专属");
        // TODO(windows-admin)：断言 CoInitializeEx + CoCreateInstance(NetFwPolicy2)
        //  + LocalPolicyModifyState 读值；本骨架仅验证 apartment 守卫可用。
        try (dev.everyagent.plugin.sandbox.codex.win.NetFwCom.Apartment apt =
                dev.everyagent.plugin.sandbox.codex.win.NetFwCom.Apartment.initialize()) {
            assertTrue(true, "STA 初始化或 RPC_E_CHANGED_MODE 容忍");
        } catch (RuntimeException e) {
            throw new AssertionError("COM init 不应失败", e);
        }
    }

    private static List<Integer> allPorts() {
        Integer[] ports = new Integer[65535];
        for (int i = 0; i < ports.length; i++) {
            ports[i] = i + 1;
        }
        return List.of(ports);
    }
}
