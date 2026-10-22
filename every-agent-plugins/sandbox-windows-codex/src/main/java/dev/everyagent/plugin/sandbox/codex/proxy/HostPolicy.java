package dev.everyagent.plugin.sandbox.codex.proxy;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 代理目标策略（host 级 allow/deny，对齐 codex network-proxy connect_policy +
 * TargetCheckedTcpConnector 的「full 模式默认放行、非公网目标默认阻断」精神，按本项目
 * 「代理仅服务 online 身份、online 本就有全网直连能力」的事实做了取舍得见类 Javadoc）。
 *
 * <p>判定顺序（先命中先返回，全部纯逻辑可单测）：
 * <ol>
 *   <li><b>显式禁配后缀匹配</b>（{@code proxy-deny-hosts}）：host 等于条目、或以
 *       {@code .<条目>} 结尾、或（条目以 {@code .} 开头时）以条目结尾——先于 DNS 解析，
 *       覆盖「解析失败/被劫持也能拦」的确定性场景；</li>
 *   <li><b>DNS 解析</b>：失败按阻断处理（fail-closed，reason=dns-failed），绝不带着
 *       未验证的主机名连出去；</li>
 *   <li><b>地址分类</b>（对<b>每个</b>解析地址逐一检查，任一命中即整单阻断——防
 *       DNS rebinding 把公网名解析到内网地址绕过）：any-local（0.0.0.0）、multicast、
 *       link-local（169.254/16、fe80::/10，含云元数据端点）、loopback（127/8、::1，
 *       除非 {@code allowLoopbackTargets}）；可选私网段（RFC1918 + IPv6 ULA，开关
 *       {@code blockPrivateTargets}，默认不阻断）。</li>
 * </ol>
 *
 * <p><b>与 codex 默认的差异（有意）</b>：codex 的连接器默认阻断一切非公网目标（私网
 * 也在内）——那服务于「沙箱唯一出口是代理」的 offline 模型。本项目代理仅服务 online
 * 命令，而 online 账户本就无防火墙限制、可直连私网（内网制品服务器等是合法场景）；
 * 若代理默认拦私网，遵循代理 env 的客户端会把私网请求送进代理吃 403，属可用性回退。
 * 故默认仅拦 any-local/multicast/link-local/loopback（SSRF 卫生：NO_PROXY 已把
 * localhost 归直连，代理侧再拦 loopback 消除「经代理打回宿主本机服务」的绕行面），
 * 私网拦截做成开关。回环目标同理默认拦（合法 localhost 流量经 NO_PROXY 直连不受影响），
 * 需要经代理访问回环目标的部署可开 {@code allowLoopbackTargets}。
 */
public final class HostPolicy {

    /** 判定结果：allowed=false 时 reason 为单行可读原因（进 403 的 x-proxy-error 头）。 */
    public record Decision(boolean allowed, String reason, List<InetSocketAddress> targets) {

        static Decision deny(String reason) {
            return new Decision(false, reason, List.of());
        }
    }

    private final boolean allowLoopbackTargets;
    private final boolean blockPrivateTargets;
    private final List<String> denyHosts;

    private HostPolicy(boolean allowLoopbackTargets, boolean blockPrivateTargets,
            List<String> denyHosts) {
        this.allowLoopbackTargets = allowLoopbackTargets;
        this.blockPrivateTargets = blockPrivateTargets;
        this.denyHosts = List.copyOf(denyHosts);
    }

    /**
     * @param allowLoopbackTargets true=放行 127/8、::1 目标（默认 false，SSRF 卫生）
     * @param blockPrivateTargets  true=阻断 RFC1918 私网与 IPv6 ULA 目标（默认 false，
     *                             避免 online 命令访问内网制品服务器的可用性回退）
     * @param denyHosts            显式禁配主机条目（后缀匹配，大小写不敏感，可空）
     */
    public static HostPolicy of(boolean allowLoopbackTargets, boolean blockPrivateTargets,
            List<String> denyHosts) {
        return new HostPolicy(allowLoopbackTargets, blockPrivateTargets,
                normalizeDenyHosts(denyHosts));
    }

    /** 全放行策略（测试与显式关策略用）。 */
    public static HostPolicy allowAll() {
        return new HostPolicy(true, true, List.of());
    }

    /** 条目归一：去空白、小写、去重、剔空（容错解析，非法段跳过）。 */
    public static List<String> normalizeDenyHosts(List<String> raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (String entry : raw) {
            if (entry == null) {
                continue;
            }
            String normalized = entry.strip().toLowerCase(Locale.ROOT);
            if (!normalized.isEmpty() && !out.contains(normalized)) {
                out.add(normalized);
            }
        }
        return out;
    }

    /** 显式禁配后缀匹配（独立纯函数，单测覆盖）。 */
    static boolean deniedByHostList(String host, List<String> denyHosts) {
        if (host == null || denyHosts.isEmpty()) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        for (String entry : denyHosts) {
            if (h.equals(entry) || h.endsWith("." + entry)
                    || (entry.startsWith(".") && h.endsWith(entry))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析并判定目标。解析出的地址随结果带回（CONNECT/转发直接连它，避免二次解析、
     * 且保证「检查的地址 = 连接的地址」——两次独立解析在 DNS 轮询下可能不一致）。
     */
    public Decision resolve(String host, int port) {
        if (host == null || host.isBlank()) {
            return Decision.deny("empty-target");
        }
        if (deniedByHostList(host, denyHosts)) {
            return Decision.deny("host-denied:" + host);
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            return Decision.deny("dns-failed:" + host);
        }
        if (addresses.length == 0) {
            return Decision.deny("dns-empty:" + host);
        }
        for (InetAddress address : addresses) {
            String violation = classify(address);
            if (violation != null) {
                return Decision.deny(violation + ":" + address.getHostAddress());
            }
        }
        List<InetSocketAddress> targets = new ArrayList<>(addresses.length);
        for (InetAddress address : addresses) {
            targets.add(new InetSocketAddress(address, port));
        }
        return new Decision(true, "ok", targets);
    }

    /** 地址分类：返回 null=放行，否则返回违规类别名（进 x-proxy-error）。 */
    private String classify(InetAddress address) {
        if (address.isAnyLocalAddress()) {
            return "target-any-local";
        }
        if (address.isMulticastAddress()) {
            return "target-multicast";
        }
        if (address.isLinkLocalAddress()) {
            return "target-link-local";
        }
        if (address.isLoopbackAddress() && !allowLoopbackTargets) {
            return "target-loopback";
        }
        if (blockPrivateTargets && isPrivateRange(address)) {
            return "target-private";
        }
        return null;
    }

    /** RFC1918（site-local）+ IPv6 ULA（fc00::/7）。 */
    static boolean isPrivateRange(InetAddress address) {
        if (address instanceof Inet6Address v6) {
            byte first = v6.getAddress()[0];
            return (first & 0xFE) == 0xFC;
        }
        return address.isSiteLocalAddress();
    }
}
