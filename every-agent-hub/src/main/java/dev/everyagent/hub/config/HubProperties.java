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

    /** 每连接 pub 令牌桶:稳态速率。 */
    private int pubRatePerSecond = 100;

    /** 每连接 pub 令牌桶:突发容量。 */
    private int pubBurst = 100;

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
     * 校验失败抛异常,应用拒绝启动;派生值供 hello 比对/管理目录频道使用。
     */
    @PostConstruct
    void validate() {
        if (hubKey == null || hubKey.isBlank()) {
            throw new IllegalStateException("hub.hub-key 未配置:hub 只接受持正确 hub key 的连接,"
                    + "配置项或环境变量 HUB_KEY 直接填原始 hub key 即可(启动时程序自行计算 sha256)");
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

    public int getPubRatePerSecond() {
        return pubRatePerSecond;
    }

    public void setPubRatePerSecond(int pubRatePerSecond) {
        this.pubRatePerSecond = pubRatePerSecond;
    }

    public int getPubBurst() {
        return pubBurst;
    }

    public void setPubBurst(int pubBurst) {
        this.pubBurst = pubBurst;
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
}
