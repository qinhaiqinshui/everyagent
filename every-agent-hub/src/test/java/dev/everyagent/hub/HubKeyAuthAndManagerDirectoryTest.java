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
 * hub-key 连接鉴权 + 管理端 worker 目录广播验收(独立 Spring 上下文,通过
 * {@link DynamicPropertySource} 注入原始 hub.hub-key——hub 启动自算 sha256,
 * 与生产部署形态一致)。
 *
 * <ul>
 *   <li>配置 hub.hub-key 后:hello 缺失/错误 hubKey → NOT_AUTHENTICATED 并关闭;正确 hubKey → welcome。</li>
 *   <li>管理连接(apiKey=hubKey 的 frontend)订阅 u.&lt;managerK&gt;.workers 收到跨 ownerKey 全量 worker 目录。</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HubKeyAuthAndManagerDirectoryTest {

    @LocalServerPort
    int port;

    private static final String HUB_KEY = "hub-secret-key";
    /** sha256(hub key) 小写 hex(频道名断言用,与 Ids.ownerKey 一致)。 */
    private static final String HUB_KEY_HASH = Ids.ownerKey(HUB_KEY);
    private static final String WORKER_KEY = "sk-owner-b";

    @DynamicPropertySource
    static void hubKeyProperty(DynamicPropertyRegistry registry) {
        registry.add("hub.hub-key", () -> HUB_KEY);
    }

    private WsTestClient connect() {
        return WsTestClient.connect(URI.create("ws://localhost:" + port + "/ws"));
    }

    /** 带可选 hubKey 的 hello(兼容不带 hubKey 的旧帧,用于缺失场景)。 */
    private void hello(WsTestClient c, String role, String apiKey, String clientId, String hubKey) {
        c.send("{\"type\":\"hello\",\"ver\":" + Frames.PROTOCOL_VERSION + ",\"role\":\"" + role
                + "\",\"apiKey\":\"" + apiKey + "\",\"clientId\":\"" + clientId + "\""
                + (hubKey == null ? "" : ",\"hubKey\":\"" + hubKey + "\"") + "}");
        c.await(t -> t.contains("\"type\":\"welcome\""), "welcome");
    }

    private void sub(WsTestClient c, String channel) {
        c.send("{\"type\":\"sub\",\"channel\":\"" + channel + "\"}");
    }

    private String ch(String ownerKey, String suffix) {
        return "u." + ownerKey + "." + suffix;
    }

    @Test
    void missingHubKeyIsRejectedWhenConfigured() {
        try (WsTestClient c = connect()) {
            c.send("{\"type\":\"hello\",\"ver\":" + Frames.PROTOCOL_VERSION
                    + ",\"role\":\"frontend\",\"apiKey\":\"sk-any\",\"clientId\":\"fe-1\"}");
            String err = c.await(t -> t.contains("NOT_AUTHENTICATED"), "hub key error");
            assertTrue(err.contains("hub key not allowed"), err);
            c.awaitClosed();
        }
    }

    @Test
    void wrongHubKeyIsRejectedForWorkerToo() {
        try (WsTestClient c = connect()) {
            c.send("{\"type\":\"hello\",\"ver\":" + Frames.PROTOCOL_VERSION
                    + ",\"role\":\"worker\",\"apiKey\":\"sk-any\",\"clientId\":\"w-1\",\"hubKey\":\"wrong\"}");
            String err = c.await(t -> t.contains("NOT_AUTHENTICATED"), "hub key error");
            assertTrue(err.contains("hub key not allowed"), err);
            c.awaitClosed();
        }
    }

    @Test
    void correctHubKeyGetsWelcomeForAnyRole() {
        try (WsTestClient fe = connect(); WsTestClient worker = connect()) {
            hello(fe, "frontend", HUB_KEY, "fe-1", HUB_KEY); // 管理连接(apiKey=hubKey)
            hello(worker, "worker", WORKER_KEY, "w-1", HUB_KEY); // 普通 worker,apiKey 任意
        }
    }

    @Test
    void managerSeesAllWorkersAcrossOwners() {
        try (WsTestClient mgr = connect(); WsTestClient worker = connect()) {
            hello(mgr, "frontend", HUB_KEY, "mgr-1", HUB_KEY);
            sub(mgr, ch(HUB_KEY_HASH, "workers"));
            hello(worker, "worker", WORKER_KEY, "w-1", HUB_KEY);

            String online = mgr.awaitEvent("worker.online");
            assertTrue(online.contains("\"workerId\":\"w-1\""), online);
            assertTrue(online.contains("\"channel\":\"u." + HUB_KEY_HASH + ".workers\""), online);
        }
    }

    @Test
    void managerSnapshotShowsWorkersOnlineBeforeSubscribe() {
        try (WsTestClient worker = connect(); WsTestClient mgr = connect()) {
            hello(worker, "worker", WORKER_KEY, "w-1", HUB_KEY);
            // worker 先上线,管理连接后订阅 → 应收到补发的全量快照
            hello(mgr, "frontend", HUB_KEY, "mgr-1", HUB_KEY);
            sub(mgr, ch(HUB_KEY_HASH, "workers"));
            String online = mgr.awaitEvent("worker.online");
            assertTrue(online.contains("\"workerId\":\"w-1\""), online);
        }
    }

    @Test
    void managerSeesOfflineWhenWorkerDisconnects() {
        try (WsTestClient mgr = connect(); WsTestClient worker = connect()) {
            hello(mgr, "frontend", HUB_KEY, "mgr-1", HUB_KEY);
            sub(mgr, ch(HUB_KEY_HASH, "workers"));
            hello(worker, "worker", WORKER_KEY, "w-1", HUB_KEY);
            mgr.awaitEvent("worker.online");

            worker.close(); // abort 触发断开 → worker.offline 广播到管理目录
            String offline = mgr.awaitEvent("worker.offline");
            assertTrue(offline.contains("\"workerId\":\"w-1\""), offline);
        }
    }
}