package dev.everyagent.plugin.sandbox.codex.proxy;

import dev.everyagent.plugin.sandbox.codex.CodexSandboxOptions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ProxyRegistry} 生命周期语义：按 codexHome 键、ensureStarted 幂等单飞、
 * disabled no-op、stopAll 全量收口、纯查询零副作用、测试注入钩子。
 */
class ProxyRegistryTest {

    @TempDir
    Path codexHome;

    @AfterEach
    void tearDown() {
        ProxyRegistry.stopAll();
        ProxyRegistry.removeForTesting(codexHome);
    }

    private CodexSandboxOptions options() {
        return new CodexSandboxOptions(codexHome, null, null, null, false, null);
    }

    @Test
    void ensureStartedBindsAndLookupSeesIt() throws IOException {
        assertFalse(ProxyRegistry.endpointIfRunning(codexHome).isPresent(),
                "未启动时纯查询返回空(零副作用)");
        Optional<ProxyRegistry.Endpoint> endpoint = ProxyRegistry.ensureStarted(options());
        assertTrue(endpoint.isPresent());
        assertEquals("http://127.0.0.1:" + endpoint.get().port(), endpoint.get().proxyUrl());
        Optional<ProxyRegistry.Endpoint> lookup = ProxyRegistry.endpointIfRunning(codexHome);
        assertTrue(lookup.isPresent(), "启动后纯查询可见(executor 据此注入 env)");
        assertEquals(endpoint.get().port(), lookup.get().port());
        assertTrue(lookup.get().port() >= LoopbackHttpProxy.MANAGED_RANGE_START
                && lookup.get().port() <= LoopbackHttpProxy.MANAGED_RANGE_END,
                "默认配置落管理段(默认首选 3128 在段内)");
    }

    @Test
    void ensureStartedIsIdempotentSamePort() throws IOException {
        int first = ProxyRegistry.ensureStarted(options()).orElseThrow().port();
        int second = ProxyRegistry.ensureStarted(options()).orElseThrow().port();
        assertEquals(first, second, "重复 ensure 不重复绑定(幂等)");
    }

    @Test
    void lookupKeyedByCanonicalCodexHome() throws IOException {
        ProxyRegistry.ensureStarted(options());
        // 相同路径的不同形态(相对/多余分隔符)应命中同一条目
        Path aliased = codexHome.toAbsolutePath().normalize();
        assertTrue(ProxyRegistry.endpointIfRunning(aliased).isPresent(),
                "键按 canonical 路径归一,路径形态差异不产生第二份代理");
        assertFalse(ProxyRegistry.endpointIfRunning(codexHome.resolve("other")).isPresent(),
                "别的 codexHome 不受影响");
    }

    @Test
    void disabledOptionsIsNoOp() throws IOException {
        CodexSandboxOptions disabled = new CodexSandboxOptions(codexHome, null, null, null,
                false, null, false, 2, 600_000, 1_800_000, 64,
                false, 3128, false, false, List.of());
        assertFalse(disabled.proxyEnabled());
        assertFalse(ProxyRegistry.ensureStarted(disabled).isPresent(),
                "proxy-enabled=false 时 ensure 是 no-op");
        assertFalse(ProxyRegistry.endpointIfRunning(codexHome).isPresent());
    }

    @Test
    void invalidPreferredPortNormalizedInOptions() {
        CodexSandboxOptions zero = new CodexSandboxOptions(codexHome, null, null, null,
                false, null, true, 2, 600_000, 1_800_000, 64,
                true, 0, false, false, List.of());
        assertEquals(CodexSandboxOptions.DEFAULT_PROXY_PREFERRED_PORT, zero.proxyPreferredPort(),
                "端口 0 配置被拒绝(无法预声明/地址不稳定),归一为默认——绝不随机端口");
        CodexSandboxOptions negative = new CodexSandboxOptions(codexHome, null, null, null,
                false, null, true, 2, 600_000, 1_800_000, 64,
                true, -5, false, false, List.of());
        assertEquals(CodexSandboxOptions.DEFAULT_PROXY_PREFERRED_PORT, negative.proxyPreferredPort());
        CodexSandboxOptions tooBig = new CodexSandboxOptions(codexHome, null, null, null,
                false, null, true, 2, 600_000, 1_800_000, 64,
                true, 70_000, false, false, List.of());
        assertEquals(CodexSandboxOptions.DEFAULT_PROXY_PREFERRED_PORT, tooBig.proxyPreferredPort());
    }

    @Test
    void stopAllClosesEverything() throws IOException {
        ProxyRegistry.ensureStarted(options());
        assertTrue(ProxyRegistry.endpointIfRunning(codexHome).isPresent());
        ProxyRegistry.stopAll();
        assertFalse(ProxyRegistry.endpointIfRunning(codexHome).isPresent(),
                "stopAll 后查询为空(deactivate 随 worker 收口)");
    }

    @Test
    void testingHookRegistersFakeEndpoint() {
        ProxyRegistry.putForTesting(codexHome, 45678);
        Optional<ProxyRegistry.Endpoint> lookup = ProxyRegistry.endpointIfRunning(codexHome);
        assertTrue(lookup.isPresent());
        assertEquals(45678, lookup.get().port());
        assertEquals("http://127.0.0.1:45678", lookup.get().proxyUrl());
        ProxyRegistry.removeForTesting(codexHome);
        assertFalse(ProxyRegistry.endpointIfRunning(codexHome).isPresent());
    }

    @Test
    void denyHostsFlowFromOptionsToPolicy() throws IOException {
        CodexSandboxOptions withDeny = new CodexSandboxOptions(codexHome, null, null, null,
                false, null, true, 2, 600_000, 1_800_000, 64,
                true, 3128, true, true,
                List.of("EVIL.example", "evil.example", " ", ""));
        assertEquals(List.of("evil.example"), withDeny.proxyDenyHosts(),
                "禁配清单去重/去空/小写归一");
        assertTrue(withDeny.proxyAllowLoopbackTargets());
        assertTrue(withDeny.proxyBlockPrivateTargets());
    }
}
