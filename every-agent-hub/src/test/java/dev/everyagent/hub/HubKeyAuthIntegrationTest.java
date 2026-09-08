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
 * hub-key 连接鉴权 + 管理端 worker 目录广播验收。
 * 本类通过 @DynamicPropertySource 在运行时注入原始 hub.hub-key(hub 启动自算 sha256,
 * 与生产部署形态一致——配置填原文,程序派生哈希)。
 * 未配置 hub-key 的开发模式场景已移除(不再存在旁路)。
 *
 * <p>边界:不新增公共频道、不放宽 ChannelRules 命名空间前缀限制——管理目录仍走
 * 管理连接自己命名空间下的 u.&lt;managerK&gt;.workers 频道;管理连接判定 =
 * role==frontend 且 ownerKey==sha256(hubKey)(即前端用 apiKey=hubKey 连接)。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HubKeyAuthIntegrationTest {

    /** 连接 hub 的原始凭证(测试专用),同时是配置值与 hello 携带值。 */
    static final String HUB_KEY_RAW = "hub-secret-for-test";

    /** sha256(hub key) 小写 hex(频道名断言用,与 Ids.ownerKey 同源计算)。 */
    static final String HUB_KEY_SHA = Ids.ownerKey(HUB_KEY_RAW);

    @DynamicPropertySource
    static void hubProps(DynamicPropertyRegistry registry) {
        registry.add("hub.hub-key", () -> HUB_KEY_RAW);
    }

    @LocalServerPort
    int port;

    private static final String KEY_A = "sk-owner-a";

    private WsTestClient connect() {
        return WsTestClient.connect(URI.create("ws://localhost:" + port + "/ws"));
    }

    private String helloFrame(String role, String apiKey, String clientId, String hubKey) {
        StringBuilder sb = new StringBuilder(128);
        sb.append("{\"type\":\"hello\",\"ver\":").append(Frames.PROTOCOL_VERSION)
                .append(",\"role\":\"").append(role)
                .append("\",\"apiKey\":\"").append(apiKey)
                .append("\",\"clientId\":\"").append(clientId).append('"');
        if (hubKey != null && !hubKey.isEmpty()) {
            sb.append(",\"hubKey\":\"").append(hubKey).append('"');
        }
        sb.append('}');
        return sb.toString();
    }

    private void hello(WsTestClient c, String role, String apiKey, String clientId, String hubKey) {
        c.send(helloFrame(role, apiKey, clientId, hubKey));
        c.await(t -> t.contains("\"type\":\"welcome\""), "welcome");
    }

    private void sub(WsTestClient c, String channel) {
        c.send("{\"type\":\"sub\",\"channel\":\"" + channel + "\"}");
    }

    /** 管理连接自己的 u.&lt;managerK&gt;.workers 频道(managerK = sha256(hub key))。 */
    private String managerChannel() {
        return "u." + HUB_KEY_SHA + ".workers";
    }

    @Test
    void wrongHubKeyRejectedAndClosed() {
        try (WsTestClient c = connect()) {
            c.send(helloFrame("frontend", KEY_A, "fe-1", "wrong-hub-key"));
            String err = c.await(t -> t.contains("NOT_AUTHENTICATED"), "not authenticated");
            assertTrue(err.contains("hub key not allowed"), err);
            c.awaitClosed();
        }
    }

    @Test
    void missingHubKeyRejectedWhenConfigured() {
        try (WsTestClient c = connect()) {
            c.send(helloFrame("worker", KEY_A, "home-pc", null));
            String err = c.await(t -> t.contains("NOT_AUTHENTICATED"), "not authenticated");
            assertTrue(err.contains("hub key not allowed"), err);
            c.awaitClosed();
        }
    }

    @Test
    void correctHubKeyWelcomeWithArbitraryApiKey() {
        // 前端可用任意 apiKey(ownerKey 自定),只须携带正确 hubKey → welcome
        try (WsTestClient c = connect()) {
            hello(c, "frontend", "sk-arbitrary-frontend", "fe-1", HUB_KEY_RAW);
        }
    }

    @Test
    void correctHubKeyWelcomeWithManagerApiKey() {
        // 管理连接:apiKey=hubKey(ownerKey==sha256(hubKey)) → welcome 且被识别为 manager
        try (WsTestClient c = connect()) {
            hello(c, "frontend", HUB_KEY_RAW, "mgr-1", HUB_KEY_RAW);
        }
    }

    @Test
    void managerReceivesWorkerOnlineAcrossOwners() {
        // 管理连接订阅自己的 u.<managerK>.workers;任意 apiKey 的 worker(携带正确 hubKey)上线
        // → 管理连接收到 worker.online(payload{workerId})。
        try (WsTestClient mgr = connect(); WsTestClient worker = connect()) {
            hello(mgr, "frontend", HUB_KEY_RAW, "mgr-1", HUB_KEY_RAW);
            sub(mgr, managerChannel());
            hello(worker, "worker", KEY_A, "home-pc", HUB_KEY_RAW);
            String online = mgr.awaitEvent("worker.online");
            assertTrue(online.contains("\"workerId\":\"home-pc\""), online);
        }
    }

    @Test
    void managerSnapshotOnSubscribeSeesWorkersAcrossOwners() {
        // 管理连接订阅 u.<managerK>.workers 时,补发全部在线 worker(跨 ownerKey 全量目录)。
        try (WsTestClient mgr = connect(); WsTestClient w1 = connect(); WsTestClient w2 = connect()) {
            hello(w1, "worker", KEY_A, "worker-a", HUB_KEY_RAW);
            hello(w2, "worker", "sk-owner-b", "worker-b", HUB_KEY_RAW);
            hello(mgr, "frontend", HUB_KEY_RAW, "mgr-1", HUB_KEY_RAW);
            sub(mgr, managerChannel());
            // 两个不同 ownerKey 的 worker 都应出现在快照里。快照帧以 hash 序到达,
            // 不能像 await 那样逐个等(会丢弃先到的另一帧),用累计 seen 的方式收齐。
            awaitSeen(mgr, "\"workerId\":\"worker-a\"", "\"workerId\":\"worker-b\"");
        }
    }

    /** 累计消费队列,直到每一帧都包含各自 needle(不丢弃先到的、暂未被等的帧)。 */
    private void awaitSeen(WsTestClient c, String... needles) {
        long deadline = System.currentTimeMillis() + 5000;
        java.util.Set<String> seen = new java.util.HashSet<>();
        while (seen.size() < needles.length && System.currentTimeMillis() < deadline) {
            WsTestClient.Frame f = c.collector.frames.poll();
            if (f != null) {
                if (f.closed()) {
                    throw new AssertionError("connection closed while waiting for: " + java.util.Arrays.toString(needles));
                }
                for (String n : needles) {
                    if (f.text().contains(n)) {
                        seen.add(n);
                    }
                }
                continue;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted");
            }
        }
        if (seen.size() < needles.length) {
            throw new AssertionError("timeout waiting for: " + java.util.Arrays.toString(needles) + ", seen=" + seen);
        }
    }
}