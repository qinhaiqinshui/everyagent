package dev.everyagent.plugin.sandbox.codex.proxy;

import dev.everyagent.plugin.sandbox.codex.CodexSandboxOptions;
import dev.everyagent.plugin.sandbox.codex.accounts.SandboxAccounts.NetworkIdentity;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 宿主侧 loopback 代理的进程级注册表（键 = codexHome；形态对齐 {@code RunnerPools}：
 * executor 随工具创建而新建，代理必须跨 executor 存活、绑定 worker 进程生命周期）。
 *
 * <p><b>启动策略（「offline 策略不启动」）</b>：插件 activate 时若网络身份判定为
 * {@link NetworkIdentity#ONLINE}（policy=online 强制，或 auto——activate 时
 * networkDenied 恒 false，即 auto 兜底为 online）则启动；policy=offline 永不启动。
 * 执行侧 {@link #endpointIfRunning(Path)} 是<b>纯查询</b>（零副作用：不在命令路径上
 * 绑端口），executor 仅在「online 身份 ∧ 代理在跑」时注入 env——offline 命令天然拿不到
 * 代理地址，代理 env 与 offline 断网语义互不渗透。auto 策略下网络身份在任务间翻转时，
 * 代理保持运行（仅占一个回环监听；offline 账户的环回 TCP 被防火墙全禁、也无人注入
 * 代理 env，无泄漏面），deactivate 全量关闭。
 *
 * <p><b>防火墙协同决策（有意不接线，汇报与 docs 有完整理由）</b>：不把代理端口加入
 * setup 侧 {@code codex.proxy-ports} 放行清单/marker。该清单只约束 <b>offline</b> 账户
 * 的环回 TCP（补集放行）；而代理仅服务 online 账户——online 账户本就无任何防火墙/
 * WFP 规则，直连 {@code 127.0.0.1:<port>} 畅通，无需放行。反向接线（把代理端口加进
 * offline 放行清单）反而会击穿 offline 的密闭性：本项目没有 codex 的逐连接归因
 * （restricting SID + GetExtendedTcpTable），无法区分 offline/online 调用方，offline
 * 进程即可经代理获得全网出站——密闭性 &gt; codex 形态对齐。codex 的
 * 「listener ⊆ proxy_ports + marker 对账」服务于其「offline 唯一出口是代理」的模型，
 * 本项目未采用该模型（方案决策：代理仅 online）。端口确定性由「只绑 3128..3159 固定段、
 * 绝不随机」保证，不存在需要 marker 对账的漂移面。
 */
public final class ProxyRegistry {

    /** 运行中的代理端点（env 注入用）。 */
    public record Endpoint(String host, int port) {

        public String proxyUrl() {
            return "http://" + host + ":" + port;
        }
    }

    /** codexHome → 代理条目（运行中代理 / 测试注入的假端点）。 */
    private static final class Entry {
        /** 真实代理（ensureStarted 置位）。 */
        volatile LoopbackHttpProxy proxy;
        /** 测试注入的假端点（不监听；仅 executor 接线断言用）。 */
        volatile Endpoint fake;

        boolean running() {
            return fake != null || (proxy != null && proxy.isRunning());
        }

        int port() {
            Endpoint f = fake;
            return f != null ? f.port() : proxy.port();
        }
    }

    private static final System.Logger LOG = System.getLogger(ProxyRegistry.class.getName());

    /** 进程级注册表（key 用 canonical 字符串,容忍路径形态差异）。 */
    private static final ConcurrentHashMap<String, Entry> REGISTRY =
            new ConcurrentHashMap<>();
    /** 启动互斥（per-process 单飞：并发首启不重复绑端口）。 */
    private static final Object START_MUTEX = new Object();

    private ProxyRegistry() {
    }

    /**
     * 纯查询：该 codexHome 的代理是否在跑（executor 每命令一次,零副作用）。
     */
    public static Optional<Endpoint> endpointIfRunning(Path codexHome) {
        Entry entry = REGISTRY.get(keyOf(codexHome));
        if (entry == null || !entry.running()) {
            return Optional.empty();
        }
        return Optional.of(new Endpoint("127.0.0.1", entry.port()));
    }

    /**
     * 确保代理启动（幂等；已运行直接返回端点）。未启用返回 empty；启动失败抛
     * IOException（调用方降级直连并 WARN——沙箱命令仍可执行,只是不注入代理 env）。
     */
    public static Optional<Endpoint> ensureStarted(CodexSandboxOptions options)
            throws IOException {
        if (options == null || !options.proxyEnabled()) {
            return Optional.empty();
        }
        Entry entry = REGISTRY.computeIfAbsent(keyOf(options.codexHome()), k -> new Entry());
        if (entry.running()) {
            return Optional.of(new Endpoint("127.0.0.1", entry.port()));
        }
        synchronized (START_MUTEX) {
            if (entry.running()) {
                return Optional.of(new Endpoint("127.0.0.1", entry.port()));
            }
            HostPolicy policy = HostPolicy.of(options.proxyAllowLoopbackTargets(),
                    options.proxyBlockPrivateTargets(), options.proxyDenyHosts());
            LoopbackHttpProxy proxy = LoopbackHttpProxy.start(
                    options.proxyPreferredPort(), policy);
            entry.proxy = proxy;
            entry.fake = null;
            LOG.log(System.Logger.Level.INFO,
                    "[codex-proxy] 注册表登记 codexHome={0} url={1}",
                    new Object[] { options.codexHome(), ProxyEnv.proxyUrl(proxy.port()) });
            return Optional.of(new Endpoint("127.0.0.1", proxy.port()));
        }
    }

    /** 关闭全部代理（插件 deactivate：随 worker 进程收口）。 */
    public static void stopAll() {
        for (var e : REGISTRY.entrySet()) {
            LoopbackHttpProxy proxy = e.getValue().proxy;
            if (proxy != null) {
                proxy.close();
            }
        }
        REGISTRY.clear();
    }

    // ---- 测试注入（跨包测试用；不影响生产路径） ----

    /** 测试注入：按端口登记假端点（executor 接线断言用,不真起监听）。 */
    public static void putForTesting(Path codexHome, int port) {
        Entry entry = new Entry();
        entry.fake = new Endpoint("127.0.0.1", port);
        REGISTRY.put(keyOf(codexHome), entry);
    }

    /** 测试清理：移除该键（含假端点）。 */
    public static void removeForTesting(Path codexHome) {
        REGISTRY.remove(keyOf(codexHome));
    }

    private static String keyOf(Path codexHome) {
        return codexHome == null ? "" : codexHome.toAbsolutePath().normalize().toString();
    }
}
