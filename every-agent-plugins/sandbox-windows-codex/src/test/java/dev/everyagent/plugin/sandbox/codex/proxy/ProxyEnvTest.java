package dev.everyagent.plugin.sandbox.codex.proxy;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ProxyEnv} 托管 env 注入语义：全集注入、不覆盖用户显式值、旧托管键先剥离
 * 再重注（防旧 worker 端口重放）、NO_PROXY 本地直连值。纯函数无 IO。
 */
class ProxyEnvTest {

    private static final String URL = "http://127.0.0.1:3128";

    @Test
    void injectsFullManagedSetOnCleanEnv() {
        Map<String, String> env = new LinkedHashMap<>();
        ProxyEnv.injectProxyEnv(env, URL);
        assertEquals(URL, env.get("HTTP_PROXY"));
        assertEquals(URL, env.get("http_proxy"), "小写变体成对注入");
        assertEquals(URL, env.get("HTTPS_PROXY"));
        assertEquals(URL, env.get("https_proxy"));
        assertEquals(URL, env.get("WS_PROXY"));
        assertEquals(URL, env.get("ws_proxy"));
        assertEquals(URL, env.get("WSS_PROXY"));
        assertEquals(URL, env.get("wss_proxy"));
        assertEquals(URL, env.get("ALL_PROXY"), "SOCKS 裁剪:ALL_PROXY 回退 http url(codex 关 socks 同款)");
        assertEquals(URL, env.get("all_proxy"));
        assertEquals(URL, env.get("FTP_PROXY"));
        assertEquals(URL, env.get("ftp_proxy"));
        assertEquals(ProxyEnv.NO_PROXY_VALUE, env.get("NO_PROXY"));
        assertEquals(ProxyEnv.NO_PROXY_VALUE, env.get("no_proxy"));
        assertEquals("1", env.get("NODE_USE_ENV_PROXY"), "Node 需显式开关才认 env 代理");
        assertEquals("true", env.get("ELECTRON_GET_USE_PROXY"));
        assertEquals("1", env.get(ProxyEnv.ACTIVE_MARKER), "活性标记(对齐 CODEX_NETWORK_PROXY_ACTIVE 用途)");
        assertTrue(ProxyEnv.isActive(env));
    }

    @Test
    void noProxyValueAllowsLocalDirect() {
        assertEquals("localhost,127.0.0.1,::1", ProxyEnv.NO_PROXY_VALUE,
                "本项目允许本地直连(等价 codex allow_local_binding=true 语义;勿与 codex 管控态的置空 NO_PROXY 混淆)");
    }

    @Test
    void preservesUserExplicitValues() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("HTTP_PROXY", "http://corp-proxy:8080");
        env.put("https_proxy", "http://corp-proxy:8080");
        env.put("NO_PROXY", "localhost,10.0.0.0/8");
        env.put("NODE_USE_ENV_PROXY", "0");
        ProxyEnv.injectProxyEnv(env, URL);
        assertEquals("http://corp-proxy:8080", env.get("HTTP_PROXY"), "用户显式值不覆盖");
        assertEquals("http://corp-proxy:8080", env.get("https_proxy"));
        assertEquals("localhost,10.0.0.0/8", env.get("NO_PROXY"));
        assertEquals("0", env.get("NODE_USE_ENV_PROXY"));
        assertEquals(URL, env.get("WS_PROXY"), "未显式设置的托管键照常注入");
        assertEquals(URL, env.get("FTP_PROXY"));
        assertEquals("1", env.get(ProxyEnv.ACTIVE_MARKER));
    }

    @Test
    void stripsStaleManagedEnvBeforeReinject() {
        // 上一层 EveryAgent 注入的托管 env(旧 worker 的旧端口 9999)——先剥离再按当前端口重注
        Map<String, String> env = new LinkedHashMap<>();
        env.put(ProxyEnv.ACTIVE_MARKER, "1");
        env.put("HTTP_PROXY", "http://127.0.0.1:9999");
        env.put("http_proxy", "http://127.0.0.1:9999");
        env.put("HTTPS_PROXY", "http://127.0.0.1:9999");
        env.put("no_proxy", "stale.example");
        env.put("NODE_USE_ENV_PROXY", "1");
        env.put("ELECTRON_GET_USE_PROXY", "true");
        env.put("USER_DEFINED", "keep-me");
        ProxyEnv.injectProxyEnv(env, URL);
        assertEquals(URL, env.get("HTTP_PROXY"), "旧端口被替换为当前端口");
        assertEquals(URL, env.get("http_proxy"));
        assertEquals(ProxyEnv.NO_PROXY_VALUE, env.get("no_proxy"), "旧 no_proxy 一并重注");
        assertEquals("keep-me", env.get("USER_DEFINED"), "非托管键不碰");
    }

    @Test
    void managedVarDetectionIsCaseInsensitive() {
        assertTrue(ProxyEnv.isManagedProxyEnvVar("http_proxy"));
        assertTrue(ProxyEnv.isManagedProxyEnvVar("HTTP_PROXY"));
        assertTrue(ProxyEnv.isManagedProxyEnvVar("All_Proxy"), "Windows env 大小写不敏感,异体形态同属托管键");
        assertTrue(ProxyEnv.isManagedProxyEnvVar(ProxyEnv.ACTIVE_MARKER));
        assertFalse(ProxyEnv.isManagedProxyEnvVar("NPM_CONFIG_PROXY"), "非托管键(用户/系统自设)不在剥离面");
        assertFalse(ProxyEnv.isManagedProxyEnvVar("PATH"));
    }

    @Test
    void stripRemovesAllManagedVariantsOnly() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("HTTP_PROXY", "http://127.0.0.1:9999");
        env.put("All_Proxy", "http://127.0.0.1:9999");
        env.put("no_proxy", "stale");
        env.put(ProxyEnv.ACTIVE_MARKER, "1");
        env.put("NPM_CONFIG_PROXY", "http://corp:8080");
        ProxyEnv.stripManagedProxyEnv(env);
        assertFalse(env.containsKey("HTTP_PROXY"));
        assertFalse(env.containsKey("All_Proxy"));
        assertFalse(env.containsKey("no_proxy"));
        assertFalse(env.containsKey(ProxyEnv.ACTIVE_MARKER));
        assertTrue(env.containsKey("NPM_CONFIG_PROXY"), "非托管键保留");
    }

    @Test
    void nullSafeNoOp() {
        ProxyEnv.injectProxyEnv(null, URL); // 不抛
        ProxyEnv.injectProxyEnv(new LinkedHashMap<>(), null); // 不抛
        ProxyEnv.injectProxyEnv(new LinkedHashMap<>(), "  "); // 不抛
        assertFalse(ProxyEnv.isActive(null));
    }

    @Test
    void proxyUrlShape() {
        assertEquals("http://127.0.0.1:3128", ProxyEnv.proxyUrl(3128));
    }
}
