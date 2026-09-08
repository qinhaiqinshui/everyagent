package dev.everyagent.hub.util;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 单 IP hello 限速(防 key 枚举)。桶实例懒创建;数量以来源 IP 为界,量级可控。
 */
public final class HelloRateLimiter {

    private final ConcurrentHashMap<String, TokenBucket> byIp = new ConcurrentHashMap<>();
    private final double ratePerMinute;

    public HelloRateLimiter(double ratePerMinute) {
        this.ratePerMinute = ratePerMinute;
    }

    public boolean tryAcquire(String ip) {
        TokenBucket bucket = byIp.computeIfAbsent(ip, k -> new TokenBucket(ratePerMinute, ratePerMinute / 60.0));
        return bucket.tryConsume(1);
    }
}
