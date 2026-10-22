package dev.everyagent.hub;

import dev.everyagent.contract.frame.Frames;
import dev.everyagent.contract.ids.Ids;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * pub 限流显式关闭:hub.limits.pub-per-sec=0(任一为 0 即关闭)→ 不建令牌桶,任意速率放行。
 * 刻意保留 pub-burst=5(非 0):若实现误把 0 当字面速率(容量 5、回填 0),第 6 帧起将恒拒——
 * 20 帧全量送达即同时证明「0 = 关闭」而非「速率 0」。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HubPubRateLimitDisabledTest {

    private static final String HUB_KEY_RAW = "hub-secret-pub-limit-off";

    @DynamicPropertySource
    static void hubProps(DynamicPropertyRegistry registry) {
        registry.add("hub.hub-key", () -> HUB_KEY_RAW);
        registry.add("hub.limits.pub-per-sec", () -> "0");
        registry.add("hub.limits.pub-burst", () -> "5");
    }

    @LocalServerPort
    int port;

    private static final String KEY_A = "sk-owner-a";
    private final String ownerA = Ids.ownerKey(KEY_A);

    private String ch(String suffix) {
        return "u." + ownerA + "." + suffix;
    }

    @Test
    void zeroDisablesPubRateLimit() {
        try (WsTestClient sender = connect(); WsTestClient listener = connect()) {
            hello(sender, "fe-1");
            hello(listener, "fe-2");
            String channel = ch("task.t20.stream");
            // 通路让步:sub 异步路由,pub 立即到达会被合法丢弃(hub 无 sub 回执)。
            listener.send("{\"type\":\"sub\",\"channel\":\"" + channel + "\"}");
            sleep(200);

            for (int i = 0; i < 20; i++) {
                sender.send("{\"type\":\"pub\",\"mid\":\"m-" + i + "\",\"channel\":\"" + channel
                        + "\",\"event\":\"delta\",\"ts\":" + i + ",\"payload\":{\"i\":" + i + "}}");
            }
            for (int i = 0; i < 20; i++) {
                String got = listener.awaitEvent("delta");
                assertTrue(got.contains("\"i\":" + i), "关闭限流后帧应按序全量送达: " + got);
            }
            assertNoFrame(sender, "RATE_LIMITED", 500);
        }
    }

    private WsTestClient connect() {
        return WsTestClient.connect(URI.create("ws://localhost:" + port + "/ws"));
    }

    private void hello(WsTestClient c, String clientId) {
        c.send("{\"type\":\"hello\",\"ver\":" + Frames.PROTOCOL_VERSION + ",\"role\":\"frontend\""
                + ",\"apiKey\":\"" + KEY_A + "\",\"clientId\":\"" + clientId
                + "\",\"hubKey\":\"" + HUB_KEY_RAW + "\"}");
        c.await(t -> t.contains("\"type\":\"welcome\""), "welcome");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted");
        }
    }

    private static void assertNoFrame(WsTestClient c, String needle, long ms) {
        long deadline = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < deadline) {
            var f = c.collector.frames.poll();
            if (f != null && f.text() != null && f.text().contains(needle)) {
                throw new AssertionError("不应收到 " + needle + ": " + f.text());
            }
            sleep(20);
        }
    }
}
