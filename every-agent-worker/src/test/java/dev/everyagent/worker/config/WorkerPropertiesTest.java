package dev.everyagent.worker.config;

import dev.everyagent.worker.config.WorkerProperties.HubConfig;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        HubConfig hubA = new HubConfig("ws://hub-a:6101/ws", "key-a", "hub-secret");
        HubConfig hubB = new HubConfig("ws://hub-b:6101/ws", "key-b", "hub-secret");
        HubConfig hubC = new HubConfig("ws://hub-c:6101/ws", "key-c", "hub-secret");
        props.setHubs(Arrays.asList(hubA, hubB, hubC));

        List<HubConfig> out = props.resolveHubs();

        assertNotNull(out);
        assertEquals(3, out.size());
        assertSame(hubA, out.get(0));
        assertSame(hubB, out.get(1));
        assertSame(hubC, out.get(2));
        assertEquals("ws://hub-a:6101/ws", out.get(0).getUrl());
        assertEquals("key-a", out.get(0).getApiKey());
        assertEquals("key-b", out.get(1).getApiKey());
        assertEquals("key-c", out.get(2).getApiKey());
    }

    // 场景 3:过滤 null 项、url 为 null / blank 的项,只保留有效项
    @Test
    void resolveHubs_filtersNullAndBlankUrlEntries() {
        WorkerProperties props = new WorkerProperties();
        HubConfig valid = new HubConfig("ws://hub-valid:6101/ws", "key-valid", "hub-secret");
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
        // 默认:persistent-root 为空 → <系统目录>/sandbox(数据目录已移除,sandbox 升顶级)
        java.nio.file.Path root = props.resolveSandboxPersistentRoot();
        assertEquals(props.resolveHomeDir().resolve("sandbox").toAbsolutePath().normalize(), root);
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
        HubConfig hubA = new HubConfig("ws://hub-a:6101/ws", "key-a", "hub-secret");
        HubConfig blankUrl = new HubConfig("", "key-blank", "hub-secret");
        HubConfig hubB = new HubConfig("ws://hub-b:6101/ws", "key-b", "hub-secret");
        props.setHubs(Arrays.asList(hubA, blankUrl, hubB));

        List<HubConfig> out = props.resolveHubs();

        assertNotNull(out);
        assertEquals(2, out.size());
        assertSame(hubA, out.get(0));
        assertSame(hubB, out.get(1));
    }

    // ===== resolveHubs 多来源合并(2026-10 修复:desktop-worker.yml 过期快照整表覆盖用户配置)=====

    /**
     * 构造模拟「home-dir 下 application-worker.yaml」的 ConfigData 来源
     * (来源名形如 Config resource 'file [<home>/application-worker.yaml]' via location ...;
     * resolveHubs 按该路径与 resolveHomeDir() 精确锚定,仅文件名匹配不算)。
     */
    private static org.springframework.core.env.MapPropertySource userFileSource(
            java.nio.file.Path home, Map<String, Object> flatProps) {
        return new org.springframework.core.env.MapPropertySource(
                "Config resource 'file [" + home.resolve("application-worker.yaml")
                        + "]' via location 'optional:file:...'", flatProps);
    }

    /** 临时 home 目录(props.home-dir 与来源名路径同源,保证锚定吻合)。 */
    private static java.nio.file.Path tempHome() {
        try {
            return java.nio.file.Files.createTempDirectory("wp-home").toAbsolutePath().normalize();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 桌面场景:hubs(绑定终值)= desktop-worker.yml 的列表(本机回环条目 + 过期快照远程条目);
     * 用户文件 application-worker.yaml 定义了最新远程条目 → 合并 = [本机回环, 用户远程],
     * 快照远程条目被忽略(以用户文件为准),本机条目恒 hubs[0](/admin 认证语义不变)。
     */
    @Test
    void resolveHubs_mergesUserFileRemoteWithDesktopLoopback() {
        java.nio.file.Path home = tempHome();
        WorkerProperties props = new WorkerProperties();
        props.setHomeDir(home.toString());
        props.setHubs(Arrays.asList(
                new HubConfig("ws://127.0.0.1:6101/ws", "desktop-api", "desktop-hub"),
                new HubConfig("ws://stale-remote:6101/ws", "stale-api", "stale-hub")));
        org.springframework.mock.env.MockEnvironment env = new org.springframework.mock.env.MockEnvironment();
        env.getPropertySources().addFirst(userFileSource(home, Map.of(
                "worker.hubs[0].url", "ws://120.25.206.241:6101/ws",
                "worker.hubs[0].api-key", "user-api",
                "worker.hubs[0].hub-key", "user-hub")));
        props.setEnvironment(env);

        List<HubConfig> out = props.resolveHubs();

        assertEquals(2, out.size());
        assertEquals("ws://127.0.0.1:6101/ws", out.get(0).getUrl());
        assertEquals("desktop-api", out.get(0).getApiKey());
        assertEquals("ws://120.25.206.241:6101/ws", out.get(1).getUrl());
        assertEquals("user-api", out.get(1).getApiKey());
    }

    /** 用户文件存在但未定义 worker.hubs(无该键)→ 维持绑定结果整表生效的现状语义(纯部署不变)。 */
    @Test
    void resolveHubs_keepsBoundWhenUserSourceHasNoHubs() {
        java.nio.file.Path home = tempHome();
        WorkerProperties props = new WorkerProperties();
        props.setHomeDir(home.toString());
        props.setHubs(Arrays.asList(
                new HubConfig("ws://127.0.0.1:6101/ws", "dk", "dhk"),
                new HubConfig("ws://snapshot-remote:6101/ws", "sk", "shk")));
        org.springframework.mock.env.MockEnvironment env = new org.springframework.mock.env.MockEnvironment();
        env.getPropertySources().addFirst(userFileSource(home,
                Map.of("worker.models[0].config-id", "m1")));
        props.setEnvironment(env);

        List<HubConfig> out = props.resolveHubs();

        assertEquals(2, out.size()); // 快照条目保留(用户文件未表态,无"以谁为准"可依)
        assertEquals("ws://snapshot-remote:6101/ws", out.get(1).getUrl());
    }

    /** 纯部署(无 desktop-worker.yml 注入):绑定结果即用户文件条目 → 合并后与现状等价。 */
    @Test
    void resolveHubs_pureDeployUserListUnchanged() {
        java.nio.file.Path home = tempHome();
        WorkerProperties props = new WorkerProperties();
        props.setHomeDir(home.toString());
        props.setHubs(Arrays.asList(
                new HubConfig("ws://r1:6101/ws", "k1", "hk1"),
                new HubConfig("ws://r2:6101/ws", "k2", "hk2")));
        org.springframework.mock.env.MockEnvironment env = new org.springframework.mock.env.MockEnvironment();
        env.getPropertySources().addFirst(userFileSource(home, Map.of(
                "worker.hubs[0].url", "ws://r1:6101/ws",
                "worker.hubs[0].api-key", "k1", "worker.hubs[0].hub-key", "hk1",
                "worker.hubs[1].url", "ws://r2:6101/ws",
                "worker.hubs[1].api-key", "k2", "worker.hubs[1].hub-key", "hk2")));
        props.setEnvironment(env);

        List<HubConfig> out = props.resolveHubs();

        assertEquals(Arrays.asList("ws://r1:6101/ws", "ws://r2:6101/ws"),
                out.stream().map(HubConfig::getUrl).toList());
    }

    /** 用户文件的远程条目缺 hub-key → fail-closed 拒绝启动(不静默吞错条目)。 */
    @Test
    void resolveHubs_userFileEntryMissingHubKeyFailsClosed() {
        java.nio.file.Path home = tempHome();
        WorkerProperties props = new WorkerProperties();
        props.setHomeDir(home.toString());
        props.setHubs(List.of(new HubConfig("ws://127.0.0.1:6101/ws", "dk", "dhk")));
        org.springframework.mock.env.MockEnvironment env = new org.springframework.mock.env.MockEnvironment();
        env.getPropertySources().addFirst(userFileSource(home, Map.of(
                "worker.hubs[0].url", "ws://remote:6101/ws",
                "worker.hubs[0].api-key", "k")));
        props.setEnvironment(env);

        assertThrows(IllegalStateException.class, props::resolveHubs);
    }

    /**
     * 来源名仅文件名匹配、路径不锚定(≠ home-dir 下)→ 不参与合并:
     * 测试场景 spring.config.import 占位符解析早于 @DynamicPropertySource,会吸入
     * 开发机真实用户文件(路径=真实 home),而生效 home-dir 已被测试属性改指别处——
     * 该来源不得被当作"用户意图"(2026-10 E2E 回归:测试池多出一条真实远程 hub 连接)。
     */
    @Test
    void resolveHubs_ignoresUnanchoredSameNameSource() {
        java.nio.file.Path home = tempHome();
        WorkerProperties props = new WorkerProperties();
        props.setHomeDir(home.toString());
        props.setHubs(List.of(new HubConfig("ws://127.0.0.1:6101/ws", "dk", "dhk")));
        org.springframework.mock.env.MockEnvironment env = new org.springframework.mock.env.MockEnvironment();
        // 名字含 application-worker.yaml 但路径是另一个目录(开发机真实 home)
        env.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                "Config resource 'file [C:\\real-home\\application-worker.yaml]' via location 'optional:file:...'",
                Map.of("worker.hubs[0].url", "ws://intruder:6101/ws",
                        "worker.hubs[0].api-key", "k", "worker.hubs[0].hub-key", "hk")));
        props.setEnvironment(env);

        List<HubConfig> out = props.resolveHubs();

        assertEquals(1, out.size());
        assertEquals("ws://127.0.0.1:6101/ws", out.get(0).getUrl()); // 绑定结果原样,无关来源被忽略
    }

    /** 回环判定与 desktop credentials.isLoopbackWsUrl 同集(localhost/127.0.0.1/[::1]/::1/0.0.0.0)。 */
    @Test
    void isLoopbackHubUrl_matchesLoopbackHostsOnly() {
        assertTrue(WorkerProperties.isLoopbackHubUrl("ws://localhost:6101/ws"));
        assertTrue(WorkerProperties.isLoopbackHubUrl("ws://127.0.0.1:6101/ws"));
        assertTrue(WorkerProperties.isLoopbackHubUrl("ws://0.0.0.0:6101/ws"));
        assertTrue(WorkerProperties.isLoopbackHubUrl("ws://[::1]:6101/ws"));
        assertTrue(WorkerProperties.isLoopbackHubUrl("  ws://LOCALHOST:6101/ws  "));
        assertFalse(WorkerProperties.isLoopbackHubUrl("ws://120.25.206.241:6101/ws"));
        assertFalse(WorkerProperties.isLoopbackHubUrl("ws://hub.example.com:6101/ws"));
        assertFalse(WorkerProperties.isLoopbackHubUrl(null));
        assertFalse(WorkerProperties.isLoopbackHubUrl(""));
        assertFalse(WorkerProperties.isLoopbackHubUrl("::::not-a-uri"));
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

    // ---- Sandbox.resolveMemoryLimitMb():-1 = 自动(总内存 <8GB 不限,≥8GB 限 70%) ----

    @Test
    void memoryLimitDefaultsToAuto() {
        // 默认 -1 = 自动模式(不再是历史硬编码 512/4096)
        assertEquals(-1, new WorkerProperties.Sandbox().getMemoryLimitMb());
    }

    @Test
    void explicitMemoryLimitPassesThrough() {
        WorkerProperties.Sandbox s = new WorkerProperties.Sandbox();
        s.setMemoryLimitMb(0);
        assertEquals(0, s.resolveMemoryLimitMb(), "0 = 显式不限制");
        s.setMemoryLimitMb(4096);
        assertEquals(4096, s.resolveMemoryLimitMb(), ">0 = 固定上限原样生效");
    }

    @Test
    void autoMemoryLimitFollowsSystemMemory() {
        long totalMb = ((com.sun.management.OperatingSystemMXBean)
                java.lang.management.ManagementFactory.getOperatingSystemMXBean())
                .getTotalMemorySize() / (1024 * 1024);
        WorkerProperties.Sandbox s = new WorkerProperties.Sandbox(); // 默认 -1 = auto
        long resolved = s.resolveMemoryLimitMb();
        if (totalMb < 8L * 1024) {
            assertEquals(0, resolved, "总内存 <8GB 应不限制,实际总内存 " + totalMb + " MB");
        } else {
            assertEquals(Math.round(totalMb * 0.70), resolved,
                    "总内存 ≥8GB 应限 70%,实际总内存 " + totalMb + " MB");
        }
        // 解析结果缓存:二次调用同值
        assertEquals(resolved, s.resolveMemoryLimitMb());
    }
}
