package dev.everyagent.worker;

import dev.everyagent.contract.frame.Frames;
import dev.everyagent.contract.ids.Ids;
import dev.everyagent.contract.json.Json;
import dev.everyagent.hub.HubApplication;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.hub.HubPool;
import dev.everyagent.worker.modules.ConfigStore.ResolvedConfig;
import dev.everyagent.worker.proto.Channels;
import dev.everyagent.worker.task.ChatModelFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多 hub worker 验收:worker 同一 workerId 注册到 3 条连接
 * (hubA1/hubA2 同 apiKey = 输出冗余扇出;hubB 异 apiKey = 多用户共用 worker)。
 * - 扇出:hubA1 建任务,hubA2 同 key 前端双收;不做 owner 隔离 → hubB 也收到广播。
 * - 任务统一存 data/tasks/&lt;taskId&gt;/;tasks.list 返回全部任务(不做 owner 过滤)。
 * - 跨用户 input/delete 一律放行(apiKey 只认证、不决定业务归属)。
 * - 来源应答:RPC 应答回请求来源连接(各前端只听自己 hub 的 evt 频道)。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class MultiHubE2eTest {

    private static final String KEY_A = "multi-key-a";
    private static final String KEY_B = "multi-key-b";
    private static final AtomicLong REQ = new AtomicLong();
    /** 3 个真实 hub 的 hub-key(与 application.yml 默认值一致;worker 与 frontend hello 均须携带)。 */
    private static final String HUB_KEY = "sljlw23948LKS";
    private static final java.nio.file.Path WS =
            java.nio.file.Path.of("target/test-ws-multi").toAbsolutePath().normalize();

    static ConfigurableApplicationContext hubA1;
    static ConfigurableApplicationContext hubA2;
    static ConfigurableApplicationContext hubB;
    static int portA1;
    static int portA2;
    static int portB;

    static {
        hubA1 = startHub();
        hubA2 = startHub();
        hubB = startHub();
        portA1 = portOf(hubA1);
        portA2 = portOf(hubA2);
        portB = portOf(hubB);
    }

    private static ConfigurableApplicationContext startHub() {
        // hub 是 WebFlux 应用,worker 测试类 classpath 有 servlet 依赖会被推导成 SERVLET,
        // 必须显式 REACTIVE 才能走 Netty + reactive 路由(/ws)。
        return new SpringApplicationBuilder(HubApplication.class)
                .web(WebApplicationType.REACTIVE)
                .run("--server.port=0", "--hub.hub-key=" + HUB_KEY);
    }

    private static int portOf(ConfigurableApplicationContext app) {
        return ((org.springframework.boot.web.server.context.WebServerApplicationContext) app)
                .getWebServer().getPort();
    }

    @AfterAll
    static void stopHubs() {
        for (ConfigurableApplicationContext app : List.of(hubA1, hubA2, hubB)) {
            if (app != null) {
                app.close();
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Cfg {
        @Bean
        @Primary
        ChatModelFactory fakeModelFactory(WorkerProperties props) {
            return new ChatModelFactory(props) {
                @Override
                public org.springframework.ai.chat.model.ChatModel build(ResolvedConfig cfg,
                        org.springframework.ai.openai.OpenAiChatOptions options, String agentId) {
                    return new FakeChatModel();
                }
            };
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("worker.worker-id", () -> "multi-worker");
        r.add("worker.hubs[0].url", () -> "ws://127.0.0.1:" + portA1 + "/ws");
        r.add("worker.hubs[0].api-key", () -> KEY_A);
        r.add("worker.hubs[0].hub-key", () -> HUB_KEY);
        r.add("worker.hubs[1].url", () -> "ws://127.0.0.1:" + portA2 + "/ws");
        r.add("worker.hubs[1].api-key", () -> KEY_A);
        r.add("worker.hubs[1].hub-key", () -> HUB_KEY);
        r.add("worker.hubs[2].url", () -> "ws://127.0.0.1:" + portB + "/ws");
        r.add("worker.hubs[2].api-key", () -> KEY_B);
        r.add("worker.hubs[2].hub-key", () -> HUB_KEY);
        r.add("worker.hub-initial-backoff-ms", () -> "100");
        r.add("worker.hub-max-backoff-ms", () -> "300");
        r.add("worker.home-dir", () -> "target/test-home-multi-" + System.nanoTime());
        r.add("worker.workspace-root", () -> "target/test-ws-multi");
    }

    @Autowired
    WorkerProperties workerProps;

    @Autowired
    HubPool pool;

    private final String kA = Ids.ownerKey(KEY_A);
    private final String kB = Ids.ownerKey(KEY_B);
    private WsTestClient feA1;
    private WsTestClient feA2;
    private WsTestClient feB;

    @BeforeEach
    void setUp() {
        long deadline = System.currentTimeMillis() + 20_000;
        while (pool.conns().stream().filter(dev.everyagent.worker.hub.HubLink::isConnected).count() < 3) {
            assertTrue(System.currentTimeMillis() < deadline, "worker 未在 20s 内连上全部 3 个 hub");
            sleep(50);
        }
        feA1 = connect(portA1, KEY_A, "fe-a1");
        feA2 = connect(portA2, KEY_A, "fe-a2");
        feB = connect(portB, KEY_B, "fe-b");
        sub(feA1, Channels.tasks(kA));
        sub(feA2, Channels.tasks(kA));
        sub(feB, Channels.tasks(kB));
        // RPC 应答回来源连接的 evt 频道:各前端须先订自己的 evt(先订后请求)
        sub(feA1, Channels.workerEvt(kA, workerProps.getWorkerId()));
        sub(feA2, Channels.workerEvt(kA, workerProps.getWorkerId()));
        sub(feB, Channels.workerEvt(kB, workerProps.getWorkerId()));
    }

    @AfterEach
    void tearDown() {
        for (WsTestClient c : List.of(feA1, feA2, feB)) {
            if (c != null) {
                c.close();
            }
        }
    }

    @Test
    void fanOutToMatchingHubsAndAllBroadcast() {
        // hubA1 建任务(SLOW 模型脚本分片延迟输出)
        String taskId = create(feA1, "SLOW:A1 的任务");
        assertTrue(taskId.startsWith("t_"), taskId);

        // 同 key 双 hub 双收:done 通知(任务频道广播)两边都到;历史经 task.poll 在各自连接上拉取验证
        feA1.await(t -> t.contains("\"event\":\"task.updated\"") && t.contains("\"status\":\"done\"")
                && t.contains(taskId), "A1 收到终态");
        feA2.await(t -> t.contains("\"event\":\"task.updated\"") && t.contains("\"status\":\"done\"")
                && t.contains(taskId), "A2(同 key 第二 hub)同样收到");
        syncUntil(feA1, taskId,
                e -> e.path("event").asString().equals("message"), "A1 经 task.poll 拉历史");
        syncUntil(feA2, taskId,
                e -> e.path("event").asString().equals("message"), "A2 经 task.poll 拉历史");

        // 不做 owner 隔离:pubAllTasks 广播到所有连接的 tasks 频道,B 侧也收到 A 的消息
        feB.await(t -> t.contains("\"event\":\"task.updated\"") && t.contains(taskId),
                "不做隔离:hubB 也收到 A 的任务通知");

        // 任意 hub 侧 sync 等价:feA2 经第二连接回放磁盘
        List<JsonNode> events = sync(feA2, taskId, 0);
        assertTrue(events.stream().anyMatch(e -> e.path("event").asString().equals("message")),
                "第二 hub 连接可回放");
        // 任务统一落 data/tasks/<taskId>/(不做 owner 分隔)
        assertTrue(java.nio.file.Files.isRegularFile(workerProps.resolveDataDir().resolve("tasks")
                .resolve(taskId).resolve("meta.json")), "任务落 data/tasks/<taskId>/");

        // hubB 建自己的任务:照常闭环,且不做 owner 隔离 → A 侧同样可见
        String taskB = create(feB, "B 自己的任务");
        feB.await(t -> t.contains("\"event\":\"task.updated\"") && t.contains("\"status\":\"done\"")
                && t.contains(taskB), "B 任务闭环");
        feA1.await(t -> t.contains(taskB), "不做隔离:A 侧也收到 B 的任务通知");
        assertTrue(java.nio.file.Files.isRegularFile(workerProps.resolveDataDir().resolve("tasks")
                .resolve(taskB).resolve("meta.json")), "B 任务落 data/tasks/<taskB>/");
        // tasks.list 返回全部(不做 owner 过滤)
        assertTrue(rpc(feB, "tasks.list", "{}").contains(taskId), "B 列表也见 A 任务(返回全部)");
        assertTrue(rpc(feA1, "tasks.list", "{}").contains(taskId), "A 列表有自己任务");
    }

    @Test
    void crossUserInputAndDeleteAllowed() {
        String taskId = create(feA1, "A 的目标任务");
        feA1.await(t -> t.contains("\"event\":\"task.updated\"") && t.contains("\"status\":\"done\"")
                && t.contains(taskId), "A 任务完成");
        List<JsonNode> firstRun = sync(feA1, taskId, 0);
        long firstRunLastSeq = firstRun.getLast().path("seq").asLong();

        // 不做 owner 隔离:B 前端在 B 自己的 input 频道上对 A 的任务发输入 → 直接放行,触发冷启动再运行
        feB.send(pub(Channels.workerInput(kB, workerProps.getWorkerId()), "task.input",
                "{\"taskId\":\"" + taskId + "\",\"text\":\"THINK:跨用户追问\"}"));
        syncUntil(feA1, taskId, e -> e.path("event").asString().equals("user.message")
                && e.path("payload").path("text").asString().contains("跨用户追问"), "跨用户 user.message");
        // THINK 脚本带 150ms×2 延迟:等再运行末条 message 落盘再取最末 message(否则读到首轮回答)
        syncUntil(feA1, taskId, e -> e.path("event").asString().equals("message")
                && e.path("seq").asLong() > firstRunLastSeq, "再运行 message");
        JsonNode secondAnswer = sync(feA1, taskId, 0).stream()
                .filter(e -> e.path("event").asString().equals("message"))
                .reduce((first, second) -> second).orElseThrow();
        assertTrue(secondAnswer.path("payload").path("text").asString().contains("上下文2:"),
                "再运行携带历史: " + secondAnswer.path("payload").path("text").asString());

        // 不做 owner 隔离:任意连接(包括 B)均可删除该任务
        assertTrue(rpc(feB, "task.delete", "{\"taskId\":\"" + taskId + "\"}").contains("\"deleted\":true"));
        assertFalse(java.nio.file.Files.exists(workerProps.resolveDataDir().resolve("tasks").resolve(taskId)),
                "目录删除");
    }

    // ---- 帮助方法 ----

    private WsTestClient connect(int port, String apiKey, String clientId) {
        WsTestClient c = WsTestClient.connect(URI.create("ws://127.0.0.1:" + port + "/ws"));
        c.send("{\"type\":\"hello\",\"ver\":" + Frames.PROTOCOL_VERSION + ",\"role\":\"frontend\",\"apiKey\":\""
                + apiKey + "\",\"clientId\":\"" + clientId + "\",\"hubKey\":\"" + HUB_KEY + "\"}");
        c.await(t -> t.contains("\"type\":\"welcome\""), "welcome " + clientId);
        return c;
    }

    private void sub(WsTestClient c, String channel) {
        c.send("{\"type\":\"sub\",\"channel\":\"" + channel + "\"}");
    }

    private String pub(String channel, String event, String payloadJson) {
        return "{\"type\":\"pub\",\"mid\":\"m-" + REQ.incrementAndGet() + "\",\"channel\":\"" + channel
                + "\",\"event\":\"" + event + "\",\"ts\":" + System.currentTimeMillis()
                + ",\"payload\":" + payloadJson + "}";
    }

    /** RPC 在指定前端/连接上发起,应答必须回到该前端(来源应答)。 */
    private String rpc(WsTestClient c, String method, String paramsJson) {
        String k = c == feB ? kB : kA;
        String reqId = "r-" + REQ.incrementAndGet();
        c.send(pub(Channels.workerCmd(k, workerProps.getWorkerId()), "rpc",
                "{\"reqId\":\"" + reqId + "\",\"method\":\"" + method + "\",\"params\":" + paramsJson + "}"));
        return c.await(t -> t.contains(reqId) && (t.contains("rpc.ok") || t.contains("rpc.err")),
                "rpc " + method + " @ " + (c == feB ? "hubB" : "hubA"));
    }

    private String create(WsTestClient c, String input) {
        String resp = rpc(c, "task.run", Json.write(Json.obj()
                .put("input", input).put("workspace", WS.toString())));
        assertTrue(resp.contains("rpc.ok"), "创建失败: " + resp);
        return Json.parse(resp).path("payload").path("result").path("taskId").asString();
    }

    /** task.poll 应答封装:data 批次(按 seq 升序) + ok/err 结果。 */
    private record PollResp(List<JsonNode> data, JsonNode result, JsonNode err) {
        boolean isErr() {
            return err != null;
        }
    }

    /** 在指定前端/连接上发 task.poll,收集该 reqId 的全部 rpc.data 帧与最终 ok/err(来源应答)。 */
    private PollResp poll(WsTestClient c, String paramsJson) {
        String k = c == feB ? kB : kA;
        String reqId = "r-" + REQ.incrementAndGet();
        c.send(pub(Channels.workerCmd(k, workerProps.getWorkerId()), "rpc",
                "{\"reqId\":\"" + reqId + "\",\"method\":\"task.poll\",\"params\":" + paramsJson + "}"));
        List<JsonNode> data = new ArrayList<>();
        JsonNode result = null;
        JsonNode err = null;
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            String t = c.awaitNew(x -> x.contains(reqId)
                            && (x.contains("\"event\":\"rpc.data\"")
                            || x.contains("\"event\":\"rpc.ok\"")
                            || x.contains("\"event\":\"rpc.err\"")),
                    "task.poll " + paramsJson);
            JsonNode payload = Json.parse(t).path("payload");
            if (t.contains("\"event\":\"rpc.data\"")) {
                for (JsonNode e : payload.path("batch")) {
                    data.add(e);
                }
            } else if (t.contains("\"event\":\"rpc.ok\"")) {
                result = payload.path("result");
                break;
            } else {
                err = payload;
                break;
            }
        }
        if (err != null) {
            return new PollResp(data, null, err);
        }
        assertTrue(result != null, "task.poll 未在限时内 ok/err: " + paramsJson);
        return new PollResp(data, result, null);
    }

    /**
     * task.poll 纯拉取:返回 from afterSeq 之后到当前最新的事件列表(events 模式,按 seq 升序)。
     * 轮询直到追平当前尾部(批次为空)即返回快照;终态(live=false)且批次为空 = 历史已到尾部;
     * 在跑任务返回「磁盘 ∪ 内存 EventLog」的当前快照(syncUntil 依赖该快照语义逐次递增轮询)。
     */
    private List<JsonNode> sync(WsTestClient c, String taskId, long afterSeq) {
        long deadline = System.currentTimeMillis() + 15_000;
        List<JsonNode> events = new ArrayList<>();
        while (System.currentTimeMillis() < deadline) {
            JsonNode params = Json.obj()
                    .put("taskId", taskId)
                    .put("mode", "events")
                    .put("afterSeq", afterSeq)
                    .put("limit", 500);
            PollResp p = poll(c, Json.write(params));
            assertFalse(p.isErr(), "task.poll sync 失败: " + String.valueOf(p.err));
            if (p.data.isEmpty()) {
                return events; // 追平当前尾部(终态=历史收齐;在跑=当前快照)
            }
            events.addAll(p.data); // 批次已按 seq 升序
            afterSeq = p.data.get(p.data.size() - 1).path("seq").asLong();
        }
        throw new AssertionError("task.poll sync 超时(持续有新事件),taskId=" + taskId);
    }

    /** 轮询 task.poll 快照直到出现满足条件的事件(任务历史纯拉取;磁盘 ∪ 内存尾部)。 */
    private JsonNode syncUntil(WsTestClient c, String taskId,
            java.util.function.Predicate<JsonNode> cond, String what) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            for (JsonNode e : sync(c, taskId, 0)) {
                if (cond.test(e)) {
                    return e;
                }
            }
            sleep(150);
        }
        throw new AssertionError("超时(task.poll 轮询),等待: " + what);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
