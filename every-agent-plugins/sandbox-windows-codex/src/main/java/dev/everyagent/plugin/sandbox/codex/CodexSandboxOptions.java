package dev.everyagent.plugin.sandbox.codex;

import dev.everyagent.plugin.api.PluginConfig;
import dev.everyagent.plugin.api.config.WorkerConfig;
import dev.everyagent.plugin.sandbox.codex.accounts.SandboxAccounts;
import dev.everyagent.plugin.sandbox.codex.accounts.SandboxAccounts.NetworkIdentity;
import dev.everyagent.plugin.sandbox.codex.proxy.HostPolicy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * codex 沙箱的插件级配置快照（设计文档 §7，经 plugin.json contributes.config 自管）。
 *
 * <p>在 {@code CodexSandboxPlugin.activate} 里从 {@link PluginConfig}（codex.* 键）与
 * {@link WorkerConfig}（持久根等）解析一次，全插件共享不可变。未接线/未消费的键
 * （extra-read-roots、setup-timeout-ms 等）见 README「限制与遗留」。
 *
 * @param codexHome        状态根（.sandbox/.sandbox-secrets/.sandbox-bin/cap_sid 之父；
 *                         默认 {@code <sandboxPersistentRoot>/codex}，键 codex.home）
 * @param accountPrefix    账户/组前缀（默认 {@link SandboxAccounts#DEFAULT_PREFIX}，
 *                         键 codex.account-prefix；派生名受 SAM 20 字符上限约束，
 *                         见 {@link SandboxAccounts#MAX_USERNAME_LEN}）
 * @param networkPolicy    auto（随 worker networkDenied）/ offline / online 强制
 *                         （键 codex.network-policy）
 * @param proxyPorts       offline 账户放行的环回 TCP 代理端口（键 codex.proxy-ports，
 *                         逗号分隔）
 * @param allowLocalBinding true=移除环回 block 规则（键 codex.allow-local-binding）
 * @param javaHome         runner JVM 的 java.exe 根；空 = 当前 JVM（键 codex.java-home）
 * @param runnerPoolEnabled runner 常驻池化开关（键 codex.runner-pool-enabled，默认 true；
 *                         false = 回到每命令 spawn 旧形态）
 * @param runnerPoolMaxSize 每键（codexHome+账户）池大小上限（键
 *                         codex.runner-pool-max-size，默认
 *                         {@link #DEFAULT_RUNNER_POOL_MAX_SIZE}）
 * @param runnerPoolIdleTtlMs 空闲 runner TTL 毫秒（键 codex.runner-pool-idle-ttl-ms，
 *                         默认 {@link #DEFAULT_RUNNER_POOL_IDLE_TTL_MS}，到期发 shutdown
 *                         关会话、登录会话回收）
 * @param logonSessionAuditIntervalMs 沙箱账户登录会话数审计周期毫秒（键
 *                         codex.logon-session-audit-interval-ms，默认
 *                         {@link #DEFAULT_LOGON_SESSION_AUDIT_INTERVAL_MS}；&lt;=0 关闭）
 * @param logonSessionAuditThreshold 登录会话总数告警阈值（键
 *                         codex.logon-session-audit-threshold，默认
 *                         {@link #DEFAULT_LOGON_SESSION_AUDIT_THRESHOLD}，超过打 WARN）
 * @param proxyEnabled     宿主侧 loopback 代理开关（键 codex.proxy-enabled，默认 true；
 *                         仅 online 身份启动/注入，false = 回到直连行为）
 * @param proxyPreferredPort 代理首选端口（键 codex.proxy-port，默认
 *                         {@link #DEFAULT_PROXY_PREFERRED_PORT}=3128；占用时按管理段
 *                         {@code 3128..3159} 顺序探测。<b>拒绝端口 0/非法值</b>——
 *                         归一为默认值，绝不监听随机端口：随机端口无法预声明、
 *                         env 注入地址不稳定、也无法排障）
 * @param proxyAllowLoopbackTargets true=放行 127/8、::1 代理目标（键
 *                         codex.proxy-allow-loopback-targets，默认 false：合法 localhost
 *                         流量经 NO_PROXY 直连，代理侧拦回环消除「经代理打回宿主本机」
 *                         的 SSRF 绕行面）
 * @param proxyBlockPrivateTargets true=阻断 RFC1918/IPv6 ULA 代理目标（键
 *                         codex.proxy-block-private-targets，默认 false：online 命令本可
 *                         直连内网制品服务器，代理默认拦私网会造成可用性回退）
 * @param proxyDenyHosts   代理目标显式禁配清单（键 codex.proxy-deny-hosts，逗号/空白
 *                         分隔，后缀匹配大小写不敏感；默认空）
 */
public record CodexSandboxOptions(
        Path codexHome,
        String accountPrefix,
        String networkPolicy,
        List<Integer> proxyPorts,
        boolean allowLocalBinding,
        String javaHome,
        boolean runnerPoolEnabled,
        int runnerPoolMaxSize,
        long runnerPoolIdleTtlMs,
        long logonSessionAuditIntervalMs,
        int logonSessionAuditThreshold,
        boolean proxyEnabled,
        int proxyPreferredPort,
        boolean proxyAllowLoopbackTargets,
        boolean proxyBlockPrivateTargets,
        List<String> proxyDenyHosts) {

    /** network-policy 合法值。 */
    public static final String POLICY_AUTO = "auto";
    public static final String POLICY_OFFLINE = "offline";
    public static final String POLICY_ONLINE = "online";

    /** runner 池化默认值（与 RunnerPools.Settings 对齐；在此重复声明避免 options→pool 反向依赖）。 */
    public static final int DEFAULT_RUNNER_POOL_MAX_SIZE = 2;
    public static final long DEFAULT_RUNNER_POOL_IDLE_TTL_MS = 10 * 60 * 1000;
    /** 登录会话审计默认周期（30 分钟）。 */
    public static final long DEFAULT_LOGON_SESSION_AUDIT_INTERVAL_MS = 30 * 60 * 1000;
    /** 登录会话总数默认告警阈值。 */
    public static final int DEFAULT_LOGON_SESSION_AUDIT_THRESHOLD = 64;
    /** loopback 代理默认首选端口（对齐 codex http 代理默认 3128）。 */
    public static final int DEFAULT_PROXY_PREFERRED_PORT = 3128;

    /** 兼容构造（池化/审计项取默认值）：既有调用点与测试不受新增配置项影响。 */
    public CodexSandboxOptions(Path codexHome, String accountPrefix, String networkPolicy,
            List<Integer> proxyPorts, boolean allowLocalBinding, String javaHome) {
        this(codexHome, accountPrefix, networkPolicy, proxyPorts, allowLocalBinding, javaHome,
                true, DEFAULT_RUNNER_POOL_MAX_SIZE, DEFAULT_RUNNER_POOL_IDLE_TTL_MS,
                DEFAULT_LOGON_SESSION_AUDIT_INTERVAL_MS, DEFAULT_LOGON_SESSION_AUDIT_THRESHOLD);
    }

    /** 兼容构造（代理项取默认值：开/3128/默认策略/无禁配）。 */
    public CodexSandboxOptions(Path codexHome, String accountPrefix, String networkPolicy,
            List<Integer> proxyPorts, boolean allowLocalBinding, String javaHome,
            boolean runnerPoolEnabled, int runnerPoolMaxSize, long runnerPoolIdleTtlMs,
            long logonSessionAuditIntervalMs, int logonSessionAuditThreshold) {
        this(codexHome, accountPrefix, networkPolicy, proxyPorts, allowLocalBinding, javaHome,
                runnerPoolEnabled, runnerPoolMaxSize, runnerPoolIdleTtlMs,
                logonSessionAuditIntervalMs, logonSessionAuditThreshold,
                true, DEFAULT_PROXY_PREFERRED_PORT, false, false, List.of());
    }

    public CodexSandboxOptions {
        accountPrefix = accountPrefix == null || accountPrefix.isBlank()
                ? SandboxAccounts.DEFAULT_PREFIX : accountPrefix;
        networkPolicy = normalizePolicy(networkPolicy);
        proxyPorts = proxyPorts == null ? List.of() : List.copyOf(proxyPorts);
        javaHome = javaHome == null ? "" : javaHome;
        runnerPoolMaxSize = Math.max(1, runnerPoolMaxSize);
        runnerPoolIdleTtlMs = Math.max(1, runnerPoolIdleTtlMs);
        logonSessionAuditThreshold = Math.max(1, logonSessionAuditThreshold);
        // 代理端口纪律：只认 1..65535；0/非法值归一为默认——绝不监听随机端口
        //（随机端口无法预声明、env 注入地址不稳定，见参数 Javadoc）
        if (proxyPreferredPort < 1 || proxyPreferredPort > 0xFFFF) {
            proxyPreferredPort = DEFAULT_PROXY_PREFERRED_PORT;
        }
        proxyDenyHosts = HostPolicy.normalizeDenyHosts(proxyDenyHosts);
    }

    /** 从插件配置 + worker 配置解析（activate 一次性调用）。 */
    public static CodexSandboxOptions from(PluginConfig cfg, WorkerConfig props) {
        String home = cfg == null ? "" : cfg.getString("codex.home", "");
        Path codexHome = home != null && !home.isBlank() ? Path.of(home)
                : props.resolveSandboxPersistentRoot().resolve("codex");
        return new CodexSandboxOptions(
                codexHome,
                cfg == null ? null : cfg.getString("codex.account-prefix", ""),
                cfg == null ? null : cfg.getString("codex.network-policy", POLICY_AUTO),
                parsePorts(cfg == null ? "" : cfg.getString("codex.proxy-ports", "")),
                cfg != null && cfg.getBoolean("codex.allow-local-binding", false),
                cfg == null ? "" : cfg.getString("codex.java-home", ""),
                cfg == null || cfg.getBoolean("codex.runner-pool-enabled", true),
                cfg == null ? DEFAULT_RUNNER_POOL_MAX_SIZE
                        : cfg.getInt("codex.runner-pool-max-size", DEFAULT_RUNNER_POOL_MAX_SIZE),
                cfg == null ? DEFAULT_RUNNER_POOL_IDLE_TTL_MS
                        : cfg.get("codex.runner-pool-idle-ttl-ms", Long.class,
                                DEFAULT_RUNNER_POOL_IDLE_TTL_MS),
                cfg == null ? DEFAULT_LOGON_SESSION_AUDIT_INTERVAL_MS
                        : cfg.get("codex.logon-session-audit-interval-ms", Long.class,
                                DEFAULT_LOGON_SESSION_AUDIT_INTERVAL_MS),
                cfg == null ? DEFAULT_LOGON_SESSION_AUDIT_THRESHOLD
                        : cfg.getInt("codex.logon-session-audit-threshold",
                                DEFAULT_LOGON_SESSION_AUDIT_THRESHOLD),
                cfg == null || cfg.getBoolean("codex.proxy-enabled", true),
                cfg == null ? DEFAULT_PROXY_PREFERRED_PORT
                        : cfg.getInt("codex.proxy-port", DEFAULT_PROXY_PREFERRED_PORT),
                cfg != null && cfg.getBoolean("codex.proxy-allow-loopback-targets", false),
                cfg != null && cfg.getBoolean("codex.proxy-block-private-targets", false),
                parseDenyHosts(cfg == null ? "" : cfg.getString("codex.proxy-deny-hosts", "")));
    }

    /** 网络身份决策：offline/online 强制，auto 随 worker 全局 networkDenied。 */
    public NetworkIdentity networkIdentity(boolean networkDenied) {
        return switch (networkPolicy) {
            case POLICY_OFFLINE -> NetworkIdentity.OFFLINE;
            case POLICY_ONLINE -> NetworkIdentity.ONLINE;
            default -> NetworkIdentity.from(networkDenied);
        };
    }

    /** 空值/非法值归一为 auto。 */
    public static String normalizePolicy(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return POLICY_OFFLINE.equals(s) || POLICY_ONLINE.equals(s) ? s : POLICY_AUTO;
    }

    /** "8080,3128" → [8080, 3128]（容忍空白/尾逗号；非法段跳过）。 */
    public static List<Integer> parsePorts(String raw) {
        List<Integer> ports = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return ports;
        }
        for (String part : raw.split(",")) {
            String trimmed = part.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                int port = Integer.parseInt(trimmed);
                if (port >= 1 && port <= 65535) {
                    ports.add(port);
                }
            } catch (NumberFormatException ignored) {
                // 非法段跳过（fail-open 于配置解析，setup 侧仍按端口白名单生成规则）
            }
        }
        return ports;
    }

    /** "evil.com, .corp.internal" → [evil.com, .corp.internal]（逗号/空白分隔,去空去重,保留大小写形态由策略侧归一）。 */
    static List<String> parseDenyHosts(String raw) {
        List<String> hosts = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return hosts;
        }
        for (String part : raw.split("[,\\s]+")) {
            String trimmed = part.strip();
            if (!trimmed.isEmpty() && !hosts.contains(trimmed)) {
                hosts.add(trimmed);
            }
        }
        return hosts;
    }
}
