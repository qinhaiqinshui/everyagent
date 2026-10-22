package dev.everyagent.plugin.sandbox.codex.proxy;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 沙箱命令的代理 env 注入（对齐 codex apply_proxy_env_overrides 的默认形态：
 * 仅向沙箱化命令注入、不动系统代理、不写任何证书库）。
 *
 * <p><b>注入集合</b>（值统一 {@code http://127.0.0.1:<port>}——本项目裁剪 SOCKS5，
 * ALL_PROXY/FTP_PROXY 与 codex 关闭 socks 时一致回退 http url）：
 * <ul>
 *   <li>HTTP_PROXY/HTTPS_PROXY/WS_PROXY/WSS_PROXY + 小写变体；</li>
 *   <li>ALL_PROXY/FTP_PROXY + 小写变体（同 http url）；</li>
 *   <li>NO_PROXY/no_proxy = {@code localhost,127.0.0.1,::1}——本项目允许本地直连
 *       （等价 codex allow_local_binding=true 语义；codex 在管控本地回环时把 NO_PROXY
 *       置空，勿混淆）；</li>
 *   <li>NODE_USE_ENV_PROXY=1、ELECTRON_GET_USE_PROXY=true（Node 系需显式开关才认 env 代理）；</li>
 *   <li>EVERY_AGENT_PROXY_ACTIVE=1 活性标记（对齐 CODEX_NETWORK_PROXY_ACTIVE 用途：
 *       下游识别「本 env 由 EveryAgent 托管代理注入」）。</li>
 * </ul>
 *
 * <p><b>两条纪律</b>：
 * <ol>
 *   <li><b>不覆盖用户显式设置</b>——env 中已有的值一律保留（putIfAbsent 语义）；</li>
 *   <li><b>托管键先剥离再重注</b>（对齐 codex strip_managed_proxy_env 精神）——若 env 带
 *       {@link #ACTIVE_MARKER}（说明是上一层 EveryAgent 注入的托管 env，可能携带<b>旧
 *       worker 的旧端口</b>），先把全部托管键剥掉再按当前端口重注，防旧代理地址重放。
 *       无标记 = 用户/系统自设 → 全部尊重不碰。</li>
 * </ol>
 */
public final class ProxyEnv {

    /** 活性标记（也是「env 属 EveryAgent 托管」的识别依据）。 */
    public static final String ACTIVE_MARKER = "EVERY_AGENT_PROXY_ACTIVE";

    /** 本项目允许本地直连的 NO_PROXY 值（语义见类 Javadoc）。 */
    public static final String NO_PROXY_VALUE = "localhost,127.0.0.1,::1";

    /** 托管键全集（剥离与注入共用一张表；大小写变体成对）。 */
    static final Set<String> MANAGED_VARS = Set.of(
            "HTTP_PROXY", "http_proxy",
            "HTTPS_PROXY", "https_proxy",
            "WS_PROXY", "ws_proxy",
            "WSS_PROXY", "wss_proxy",
            "ALL_PROXY", "all_proxy",
            "FTP_PROXY", "ftp_proxy",
            "NO_PROXY", "no_proxy",
            "NODE_USE_ENV_PROXY",
            "ELECTRON_GET_USE_PROXY",
            ACTIVE_MARKER);

    private ProxyEnv() {
    }

    /**
     * 就地注入代理 env（仅 online 身份且代理在跑时由 {@link CodexCommandExecutor} 调用；
     * 纯函数可单测）。
     *
     * @param env      子进程 env（就地修改）
     * @param proxyUrl 形如 {@code http://127.0.0.1:3128}
     */
    public static void injectProxyEnv(Map<String, String> env, String proxyUrl) {
        if (env == null || proxyUrl == null || proxyUrl.isBlank()) {
            return;
        }
        // 剥离上一层注入的托管键（有 ACTIVE_MARKER 才剥——用户自设值不属于托管面）
        if (env.containsKey(ACTIVE_MARKER)) {
            stripManagedProxyEnv(env);
        }
        Map<String, String> managed = new LinkedHashMap<>();
        for (String name : new String[] { "HTTP_PROXY", "http_proxy", "HTTPS_PROXY",
                "https_proxy", "WS_PROXY", "ws_proxy", "WSS_PROXY", "wss_proxy",
                "ALL_PROXY", "all_proxy", "FTP_PROXY", "ftp_proxy" }) {
            managed.put(name, proxyUrl);
        }
        managed.put("NO_PROXY", NO_PROXY_VALUE);
        managed.put("no_proxy", NO_PROXY_VALUE);
        managed.put("NODE_USE_ENV_PROXY", "1");
        managed.put("ELECTRON_GET_USE_PROXY", "true");
        managed.forEach(env::putIfAbsent);
        // 活性标记恒刷新（托管键,端口可能已变,旧值无意义）
        env.put(ACTIVE_MARKER, "1");
    }

    /**
     * 剥离托管代理键（对齐 strip_managed_proxy_env）：只剥 {@link #MANAGED_VARS}，
     * 用户自设的其它变量（如公司代理脚本注入的 NPM_CONFIG_PROXY 等非托管键）不碰。
     * 大小写不敏感匹配——Windows 环境块大小写不敏感，托管键可能以任一大小写形态驻留。
     */
    static void stripManagedProxyEnv(Map<String, String> env) {
        env.keySet().removeIf(ProxyEnv::isManagedProxyEnvVar);
    }

    /** 托管键判定（大小写不敏感；独立纯函数，单测覆盖）。 */
    static boolean isManagedProxyEnvVar(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        String upper = name.toUpperCase(Locale.ROOT);
        for (String managed : MANAGED_VARS) {
            if (managed.equals(upper)) {
                return true;
            }
        }
        return false;
    }

    /** {@link #injectProxyEnv} 的布尔便捷判定（测试/接线防呆）。 */
    public static boolean isActive(Map<String, String> env) {
        return env != null && "1".equals(env.get(ACTIVE_MARKER));
    }

    /** 代理 URL 构造（host 恒 127.0.0.1）。 */
    public static String proxyUrl(int port) {
        return "http://127.0.0.1:" + port;
    }
}
