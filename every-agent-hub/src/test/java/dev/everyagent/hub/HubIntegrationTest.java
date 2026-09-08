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
 * M1 验收:两个标签页同 apiKey 互发;不同 apiKey 相互隔离;worker 角色上下线事件可见。
 * hub 去业务化后:同命名空间互信(任意角色可发任意自有频道),跨命名空间一律拒绝;
 * stream 频道订阅通知(join/leave → owner worker)见 streamChannelSubscriberNotifications。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HubIntegrationTest {

    /** 连接 hub 的原始凭证(测试专用);hub-key 已为必填,本类经 @DynamicPropertySource 注入。 */
    private static final String HUB_KEY_RAW = "hub-secret-integration";

    @DynamicPropertySource
    static void hubProps(DynamicPropertyRegistry registry) {
        registry.add("hub.hub-key", () -> HUB_KEY_RAW);
    }

    @LocalServerPort
    int port;

    private static final String KEY_A = "sk-owner-a";
    private static final String KEY_B = "sk-owner-b";
    private final String ownerA = Ids.ownerKey(KEY_A);
    private final String ownerB = Ids.ownerKey(KEY_B);

    /** hub 端不知道业务频道构造器——测试里手拼,顺带验证 hub 对频道语义零理解。 */
    private String ch(String suffix) {
        return "u." + ownerA + "." + suffix;
    }

    private WsTestClient connect() {
        return WsTestClient.connect(URI.create("ws://localhost:" + port + "/ws"));
    }

    private void hello(WsTestClient c, String role, String apiKey, String clientId) {
        c.send("{\"type\":\"hello\",\"ver\":" + Frames.PROTOCOL_VERSION + ",\"role\":\"" + role
                + "\",\"apiKey\":\"" + apiKey + "\",\"clientId\":\"" + clientId
                + "\",\"hubKey\":\"" + HUB_KEY_RAW + "\"}");
        c.await(t -> t.contains("\"type\":\"welcome\""), "welcome");
    }

    private void sub(WsTestClient c, String channel) {
        c.send("{\"type\":\"sub\",\"channel\":\"" + channel + "\"}");
    }

    private void pub(WsTestClient c, String channel, String event, String payloadJson) {
        c.send("{\"type\":\"pub\",\"mid\":\"m-1\",\"channel\":\"" + channel + "\",\"event\":\"" + event
                + "\",\"ts\":1,\"payload\":" + payloadJson + "}");
    }

    @Test
    void twoTabsSameKeyCanEcho() {
        try (WsTestClient tab1 = connect(); WsTestClient tab2 = connect()) {
            hello(tab1, "frontend", KEY_A, "fe-1");
            hello(tab2, "frontend", KEY_A, "fe-2");
            String echo = ch("room.echo"); // 前端自建频道(前端间协作)
            sub(tab1, echo);
            sub(tab2, echo);
            pub(tab1, echo, "delta", "{\"text\":\"你好\"}");

            String got1 = tab1.awaitEvent("delta");
            String got2 = tab2.awaitEvent("delta");
            assertTrue(got1.contains("\"text\":\"你好\""));
            assertTrue(got1.contains("\"from\":{\"clientId\":\"fe-1\",\"role\":\"frontend\",\"sessionId\":\""), got1);
            assertTrue(got2.contains("\"from\":{\"clientId\":\"fe-1\",\"role\":\"frontend\",\"sessionId\":\""), got2);
            // ext 原样转发 + must-ignore 未知字段
            tab1.send("{\"type\":\"pub\",\"channel\":\"" + echo + "\",\"event\":\"delta\",\"ts\":2,"
                    + "\"payload\":{\"text\":\"x\"},\"ext\":{\"traceparent\":\"00-abc-def-01\"},\"futureField\":123}");
            String got = tab2.await(t -> t.contains("traceparent"), "ext passthrough");
            assertTrue(got.contains("\"futureField\":123"), "未知字段必须保留");
        }
    }

    @Test
    void differentKeysAreIsolated() {
        try (WsTestClient a = connect(); WsTestClient b = connect()) {
            hello(a, "frontend", KEY_A, "fe-a");
            hello(b, "frontend", KEY_B, "fe-b");

            // B 无法订阅/发布 A 的频道
            String channelA = "u." + ownerA + ".room.a";
            sub(b, channelA);
            pub(b, channelA, "delta", "{\"text\":\"x\"}");
            b.await(t -> t.contains("ACL_DENIED"), "B 订阅 A 的频道应被拒");

            // A 在自己频道发布,B 收不到
            sub(a, channelA);
            pub(a, channelA, "delta", "{\"text\":\"secret\"}");
            a.awaitEvent("delta");
            // B 的队列里不应有 secret;排空 1s 验证
            long deadline = System.currentTimeMillis() + 1000;
            while (System.currentTimeMillis() < deadline) {
                var f = b.collector.frames.poll();
                if (f != null && f.text() != null && f.text().contains("secret")) {
                    throw new AssertionError("B 不应收到 A 的消息");
                }
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    @Test
    void workerPresenceVisible() {
        try (WsTestClient fe = connect(); WsTestClient worker = connect()) {
            hello(fe, "frontend", KEY_A, "fe-1");
            sub(fe, ch("workers"));
            hello(worker, "worker", KEY_A, "home-pc");
            fe.awaitEvent("worker.online");

            worker.send(""); // no-op 保证连接活跃
            worker.close(); // abort 触发断开
            fe.awaitEvent("worker.offline");
        }
    }

    @Test
    void pubBeforeHelloIsRejectedAndDisconnected() {
        try (WsTestClient c = connect()) {
            pub(c, ch("room"), "x", "{}");
            c.await(t -> t.contains("NOT_AUTHENTICATED"), "not authenticated error");
            c.awaitClosed();
        }
    }

    @Test
    void sameNamespaceIsTrustedForAnyChannel() {
        // hub 不理解业务:没有保留频道。前端可以 pub workers/cmd/tasks 等任意自有频道
        // (持有 apiKey 即命名空间全权,业务归属校验在 worker)——转发本身照常发生。
        try (WsTestClient fe = connect(); WsTestClient listener = connect()) {
            hello(fe, "frontend", KEY_A, "fe-1");
            hello(listener, "frontend", KEY_A, "fe-2");
            sub(listener, ch("task.t9.stream"));

            pub(fe, ch("workers"), "worker.online", "{\"workerId\":\"fake\"}");
            pub(fe, ch("task.t9.stream"), "delta", "{\"text\":\"hi\"}");
            String got = listener.awaitEvent("delta");
            assertTrue(got.contains("\"from\":{\"clientId\":\"fe-1\""), got);
        }
    }

    @Test
    void rpcRoundTripThroughCmdAndEvt() {
        try (WsTestClient fe = connect(); WsTestClient worker = connect()) {
            hello(fe, "frontend", KEY_A, "fe-1");
            hello(worker, "worker", KEY_A, "home-pc");
            sub(worker, ch("worker.home-pc.cmd"));
            sub(fe, ch("worker.home-pc.evt"));

            pub(fe, ch("worker.home-pc.cmd"), "rpc",
                    "{\"reqId\":\"r-1\",\"method\":\"sys.methods\",\"params\":{}}");

            String req = worker.awaitEvent("rpc");
            assertTrue(req.contains("\"reqId\":\"r-1\""), req);
            pub(worker, ch("worker.home-pc.evt"), "rpc.ok",
                    "{\"reqId\":\"r-1\",\"result\":{\"methods\":[\"sys.methods\"]}}");
            fe.await(t -> t.contains("rpc.ok") && t.contains("r-1"), "rpc.ok");
        }
    }

    /** 200 KiB 事件帧:超出 Reactor Netty 旧默认 64 KiB 传输上限(task.sync 大回放帧曾因此被拒收),应完整转发。 */
    @Test
    void largeFrameBeyond64KiBIsRelayed() {
        try (WsTestClient fe = connect(); WsTestClient listener = connect()) {
            hello(fe, "frontend", KEY_A, "fe-1");
            hello(listener, "frontend", KEY_A, "fe-2");
            sub(listener, ch("task.big.stream"));

            String big = "x".repeat(200 * 1024);
            pub(fe, ch("task.big.stream"), "message", "{\"text\":\"" + big + "\"}");
            String got = listener.awaitEvent("message");
            assertTrue(got.length() > 200 * 1024, "大帧应完整转发,实际长度=" + got.length());
        }
    }

    @Test
    void workerPreemptionClosesOldConnection() {
        try (WsTestClient fe = connect(); WsTestClient w1 = connect(); WsTestClient w2 = connect()) {
            hello(fe, "frontend", KEY_A, "fe-1");
            sub(fe, ch("workers"));
            hello(w1, "worker", KEY_A, "home-pc");
            fe.awaitEvent("worker.online");

            hello(w2, "worker", KEY_A, "home-pc");
            // offline(旧)→ online(新)依次出现
            String offline = fe.awaitEvent("worker.offline");
            assertTrue(offline.contains("home-pc"));
            fe.awaitEvent("worker.online");
            w1.awaitClosed();
        }
    }

    /**
     * stream 频道订阅通知(混合模型的 hub 侧):前端 sub/unsub u.<K>.task.<id>.stream →
     * 向该 owner 在线 worker 定向发 subscriber.join/leave(payload={sessionId,taskId});
     * 通知为无状态 fire-and-forget msg,不经频道广播(前端自身收不到)、worker 订阅不触发、
     * 跨 owner 隔离;未订阅过的 unsub 不发 leave;前端断连经 cleanup 补发 leave。
     */
    @Test
    void streamChannelSubscriberNotifications() {
        try (WsTestClient fe = connect(); WsTestClient worker = connect(); WsTestClient other = connect()) {
            // 内联 hello 以捕获 welcome.sessionId(join/leave payload 的期望值)
            fe.send("{\"type\":\"hello\",\"ver\":" + Frames.PROTOCOL_VERSION
                    + ",\"role\":\"frontend\",\"apiKey\":\"" + KEY_A + "\",\"clientId\":\"fe-1\""
                    + ",\"hubKey\":\"" + HUB_KEY_RAW + "\"}");
            String welcome = fe.await(t -> t.contains("\"type\":\"welcome\""), "welcome");
            String feSession = dev.everyagent.contract.json.Json.parse(welcome).path("sessionId").asString();
            assertTrue(feSession.length() > 0, "welcome 应带 sessionId: " + welcome);
            hello(worker, "worker", KEY_A, "home-pc");
            hello(other, "worker", KEY_B, "other-pc");

            // 前端订阅 stream 频道 → owner 在线 worker 收 subscriber.join
            sub(fe, ch("task.t77.stream"));
            String join = worker.awaitEvent("subscriber.join");
            assertTrue(join.contains("\"channel\":\"" + ch("task.t77.stream") + "\""), join);
            assertTrue(join.contains("\"taskId\":\"t77\""), join);
            assertTrue(join.contains("\"sessionId\":\"" + feSession + "\""), join);
            assertTrue(join.contains("\"from\":{\"clientId\":\"hub\",\"role\":\"hub\"}"), join);

            // 通知只投 worker 连接,不经频道广播:前端自己与跨 owner 的 worker 均收不到
            assertNoFrame(fe, "subscriber.join", 500);
            assertNoFrame(other, "subscriber.join", 500);

            // worker 订阅 stream 频道不触发(仅 frontend 订阅通知)
            sub(worker, ch("task.t78.stream"));
            assertNoFrame(worker, "subscriber.join", 500);

            // 退订(确曾订阅)→ leave;未订阅过的 unsub 不发
            fe.send("{\"type\":\"unsub\",\"channel\":\"" + ch("task.t77.stream") + "\"}");
            String leave = worker.awaitEvent("subscriber.leave");
            assertTrue(leave.contains("\"taskId\":\"t77\""), leave);
            assertTrue(leave.contains("\"sessionId\":\"" + feSession + "\""), leave);
            fe.send("{\"type\":\"unsub\",\"channel\":\"" + ch("task.t99.stream") + "\"}");
            assertNoFrame(worker, "subscriber.leave", 500);

            // 前端断连 → cleanup 走 unsubscribeAll,同样补发 leave(worker 据此销毁定向推送器)
            sub(fe, ch("task.t79.stream"));
            worker.await(t -> t.contains("subscriber.join") && t.contains("t79"), "t79 join");
            fe.close();
            String leave2 = worker.awaitEvent("subscriber.leave");
            assertTrue(leave2.contains("t79"), leave2);
        }
    }

    /** 负向断言:ms 内不应出现含 needle 的帧(排空式,同 differentKeysAreIsolated)。 */
    private static void assertNoFrame(WsTestClient c, String needle, long ms) {
        long deadline = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < deadline) {
            var f = c.collector.frames.poll();
            if (f != null && f.text() != null && f.text().contains(needle)) {
                throw new AssertionError("不应收到 " + needle + ": " + f.text());
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
