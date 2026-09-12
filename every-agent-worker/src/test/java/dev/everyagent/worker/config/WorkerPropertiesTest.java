package dev.everyagent.worker.config;

import dev.everyagent.worker.config.WorkerProperties.HubConfig;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WorkerProperties.resolveHubs() 纯单测(无 Spring):
 * hubs 是唯一配置入口,空 = 不连任何 hub(返回空列表而非 null),
 * 仅保留 url 非空且非 blank 的有效项,过滤 null / url 为 null / blank 的项。
 */
class WorkerPropertiesTest {

    // 场景 1:hubs 为 null 或空列表 → 返回空列表(非 null,长度 0)
    @Test
    void resolveHubs_returnsEmptyList_whenHubsNull() {
        WorkerProperties props = new WorkerProperties();
        props.setHubs(null);

        List<HubConfig> out = props.resolveHubs();

        assertNotNull(out);
        assertTrue(out.isEmpty());
    }

    @Test
    void resolveHubs_returnsEmptyList_whenHubsEmpty() {
        WorkerProperties props = new WorkerProperties();
        props.setHubs(Collections.emptyList());

        List<HubConfig> out = props.resolveHubs();

        assertNotNull(out);
        assertTrue(out.isEmpty());
    }

    // 场景 2:hubs 含 2-3 个有效 {url, apiKey}(不同 apiKey)→ 原样返回这些项
    @Test
    void resolveHubs_returnsValidHubsAsIs() {
        WorkerProperties props = new WorkerProperties();
        HubConfig hubA = new HubConfig("ws://hub-a:9100/ws", "key-a", "hub-secret");
        HubConfig hubB = new HubConfig("ws://hub-b:9100/ws", "key-b", "hub-secret");
        HubConfig hubC = new HubConfig("ws://hub-c:9100/ws", "key-c", "hub-secret");
        props.setHubs(Arrays.asList(hubA, hubB, hubC));

        List<HubConfig> out = props.resolveHubs();

        assertNotNull(out);
        assertEquals(3, out.size());
        assertSame(hubA, out.get(0));
        assertSame(hubB, out.get(1));
        assertSame(hubC, out.get(2));
        assertEquals("ws://hub-a:9100/ws", out.get(0).getUrl());
        assertEquals("key-a", out.get(0).getApiKey());
        assertEquals("key-b", out.get(1).getApiKey());
        assertEquals("key-c", out.get(2).getApiKey());
    }

    // 场景 3:过滤 null 项、url 为 null / blank 的项,只保留有效项
    @Test
    void resolveHubs_filtersNullAndBlankUrlEntries() {
        WorkerProperties props = new WorkerProperties();
        HubConfig valid = new HubConfig("ws://hub-valid:9100/ws", "key-valid", "hub-secret");
        HubConfig nullUrl = new HubConfig(null, "key-null-url", "hub-secret");
        HubConfig blankUrl = new HubConfig("   ", "key-blank-url", "hub-secret");
        props.setHubs(Arrays.asList(null, valid, nullUrl, blankUrl, null));

        List<HubConfig> out = props.resolveHubs();

        assertNotNull(out);
        assertEquals(1, out.size());
        assertSame(valid, out.get(0));
        assertEquals("key-valid", out.get(0).getApiKey());
    }

    @Test
    void sandboxDefaultsAllowNetworkAndDenyEscalation() {
        WorkerProperties props = new WorkerProperties();
        WorkerProperties.Sandbox s = props.getSandbox();
        // 默认:放行网络(含回环)、拒绝提权
        assertTrue(s.isAllowNetwork());
        assertFalse(s.isAllowPrivilegeEscalation());
        // 默认:seccomp 内核级提权拦截开启(默认要开启)
        assertTrue(s.isInterceptPrivilege());
        // 默认 allowNetwork=true → networkDenied()=false
        assertFalse(s.networkDenied());
        // 显式关闭网络 → 回落 networkPolicy(deny-all 拒网)
        s.setAllowNetwork(false);
        assertTrue(s.networkDenied());
        // networkPolicy 非 deny-all(audit-only)且未显式放行 → 判定放开
        s.setNetworkPolicy("audit-only");
        assertFalse(s.networkDenied());
        s.setNetworkPolicy("deny-all");
        assertTrue(s.networkDenied());
        // 提权开关独立于网络判定
        s.setAllowPrivilegeEscalation(true);
        assertTrue(s.isAllowPrivilegeEscalation());
        assertTrue(s.networkDenied());
    }

    @Test
    void sandboxPersistentRootResolvesToDataDirSandboxByDefault() {
        WorkerProperties props = new WorkerProperties();
        props.setHomeDir("");
        // 默认:persistent-root 为空 → <数据目录>/sandbox;数据目录默认 <系统目录>/data
        java.nio.file.Path root = props.resolveSandboxPersistentRoot();
        assertEquals(props.resolveDataDir().resolve("sandbox").toAbsolutePath().normalize(), root);
    }

    @Test
    void sandboxPersistentRootUsesExplicitWhenSet() {
        WorkerProperties props = new WorkerProperties();
        props.getSandbox().setPersistentRoot("C:\\shared\\eagent-sandbox");
        assertEquals(java.nio.file.Path.of("C:\\shared\\eagent-sandbox").toAbsolutePath().normalize(),
                props.resolveSandboxPersistentRoot());
    }

    @Test
    void sandboxDefaultsForPersistentState() {
        WorkerProperties props = new WorkerProperties();
        // 默认:持久状态开启、持久根默认(子目录挂在 <dataDir>/sandbox)
        assertTrue(props.getSandbox().isPersistentState());
        assertEquals("", props.getSandbox().getPersistentRoot());
    }

    @Test
    void resolveHubs_filtersBlankOnlyAndKeepsOtherValid() {
        WorkerProperties props = new WorkerProperties();
        HubConfig hubA = new HubConfig("ws://hub-a:9100/ws", "key-a", "hub-secret");
        HubConfig blankUrl = new HubConfig("", "key-blank", "hub-secret");
        HubConfig hubB = new HubConfig("ws://hub-b:9100/ws", "key-b", "hub-secret");
        props.setHubs(Arrays.asList(hubA, blankUrl, hubB));

        List<HubConfig> out = props.resolveHubs();

        assertNotNull(out);
        assertEquals(2, out.size());
        assertSame(hubA, out.get(0));
        assertSame(hubB, out.get(1));
    }

    @Test
    void programDirUsesExplicitWhenSet() {
        WorkerProperties props = new WorkerProperties();
        props.setProgramDir("C:\\app\\resources");
        assertEquals(java.nio.file.Path.of("C:\\app\\resources").toAbsolutePath().normalize(),
                props.resolveProgramDir());
    }

    @Test
    void runtimeDirResolvesUnderUserDir() {
        WorkerProperties props = new WorkerProperties();
        // 程序附属文件随安装/解压分发到程序根(= JVM 工作目录 user.dir)/runtime,
        // 以字面相对路径 ./runtime 解析,与 program-dir 无关。
        assertEquals(java.nio.file.Path.of("runtime").toAbsolutePath().normalize(),
                props.resolveRuntimeDir());
    }

    @Test
    void programDirFallsBackToUserDirWhenUnset() {
        WorkerProperties props = new WorkerProperties();
        props.setProgramDir("");
        // 未配置时兜底:codeSource 定位本类所在 jar 的目录(测试运行于 target/test-classes,
        // codeSource 指向 classes 目录本身而非 jar,故 resolveProgramDir 非 null 即算通过);
        // 兜底不抛异常、返回绝对路径即可
        java.nio.file.Path p = props.resolveProgramDir();
        assertNotNull(p);
        assertTrue(p.isAbsolute());
    }

    @Test
    void modelsDefaultsToEmptyList() {
        WorkerProperties props = new WorkerProperties();
        assertNotNull(props.getModels());
        assertTrue(props.getModels().isEmpty());
    }

    @Test
    void setModelsNullCoalescesToEmpty() {
        WorkerProperties props = new WorkerProperties();
        props.setModels(null);
        assertNotNull(props.getModels());
        assertTrue(props.getModels().isEmpty());
    }

    @Test
    void modelFieldsAreSettable() {
        WorkerProperties.Model m = new WorkerProperties.Model();
        m.setConfigId("cfg-1");
        m.setProvider("openai-compat");
        m.setBaseUrl("http://x");
        m.setModel("m1");
        m.setApiKey("sk-x");
        m.setIsDefault(true);
        assertEquals("cfg-1", m.getConfigId());
        assertEquals("openai-compat", m.getProvider());
        assertEquals("http://x", m.getBaseUrl());
        assertEquals("m1", m.getModel());
        assertEquals("sk-x", m.getApiKey());
        assertEquals(Boolean.TRUE, m.getIsDefault());
        assertNotNull(m.getParams());
    }

    // ===== retry 退避策略(fixed 默认 / exponential)=====

    @Test
    void retryDefaultsToFixedStrategyWith30RetriesAnd3sInterval() {
        WorkerProperties.Retry r = new WorkerProperties.Retry();
        // 新默认:固定 3s 间隔、最多 30 次重试
        assertEquals(WorkerProperties.Retry.STRATEGY_FIXED, r.getStrategy());
        assertEquals(30, r.getMaxRequestRetries());
        assertEquals(3_000L, r.getBackoffBaseMs());
    }

    @Test
    void fixedStrategyBackoffIsConstantBaseMs() {
        WorkerProperties.Retry r = new WorkerProperties.Retry();
        // fixed 策略:任意 attempt 恒返回 backoffBaseMs(默认 3s),不随次数增长
        for (long attempt = 1; attempt <= 30; attempt++) {
            assertEquals(3_000L, r.backoffMs(attempt), "attempt=" + attempt);
        }
    }

    @Test
    void fixedStrategyHonorsCustomBaseMsAndNonPositiveAttempt() {
        WorkerProperties.Retry r = new WorkerProperties.Retry();
        r.setStrategy(WorkerProperties.Retry.STRATEGY_FIXED);
        r.setBackoffBaseMs(1_500);
        assertEquals(1_500L, r.backoffMs(1));
        assertEquals(1_500L, r.backoffMs(7));
        // attempt ≤ 0 按 1 计,固定间隔不受影响
        assertEquals(1_500L, r.backoffMs(0));
        assertEquals(1_500L, r.backoffMs(-3));
    }

    @Test
    void fixedStrategyIsCaseInsensitive() {
        WorkerProperties.Retry r = new WorkerProperties.Retry();
        r.setStrategy("FIXED");
        assertEquals(3_000L, r.backoffMs(5));
    }

    @Test
    void exponentialStrategyKeepsLegacyFormula() {
        WorkerProperties.Retry r = new WorkerProperties.Retry();
        r.setStrategy(WorkerProperties.Retry.STRATEGY_EXPONENTIAL);
        // base * factor^(attempt-1):3s、15s、75s(与 novel_agent-n computeRetryDelayMs 同式)
        assertEquals(3_000L, r.backoffMs(1));
        assertEquals(15_000L, r.backoffMs(2));
        assertEquals(75_000L, r.backoffMs(3));
        // attempt ≤ 0 按 1 计
        assertEquals(3_000L, r.backoffMs(0));
    }

    @Test
    void unknownOrNullStrategyFallsBackToExponential() {
        WorkerProperties.Retry r = new WorkerProperties.Retry();
        // 未知/空取值回落 exponential(升级前旧配置的行为),不抛异常
        r.setStrategy("bogus");
        assertEquals(15_000L, r.backoffMs(2));
        r.setStrategy(null);
        assertEquals(15_000L, r.backoffMs(2));
        r.setStrategy("");
        assertEquals(15_000L, r.backoffMs(2));
    }
}
