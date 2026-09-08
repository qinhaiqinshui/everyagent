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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 整体联调(M1–M5 验收):进程内启动真实 hub,worker 经真实 WS 链路接入,
 * 前端测试客户端走真实 hub 完成 presence / 任务创建 / 流式 / sync 回放 / fs / config。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class WorkerHubE2eTest {

    private static final String KEY = "e2e-key-1";
    private static final AtomicLong REQ = new AtomicLong();
    /** 真实 hub 的 hub-key(与 application.yml 默认值一致;worker 与 frontend 的 hello 均须携带)。 */
    private static final String HUB_KEY = "sljlw23948LKS";
    /** 默认工作区(workspace-root 同值,task.run/fs 必填 workspace 参数)。 */
    private static final java.nio.file.Path WS =
            java.nio.file.Path.of("target/test-ws-e2e").toAbsolutePath().normalize();

    static ConfigurableApplicationContext hubApp;
    static int hubPort;

    // 静态块先于测试上下文加载(含 @DynamicPropertySource 解析),保证 worker 首次连接即指向真实 hub。
    // hub 是 WebFlux 应用,worker 测试类 classpath 同时有 servlet 依赖会被推导成 SERVLET,
    // 必须显式 REACTIVE 才能走 Netty + reactive 路由(/ws);端口取 WebServer 实际绑定值。
    static {
        hubApp = new SpringApplicationBuilder(HubApplication.class)
                .web(WebApplicationType.REACTIVE)
                .run("--server.port=0", "--hub.hub-key=" + HUB_KEY);
        hubPort = ((org.springframework.boot.web.server.context.WebServerApplicationContext) hubApp)
                .getWebServer().getPort();
        assert hubPort > 0;
    }

    @AfterAll
    static void stopHub() {
        if (hubApp != null) {
            hubApp.close();
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
        r.add("worker.hubs[0].url", () -> "ws://127.0.0.1:" + hubPort + "/ws");
        r.add("worker.hubs[0].api-key", () -> KEY);
        r.add("worker.hubs[0].hub-key", () -> HUB_KEY);
        r.add("worker.worker-id", () -> "e2e-worker");
        r.add("worker.hub-initial-backoff-ms", () -> "100");
        r.add("worker.hub-max-backoff-ms", () -> "300");
        // 系统目录每次运行唯一:models.json 不落真实用户主目录,持久化工作区选择也不跨运行串状态
        r.add("worker.home-dir", () -> "target/test-home-e2e-" + System.nanoTime());
        r.add("worker.workspace-root", () -> "target/test-ws-e2e");
    }

    @Autowired
    WorkerProperties workerProps;

    @Autowired
    HubPool pool;

    private final String k = Ids.ownerKey(KEY);
    private WsTestClient fe;

    @BeforeEach
    void setUp() {
        long deadline = System.currentTimeMillis() + 20_000;
        while (!pool.anyConnected()) {
            assertTrue(System.currentTimeMillis() < deadline, "worker 未在 20s 内连上真实 hub");
            sleep(50);
        }
        fe = WsTestClient.connect(URI.create("ws://127.0.0.1:" + hubPort + "/ws"));
        fe.send("{\"type\":\"hello\",\"ver\":" + Frames.PROTOCOL_VERSION
                + ",\"role\":\"frontend\",\"apiKey\":\"" + KEY + "\",\"clientId\":\"fe-e2e\",\"hubKey\":\""
                + HUB_KEY + "\"}");
        fe.await(t -> t.contains("\"type\":\"welcome\""), "welcome(真实 hub)");
        sub(Channels.tasks(k));
        sub(Channels.workers(k));
        sub(Channels.workerEvt(k, workerProps.getWorkerId()));
    }

    @AfterEach
    void tearDown() {
        if (fe != null) {
            fe.close();
        }
    }

    @Test
    void presenceTaskStreamSyncThroughRealHub() throws java.io.IOException {
        // presence:强制 worker 重连一次,验证 offline/online 事件经真实 hub 扇出。
        // hub 订阅 workers 频道时会补发在线快照,历史里已有一条 online,
        // 须等「第二条」才证明真实重连发生;连接态另用轮询兜底(重连有退避窗口)。
        pool.primary().forceReconnect();
        fe.await(t -> t.contains("\"event\":\"worker.offline\"") && t.contains("e2e-worker"),
                "worker.offline presence");
        fe.awaitCount(2, "\"event\":\"worker.online\"", "worker.online presence(快照+真实重连)");
        long deadline = System.currentTimeMillis() + 20_000;
        while (!pool.anyConnected()) {
            assertTrue(System.currentTimeMillis() < deadline, "worker 重连成功");
            sleep(50);
        }

        // 任务闭环:创建 → done → task.poll 回放(持久事件,seq 有洞合法)。
        // FakeChatModel 秒回,首轮瞬态帧(task.poll 内存缓冲)可能在拉取前已随 done 驱逐——
        // 瞬态帧断言放在下方「再运行」段(THINK 脚本带延迟,窗口确定)。
        String taskId = create("THINK:E2E:你好");
        assertTrue(taskId.startsWith("t_"), "短 ID: " + taskId);
        String doneFrame = fe.await(t -> t.contains("\"event\":\"task.updated\"")
                && t.contains("\"status\":\"done\"") && t.contains(taskId), "任务经真实 hub 完成");
        assertTrue(doneFrame.contains("\"workspace\""), "task.updated 摘要带 workspace 字段: " + doneFrame);

        List<JsonNode> events = sync(taskId, 0);
        List<String> names = events.stream().map(e -> e.path("event").asString()).toList();
        assertTrue(names.contains("message"), names.toString());
        assertFalse(names.contains("delta") || names.contains("thinking"),
                "瞬态事件不落盘不回放: " + names);
        for (int i = 1; i < events.size(); i++) {
            assertTrue(events.get(i).path("seq").asLong() > events.get(i - 1).path("seq").asLong(),
                    "回放 seq 严格递增(洞合法,真实 hub)");
        }
        JsonNode messageEv = events.stream()
                .filter(e -> e.path("event").asString().equals("message")).findFirst().orElseThrow();
        assertEquals("思考完成", messageEv.path("payload").path("thinking").asString(),
                "message 行携带整轮 thinking");

        // 增量 sync:task.poll 支持按 seq 增量拉取(from last-1 → 仅最后一条事件)
        long last = events.getLast().path("seq").asLong();
        List<JsonNode> tail = sync(taskId, last - 1);
        assertEquals(1, tail.size(), "增量拉取只含 afterSeq 之后的事件: " + tail);
        assertEquals(last, tail.get(0).path("seq").asLong(), "增量返回最后一条事件");

        // 任务数据落系统目录 data/tasks/<taskId>/(经真实链路全量持久化,按 agent 分文件)
        java.nio.file.Path taskDir = workerProps.resolveDataDir().resolve("tasks").resolve(taskId);
        JsonNode meta = Json.parse(java.nio.file.Files.readString(taskDir.resolve("meta.json")));
        String mainAgentId = meta.path("mainAgentId").asString();
        assertTrue(mainAgentId.startsWith("a_"), mainAgentId);
        assertTrue(java.nio.file.Files.isRegularFile(taskDir.resolve(mainAgentId + ".jsonl")),
                "主 agent 分文件落 data/tasks/<taskId>");

        // 再运行(冷启动)经 task.input 触发:新一轮权威 message 与收口终态经 task.poll 纯拉取验证。
        // (瞬态 delta 与 message 共享轮 seq,TaskManager 按 seq 归并后以 message 定稿,逐帧 delta
        // 拉不到属正常;stream 频道推送随 DataPusher 删除。曾因 taskId 为大写 ULID 被 hub 频道
        // 字符集 ACL 拒收的旧风险已无频道依赖。耗时不再发 task_duration trace,故以终态
        // agent.status(done)作为收口信号。)
        fe.send(pub(Channels.workerInput(k, workerProps.getWorkerId()), "task.input",
                Json.write(Json.obj().put("taskId", taskId).put("text", "THINK:E2E:追问"))));
        syncUntil(taskId, e -> e.path("event").asString().equals("message")
                        && e.path("seq").asLong() > last
                        && e.path("payload").path("text").asString().contains("追问"),
                "再运行 message(task.poll 可拉取)");
        syncUntil(taskId, e -> e.path("event").asString().equals("agent.status")
                        && e.path("seq").asLong() > last
                        && "done".equals(e.path("payload").path("status").asString()),
                "再运行收口 agent.status(done)");
    }

    /**
     * worker 重连(等价模拟服务重启)后的韧性:前端 WS 与订阅(pull 模式无 stream 订阅)原样保留,
     * 直接经 worker 级 input 频道触发下一轮运行,worker 重连后照常续跑,新输出可经 task.poll 拉取。
     * (原 taskInputRebuildsPusherAfterWorkerReconnect 验证 DataPusher 断线重建/stream 推送——该机制
     * 已随「任务流纯拉取」改造整体删除,断言迁移为 task.poll 结果验证,保留重连韧性意图。)
     */
    @Test
    void taskContinuesAfterWorkerReconnect() {
        String taskId = create("THINK:E2E:第一轮");
        fe.await(t -> t.contains("\"event\":\"task.updated\"") && t.contains("\"status\":\"done\"")
                && t.contains(taskId), "任务经真实 hub 完成(第一轮)");
        long lastSeq = sync(taskId, 0).getLast().path("seq").asLong();

        // 模拟 worker 重启:强制重连 hub 连接
        pool.primary().forceReconnect();
        long deadline = System.currentTimeMillis() + 20_000;
        while (!pool.anyConnected()) {
            assertTrue(System.currentTimeMillis() < deadline, "worker 重连成功");
            sleep(50);
        }
        // 前端不刷新、不重新订阅(pull 模式无频道订阅),直接发下一轮输入:
        // 重连后任务照常续跑,输出经 task.poll 可拉取。
        fe.send(pub(Channels.workerInput(k, workerProps.getWorkerId()), "task.input",
                Json.write(Json.obj().put("taskId", taskId).put("text", "THINK:E2E:重启后续跑"))));
        syncUntil(taskId, e -> e.path("event").asString().equals("message")
                        && e.path("seq").asLong() > lastSeq
                        && e.path("payload").path("text").asString().contains("重启后续跑"),
                "重启后续跑 message(task.poll 可拉取)");
    }

    @Test
    void fsAndConfigThroughRealHub() {
        // Windows 绝对路径经 Json.write 转义:裸拼的反斜杠转义会让真实 hub 直接断连
        String b64 = java.util.Base64.getEncoder()
                .encodeToString("e2e 内容".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String w = rpc("fs.write", Json.write(Json.obj()
                .put("path", "e2e.txt").put("contentBase64", b64).put("workspace", WS.toString())));
        assertTrue(w.contains("rpc.ok") && w.contains("e2e.txt"), w);

        String l = rpc("fs.list", Json.write(Json.obj()
                .put("path", ".").put("workspace", WS.toString())));
        assertTrue(l.contains("e2e.txt"), l);

        String c = rpc("config.get", "{}");
        assertTrue(c.contains("models") && !c.contains("\"apiKey\":\"sk-"), "apiKey 掩码: " + c);

        String methods = rpc("sys.methods", "{}");
        assertTrue(methods.contains("task.run") && methods.contains("git.status")
                && methods.contains("workspaces.list") && methods.contains("task.delete"), methods);

        String wsList = rpc("workspaces.list", "{}");
        // 断言走解析后比较:线上 JSON 反斜杠已转义,裸 contains 匹配不到 Windows 路径
        boolean hasWs = false;
        for (JsonNode wsn : Json.parse(wsList).path("payload").path("result").path("workspaces")) {
            hasWs |= WS.toString().equals(wsn.path("root").asString());
        }
        assertTrue(hasWs && !wsList.contains("wsKey"), "注册表无 wsKey: " + wsList);
    }

    // ---- 帮助方法 ----

    private void sub(String channel) {
        fe.send("{\"type\":\"sub\",\"channel\":\"" + channel + "\"}");
    }

    private String pub(String channel, String event, String payloadJson) {
        return "{\"type\":\"pub\",\"mid\":\"m-" + REQ.incrementAndGet() + "\",\"channel\":\"" + channel
                + "\",\"event\":\"" + event + "\",\"ts\":" + System.currentTimeMillis()
                + ",\"payload\":" + payloadJson + "}";
    }

    private String rpc(String method, String paramsJson) {
        String reqId = "r-" + REQ.incrementAndGet();
        fe.send("{\"type\":\"pub\",\"mid\":\"m-" + REQ.incrementAndGet() + "\",\"channel\":\""
                + Channels.workerCmd(k, workerProps.getWorkerId()) + "\",\"event\":\"rpc\",\"ts\":"
                + System.currentTimeMillis() + ",\"payload\":{\"reqId\":\"" + reqId
                + "\",\"method\":\"" + method + "\",\"params\":" + paramsJson + "}}");
        return fe.await(t -> t.contains(reqId) && (t.contains("rpc.ok") || t.contains("rpc.err")),
                "rpc " + method + "(真实 hub)");
    }

    private String create(String input) {
        String resp = rpc("task.run", Json.write(Json.obj()
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

    /** 发一次 task.poll,收集该 reqId 的全部 rpc.data 帧与最终 ok/err(经真实 hub 转发)。 */
    private PollResp poll(String paramsJson) {
        String reqId = "r-" + REQ.incrementAndGet();
        fe.send("{\"type\":\"pub\",\"mid\":\"m-" + REQ.incrementAndGet() + "\",\"channel\":\""
                + Channels.workerCmd(k, workerProps.getWorkerId()) + "\",\"event\":\"rpc\",\"ts\":"
                + System.currentTimeMillis() + ",\"payload\":{\"reqId\":\"" + reqId
                + "\",\"method\":\"task.poll\",\"params\":" + paramsJson + "}}");
        List<JsonNode> data = new ArrayList<>();
        JsonNode result = null;
        JsonNode err = null;
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            String t = fe.awaitNew(x -> x.contains(reqId)
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
    private List<JsonNode> sync(String taskId, long afterSeq) {
        long deadline = System.currentTimeMillis() + 15_000;
        List<JsonNode> events = new ArrayList<>();
        while (System.currentTimeMillis() < deadline) {
            JsonNode params = Json.obj()
                    .put("taskId", taskId)
                    .put("mode", "events")
                    .put("afterSeq", afterSeq)
                    .put("limit", 500);
            PollResp p = poll(Json.write(params));
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
    private JsonNode syncUntil(String taskId, java.util.function.Predicate<JsonNode> cond, String what) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            for (JsonNode e : sync(taskId, 0)) {
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
