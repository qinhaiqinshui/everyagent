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
 * pub 限流生效路径(安全整改 L-6):紧配置 hub.limits.pub-per-sec=1 / pub-burst=3 下验证
 * ① burst 内帧零误伤(令牌桶初始容量 = pub-burst);② 超速 pub 收 RATE_LIMITED error(不断连)。
 * 默认值(1000/2000)不误伤见 {@link HubIntegrationTest#defaultPubLimitsDoNotThrottleNormalTraffic},
 * 0 关闭见 {@link HubPubRateLimitDisabledTest}。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HubPubRateLimitTest {

    private static final String HUB_KEY_RAW = "hub-secret-pub-limit";

    @DynamicPropertySource
    static void hubProps(DynamicPropertyRegistry registry) {
        registry.add("hub.hub-key", () -> HUB_KEY_RAW);
        registry.add("hub.limits.pub-per-sec", () -> "1");
        registry.add("hub.limits.pub-burst", () -> "3");
    }

    @LocalServerPort
    int port;

    private static final String KEY_A = "sk-owner-a";
    private final String ownerA = Ids.ownerKey(KEY_A);

    private String ch(String suffix) {
        return "u." + ownerA + "." + suffix;
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

    private void pub(WsTestClient c, String channel, int i) {
        c.send("{\"type\":\"pub\",\"mid\":\"m-" + i + "\",\"channel\":\"" + channel
                + "\",\"event\":\"delta\",\"ts\":" + i + ",\"payload\":{\"i\":" + i + "}}");
    }

    /** ① burst(=3)内的帧全部送达、发布者不收 RATE_LIMITED——正常突发不误伤。 */
    @Test
    void burstWithinLimitIsNotThrottled() {
        try (WsTestClient sender = connect(); WsTestClient listener = connect()) {
            hello(sender, "fe-1");
            hello(listener, "fe-2");
            String channel = ch("task.t3.stream");
            sub(listener, channel);

            for (int i = 0; i < 3; i++) {
                pub(sender, channel, i);
            }
            for (int i = 0; i < 3; i++) {
                String got = listener.awaitEvent("delta");
                assertTrue(got.contains("\"i\":" + i), "burst 内帧应按序全量送达: " + got);
            }
            assertNoFrame(sender, "RATE_LIMITED", 500);
        }
    }

    /** ② 突发耗尽后继续超速 → 发布者收 RATE_LIMITED error(只拒本帧,连接保持)。 */
    @Test
    void pubBeyondBurstIsRateLimited() {
        try (WsTestClient sender = connect(); WsTestClient listener = connect()) {
            hello(sender, "fe-1");
            hello(listener, "fe-2");
            String channel = ch("task.t10.stream");
            sub(listener, channel);

            for (int i = 0; i < 10; i++) {
                pub(sender, channel, i);
            }
            String err = sender.await(t -> t.contains("RATE_LIMITED"), "RATE_LIMITED error");
            assertTrue(err.contains("\"type\":\"error\""), "应为 error 帧: " + err);
            // 拒帧只回 error、不断连:后续仍可低速率继续 pub(1/s 回填,等 1.2s 后应放行 1 帧)
            sleep(1200);
            pub(sender, channel, 99);
            listener.await(t -> t.contains("\"i\":99"), "回填令牌后应恢复放行");
        }
    }

    private void sub(WsTestClient c, String channel) {
        c.send("{\"type\":\"sub\",\"channel\":\"" + channel + "\"}");
        // 通路让步:sub 异步路由,发布端(pub 走另一条连接)的帧先到会被合法丢弃,跨连接
        // sub→pub 必须消除竞态(hub 无 sub 回执,详见 HubIntegrationTest#subSettled)。
        sleep(200);
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

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted");
        }
    }
}
