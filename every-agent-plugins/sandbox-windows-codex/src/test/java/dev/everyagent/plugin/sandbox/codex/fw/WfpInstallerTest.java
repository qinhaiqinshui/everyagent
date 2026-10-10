package dev.everyagent.plugin.sandbox.codex.fw;

import com.sun.jna.Platform;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WfpInstaller 单测：filter 规格表静态校验（跨平台；对齐 wfp.rs tests 的
 * filter_keys_are_unique / filter_names_are_unique）+ Windows 专属骨架。
 */
class WfpInstallerTest {

    @Test
    void twelveFiltersInstalledAcrossFourAleLayers() {
        assertEquals(12, WfpInstaller.FILTER_SPECS.length, "12 条（对齐 FILTER_SPECS）");
        Set<String> layers = new HashSet<>();
        for (WfpInstaller.FilterSpec spec : WfpInstaller.FILTER_SPECS) {
            layers.add(guidString(spec.layerKey()));
        }
        assertEquals(Set.of(
                guidString(WfpInstaller.LAYER_ALE_AUTH_CONNECT_V4),
                guidString(WfpInstaller.LAYER_ALE_AUTH_CONNECT_V6),
                guidString(WfpInstaller.LAYER_ALE_RESOURCE_ASSIGNMENT_V4),
                guidString(WfpInstaller.LAYER_ALE_RESOURCE_ASSIGNMENT_V6)), layers,
                "恰好 ALE_AUTH_CONNECT/RESOURCE_ASSIGNMENT × v4/v6 四层");
    }

    @Test
    void filterKeysAreUnique() {
        Set<String> keys = new HashSet<>();
        for (WfpInstaller.FilterSpec spec : WfpInstaller.FILTER_SPECS) {
            keys.add(guidString(spec.key()));
        }
        assertEquals(keys.size(), WfpInstaller.FILTER_SPECS.length);
    }

    @Test
    void filterNamesAreUniqueAndOwnPrefix() {
        Set<String> names = new HashSet<>();
        for (WfpInstaller.FilterSpec spec : WfpInstaller.FILTER_SPECS) {
            assertTrue(spec.name().startsWith("everyagent_wfp_"),
                    "自有前缀: " + spec.name());
            names.add(spec.name());
        }
        assertEquals(names.size(), WfpInstaller.FILTER_SPECS.length);
    }

    @Test
    void everyFilterStartsWithUserCondition() {
        for (WfpInstaller.FilterSpec spec : WfpInstaller.FILTER_SPECS) {
            assertTrue(spec.conditions().length >= 2,
                    "ALE_USER_ID + 协议/端口: " + spec.name());
            assertTrue(spec.conditions()[0] instanceof WfpInstaller.ConditionSpec.User,
                    "首条件恒为用户条件（SD blob 匹配 offline 账户）");
        }
        // 4 ICMP（connect/assign × v4/v6）+ 8 端口条件（53/853/445/139 × v4/v6）
        long icmp = java.util.Arrays.stream(WfpInstaller.FILTER_SPECS)
                .filter(s -> s.name().contains("icmp")).count();
        long ported = java.util.Arrays.stream(WfpInstaller.FILTER_SPECS)
                .filter(s -> s.conditions()[1] instanceof WfpInstaller.ConditionSpec.RemotePort)
                .count();
        assertEquals(4, icmp);
        assertEquals(8, ported);
    }

    @Test
    void providerAndSublayerGuidsAreFreshlyOwnedIdentities() {
        // 不与 codex 的 GUID 相同（WFP 按固定 GUID 识别持久对象；混用会互删对方对象）
        Set<String> codexGuids = Set.of(
                "2e31d31c-3948-4753-9117-e5d1a6496f41", // codex provider
                "e65054fd-4d32-4c7c-95ef-621f0cf6431a"); // codex sublayer
        String provider = guidString(WfpInstaller.PROVIDER_KEY);
        String sublayer = guidString(WfpInstaller.SUBLAYER_KEY);
        assertTrue(!codexGuids.contains(provider) && !codexGuids.contains(sublayer),
                "自有新 GUID");
        assertTrue(!provider.equals(sublayer));
        // 与 codex 的 12 个 filter key 亦不重叠
        Set<String> codexFilterKeys = Set.of(
                "9f5f3812-79f0-4fe9-9615-4c2c92d2f0ff", "87498484-45ab-4510-845e-ece8b791b3bc",
                "af4751de-f874-4a7b-a34d-f0d0f22d1d9b", "ea10db66-a928-4b2e-a82e-a376a54f93ba",
                "83172805-f6be-4ae1-9dc6-6847aef04e7f", "d23b2efb-1efb-46b2-96f3-b0ccda5690c8",
                "420b026f-9dc9-4aea-88f4-0f2b9feab39a", "8d917c81-99cc-45e7-84d6-824df860cfb8",
                "e1d6e0af-ce5f-471b-b2d3-15ca00e966f3", "c2bceca4-66ef-4a0f-ba80-f4f761b8c6f0",
                "ba10c618-84e7-4b83-8f74-36e22b2fa1ff", "fe7f22b8-5cf5-4adb-b2aa-71fc0a8f5d44");
        for (WfpInstaller.FilterSpec spec : WfpInstaller.FILTER_SPECS) {
            assertTrue(!codexFilterKeys.contains(guidString(spec.key())),
                    "filter key 不得复用 codex 值: " + spec.name());
        }
    }

    @Test
    void layerAndConditionGuidsAreDistinctNonZero() {
        Set<String> guids = new HashSet<>();
        for (var g : new com.sun.jna.platform.win32.Guid.GUID[] {
                WfpInstaller.LAYER_ALE_AUTH_CONNECT_V4, WfpInstaller.LAYER_ALE_AUTH_CONNECT_V6,
                WfpInstaller.LAYER_ALE_RESOURCE_ASSIGNMENT_V4,
                WfpInstaller.LAYER_ALE_RESOURCE_ASSIGNMENT_V6,
                WfpInstaller.CONDITION_IP_PROTOCOL, WfpInstaller.CONDITION_IP_REMOTE_PORT,
                WfpInstaller.CONDITION_ALE_USER_ID }) {
            String s = guidString(g);
            assertTrue(!s.startsWith("00000000-0000-0000-0000-000000000000"), "非零 GUID");
            guids.add(s);
        }
        assertEquals(7, guids.size(), "互不相同");
        // TODO(windows-admin)：与 SDK fwpmu.h 常量逐一断言相等（本环境无头文件可核对，
        // 运行期失败表现为 FwpmFilterAdd0 校验错误——事务 Abort 兜底 fail-closed）。
    }

    // ---- Windows 专属（assumeTrue 守卫） ----

    @Test
    void windowsEngineOpenTransactionRoundTripSkeleton() {
        Assumptions.assumeTrue(Platform.isWindows(), "Windows 专属");
        // TODO(windows-admin)：FwpmEngineOpen0/事务三段/结构体偏移断言
        // （FWPM_FILTER_CONDITION0 数组 40B/元素等）；此处仅保骨架可发现。
        assertTrue(WfpInstaller.FILTER_SPECS.length > 0);
    }

    private static String guidString(com.sun.jna.platform.win32.Guid.GUID g) {
        return g.toGuidString();
    }
}
