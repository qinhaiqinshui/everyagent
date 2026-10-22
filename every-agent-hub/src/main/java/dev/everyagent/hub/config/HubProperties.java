package dev.everyagent.hub.config;

import dev.everyagent.contract.ids.Ids;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * hub 公网加固参数(架构 §4.3)。
 */
@ConfigurationProperties(prefix = "hub")
public class HubProperties {

    /**
     * 连接 hub 的凭证(前端与 worker 都要在 hello.hubKey 携带原始 hub key)。本配置直接填
     * 原始 hub key(必填);hub 启动时在内存中自行计算 sha256 小写 hex(见 {@link #getHubKeySha()})
     * 用于 hello 比对与管理目录频道,部署侧无需手填哈希,原始 key 不进日志。
     */
    private String hubKey = "";

    /** sha256(hub-key 原文) 的小写 hex;validate() 启动派生,读取方一律用本值,不碰原文。 */
    private String hubKeySha = "";

    /** 单帧上限,默认 16 MiB。应用层 HubConnection 与传输层 Reactor Netty 升级策略共用此值。 */
    private int maxFrameBytes = 16_777_216;

    /** 限流配额(hub.limits.*):pub 令牌桶等,见 {@link Limits}。 */
    private final Limits limits = new Limits();

    /** 每连接出口队列上限,溢出断开(慢消费者保护)。 */
    private int outboundQueueLimit = 1000;

    /** WS 协议层 ping 间隔(毫秒)。 */
    private long pingIntervalMs = 15_000;

    /** 入站静默判死阈值(毫秒);0 = 关闭。 */
    private long staleReadMs = 0;

    /** 单 IP hello 限速(次/分钟)。 */
    private int helloRatePerMinute = 60;

    /**
     * 启动 fail-fast + 派生:hub-key 必填(直接填原始密钥,程序自算 sha256,无需手填哈希)。
     * 打包默认值为空(fail-closed,无任何内置默认密钥),未显式配置即拒绝启动。
     * 校验失败抛异常,应用拒绝启动;派生值供 hello 比对/管理目录频道使用。
     */
    @PostConstruct
    void validate() {
        if (hubKey == null || hubKey.isBlank()) {
            throw new IllegalStateException("hub.hub-key 未配置(fail-closed:已移除打包默认密钥,空值不再放行):"
                    + "请显式配置原始 hub key——①环境变量 HUB_KEY=xxx;②配置项 hub.hub-key"
                    + "(jar 内 application.yml 或 ~/.everyagent/application-hub.yaml,支持 ${EVERYAGENT_HOME} 重定位);"
                    + "③启动参数 --hub.hub-key=xxx。填原文即可(启动时程序自行计算 sha256)");
        }
        if (limits.pubPerSec < 0 || limits.pubBurst < 0) {
            throw new IllegalStateException("hub.limits.pub-per-sec / hub.limits.pub-burst 不允许负值"
                    + "(0 = 显式关闭对应限流):pub-per-sec=" + limits.pubPerSec
                    + ", pub-burst=" + limits.pubBurst);
        }
        this.hubKeySha = Ids.ownerKey(hubKey.trim());
    }

    public String getHubKey() {
        return hubKey;
    }

    /** sha256(hub-key 原文) 小写 hex(启动派生);hello 比对与管理目录频道的唯一依据。 */
    public String getHubKeySha() {
        return hubKeySha;
    }

    public void setHubKey(String hubKey) {
        this.hubKey = hubKey;
    }

    public int getMaxFrameBytes() {
        return maxFrameBytes;
    }

    public void setMaxFrameBytes(int maxFrameBytes) {
        this.maxFrameBytes = maxFrameBytes;
    }

    /** 限流配额(hub.limits.*,见 {@link Limits});getter 供 yml 绑定嵌套属性。 */
    public Limits getLimits() {
        return limits;
    }

    public int getOutboundQueueLimit() {
        return outboundQueueLimit;
    }

    public void setOutboundQueueLimit(int outboundQueueLimit) {
        this.outboundQueueLimit = outboundQueueLimit;
    }

    public long getPingIntervalMs() {
        return pingIntervalMs;
    }

    public void setPingIntervalMs(long pingIntervalMs) {
        this.pingIntervalMs = pingIntervalMs;
    }

    public long getStaleReadMs() {
        return staleReadMs;
    }

    public void setStaleReadMs(long staleReadMs) {
        this.staleReadMs = staleReadMs;
    }

    public int getHelloRatePerMinute() {
        return helloRatePerMinute;
    }

    public void setHelloRatePerMinute(int helloRatePerMinute) {
        this.helloRatePerMinute = helloRatePerMinute;
    }

    /**
     * 限流配额(hub.limits.*)。默认值按「默认启用、正常流量零误伤」推算(安全整改 L-6:
     * pub 限流曾被注释停用,认证连接可无限速率扇出 16MiB 帧 → CPU/带宽放大):
     * <ul>
     *   <li>{@code pubPerSec=1000}:快模型流式输出 ~100 帧/s × 并发任务 ≤4 ≈ 400 帧/s 峰值,×2 余量;</li>
     *   <li>{@code pubBurst=2000}:≥ worker DataPusher 首挂回扫 FIRST_SWEEP_MAX=2000,
     *       覆盖 fs.read 等瞬时帧脉冲叠加在流式输出之上的场景。</li>
     * </ul>
     * 两值任一为 0 = 显式关闭 pub 限流(仅建议可信环境临时排障);公网部署建议收紧
     * (如 200/500),与 hello-rate-per-minute、stale-read-ms 配合使用。
     */
    public static class Limits {

        /** 每连接 pub 令牌桶:稳态速率(帧/s),默认 1000;0 = 关闭。 */
        private int pubPerSec = 1000;

        /** 每连接 pub 令牌桶:突发容量(帧),默认 2000;0 = 关闭。 */
        private int pubBurst = 2000;

        public int getPubPerSec() {
            return pubPerSec;
        }

        public void setPubPerSec(int pubPerSec) {
            this.pubPerSec = pubPerSec;
        }

        public int getPubBurst() {
            return pubBurst;
        }

        public void setPubBurst(int pubBurst) {
            this.pubBurst = pubBurst;
        }
    }
}
