package dev.everyagent.plugin.sandbox.codex;

import dev.everyagent.plugin.api.PluginConfig;
import dev.everyagent.plugin.api.config.WorkerConfig;
import dev.everyagent.plugin.sandbox.codex.accounts.SandboxAccounts;
import dev.everyagent.plugin.sandbox.codex.accounts.SandboxAccounts.NetworkIdentity;

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
 * @param accountPrefix    账户/组前缀（默认 EveryAgentCodex，键 codex.account-prefix）
 * @param networkPolicy    auto（随 worker networkDenied）/ offline / online 强制
 *                         （键 codex.network-policy）
 * @param proxyPorts       offline 账户放行的环回 TCP 代理端口（键 codex.proxy-ports，
 *                         逗号分隔）
 * @param allowLocalBinding true=移除环回 block 规则（键 codex.allow-local-binding）
 * @param javaHome         runner 启动 java.exe 根；空 = 当前 JVM（键 codex.java-home）
 */
public record CodexSandboxOptions(
        Path codexHome,
        String accountPrefix,
        String networkPolicy,
        List<Integer> proxyPorts,
        boolean allowLocalBinding,
        String javaHome) {

    /** network-policy 合法值。 */
    public static final String POLICY_AUTO = "auto";
    public static final String POLICY_OFFLINE = "offline";
    public static final String POLICY_ONLINE = "online";

    public CodexSandboxOptions {
        accountPrefix = accountPrefix == null || accountPrefix.isBlank()
                ? SandboxAccounts.DEFAULT_PREFIX : accountPrefix;
        networkPolicy = normalizePolicy(networkPolicy);
        proxyPorts = proxyPorts == null ? List.of() : List.copyOf(proxyPorts);
        javaHome = javaHome == null ? "" : javaHome;
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
                cfg == null ? "" : cfg.getString("codex.java-home", ""));
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
}
