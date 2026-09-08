package dev.everyagent.hub.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenBucketTest {

    @Test
    void burstThenRefill() throws InterruptedException {
        TokenBucket bucket = new TokenBucket(3, 100); // 容量 3,每秒回 100
        assertTrue(bucket.tryConsume(1));
        assertTrue(bucket.tryConsume(1));
        assertTrue(bucket.tryConsume(1));
        assertFalse(bucket.tryConsume(1), "burst 耗尽后应拒绝");
        Thread.sleep(30); // 30ms ≈ 回 3 个
        assertTrue(bucket.tryConsume(1), "补充后应放行");
    }
}
