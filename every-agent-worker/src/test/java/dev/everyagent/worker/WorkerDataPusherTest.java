package dev.everyagent.worker;

import dev.everyagent.contract.frame.Frames;
import dev.everyagent.contract.ids.Ids;
import dev.everyagent.contract.json.Json;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.hub.HubPool;
import dev.everyagent.worker.modules.ConfigStore.ResolvedConfig;
import dev.everyagent.worker.proto.Channels;
import dev.everyagent.worker.ship.DataPusherManager;
import dev.everyagent.worker.task.ChatModelFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.server.standard.ServerEndpointExporter;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 定向推送(DataPusher)集成测试:混合模型的 worker 端推送侧验收。
 * 覆盖:join 建推送器 → 实时增量定向推送(ext.target=前端会话,thinking/delta=append、
 * message=replace);子 agent 增量帧带 payload.agentId(wire 口径与 task.poll 一致);
 * 终态任务订阅不推数据帧、task.input 再运行 → 新一轮输出换挂推送;退订销毁;
 * 前端断连清扫;worker 断链重连后 task.input 兜底重建推送器;非本 worker 任务忽略;
 * 重复 join 幂等。
 * 复用 WorkerTaskPollTest 的测试基建(FakeHub + FakeChatModel + WsTestClient)。
 * FakeChatModel 的 THINK 流片间 150ms 延迟即为「create 返回 → sub 生效」预留窗口,
 * 且推送器首挂回放(replayEnd=size)兜住更晚的订阅——仅需在任务终态前完成 sub。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class WorkerDataPusherTest {

    private static final String KEY = "test-key-pusher";
    private static final AtomicLong REQ = new AtomicLong();
    private static final java.nio.file.Path WS =
            java.nio.file.Path.of("target/test-workspace-pusher").toAbsolutePath().normalize();
    private static final int PORT = freePort();

    private static int freePort() {
        try (var s = new java.net.ServerSocket(0)) {
            return s.getLocalPort();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Cfg {
        @Bean
        ServerEndpointExporter serverEndpointExporter() {
            return new ServerEndpointExporter();
        }

        @Bean
        FakeHub fakeHub() {
            return new FakeHub();
        }

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

        @Bean
        @Primary
        org.springframework.ai.chat.model.ChatModel fakeChatModel() {
            return new FakeChatModel();
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("server.port", () -> String.valueOf(PORT));
        r.add("worker.hubs[0].url", () -> "ws://127.0.0.1:" + PORT + "/fakehub");
        r.add("worker.hubs[0].api-key", () -> KEY);
        r.add("worker.hubs[0].hub-key", () -> "test-hub-key");
        r.add("worker.worker-id", () -> "test-worker-pusher");
        r.add("worker.hub-initial-backoff-ms", () -> "100");
        r.add("worker.hub-max-backoff-ms", () -> "300");
        r.add("worker.limits.max-concurrent-tasks", () -> "4");
        r.add("worker.limits.max-concurrent-subs", () -> "4");
        r.add("worker.limits.ask-timeout-ms", () -> "120000");
        r.add("worker.limits.sub-wait-timeout-ms", () -> "15000");
        r.add("worker.retry.max-request-retries", () -> "0");
        r.add("worker.home-dir", () -> "target/test-home-pusher-" + System.nanoTime());
        r.add("worker.workspace-root", () -> "target/test-workspace-pusher");
    }

    @Autowired
    WorkerProperties workerProps;

    @Autowired
    HubPool pool;

    @Autowired
    DataPusherManager pushers;

    private final String k = Ids.ownerKey(KEY);
    private WsTestClient fe;
    /** 前端会话 id(welcome 帧携带;= 推送帧 ext.target 的期望值)。 */
    private String sessionId;

    @BeforeEach
    void setUp() {
        long deadline = System.currentTimeMillis() + 20_000;
        while (!pool.anyConnected()) {
            assertTrue(System.currentTimeMillis() < deadline, "hub 未在 20s 内连上 FakeHub");
            sleep(50);
        }
        fe = WsTestClient.connect(URI.create("ws://127.0.0.1:" + PORT + "/fakehub"));
        sessionId = hello(fe);
        sub(fe, Channels.tasks(k));
        sub(fe, Channels.workerEvt(k, workerProps.getWorkerId()));
    }

    @AfterEach
    void tearDown() {
        if (fe != null) {
            fe.close();
        }
    }

    // ---- 推送用例 ----

    @Test
    void livePushDirectsDeltaThinkingAppendAndMessageReplace() {
        String taskId = create("THINK:推送测试");
        sub(fe, streamCh(taskId));

        String thinking = fe.await(t -> t.contains(streamCh(taskId))
                && t.contains("\"event\":\"thinking\"") && t.contains("\"operate\":\"append\""),
                "thinking 增量推送 append");
        assertTrue(thinking.contains("\"target\":\"" + sessionId + "\""),
                "定向到前端会话: " + thinking);

        String delta = fe.await(t -> t.contains(streamCh(taskId))
                && t.contains("\"event\":\"delta\"") && t.contains("\"operate\":\"append\""),
                "delta 增量推送 append");
        assertTrue(delta.contains("\"target\":\"" + sessionId + "\""),
                "定向到前端会话: " + delta);

        String message = fe.await(t -> t.contains(streamCh(taskId))
                && t.contains("\"event\":\"message\"") && t.contains("\"operate\":\"replace\""),
                "message 推送 replace");
        assertTrue(message.contains("\"target\":\"" + sessionId + "\""),
                "定向到前端会话: " + message);

        awaitPusherCount(1, "join 建立推送器");
        awaitDone(taskId);
    }

    @Test
    void streamAckRoutesToPusherAndReleasesCredit() {
        String taskId = create("THINK:背压ack");
        sub(fe, streamCh(taskId));

        String frame = fe.await(t -> t.contains(streamCh(taskId))
                && t.contains("\"credit\":true") && t.contains("\"creditIndex\""),
                "推送帧携带 credit 标记");
        assertTrue(frame.contains("\"target\":\"" + sessionId + "\""), "定向: " + frame);
        long creditIndex = Json.parse(frame).path("ext").path("creditIndex").asLong(-1);
        assertTrue(creditIndex >= 0, "creditIndex 非负: " + frame);

        // 前端回报消费进度:stream.ack 应被路由到该推送器(不建新、不报错)
        fe.send(pub(Channels.workerInput(k, workerProps.getWorkerId()), "stream.ack",
                "{\"taskId\":\"" + taskId + "\",\"creditIndex\":" + creditIndex + "}"));
        sleep(300);
        awaitPusherCount(1, "stream.ack 路由后推送器数不变");

        awaitDone(taskId);
    }

    @Test
    void subAgentLivePushCarriesAgentId() {
        String taskId = create("SUB:子代理增量");
        sub(fe, streamCh(taskId));

        // 主 agent 的 delta 帧无 agentId;子 agent 增量经 wireEvent 注入 payload.agentId
        String frame = fe.await(t -> t.contains(streamCh(taskId))
                && t.contains("\"event\":\"delta\"") && t.contains("\"agentId\""),
                "子 agent delta 帧带 payload.agentId");
        assertTrue(frame.contains("\"target\":\"" + sessionId + "\""),
                "定向到前端会话: " + frame);

        awaitPusherCount(1, "join 建立推送器");
        awaitDone(taskId);
    }

    @Test
    void terminalTaskNotPushedAndRerunPushesNewRun() {
        String taskId = create("首轮问候");
        awaitDone(taskId);

        sub(fe, streamCh(taskId));
        awaitPusherCount(1, "终态任务订阅仍建推送器(归属磁盘校验通过)");
        assertTrue(fe.absentAfter(streamCh(taskId), 700), "终态任务不应推数据帧");

        // 再运行:TaskResumeListener 唤醒推送器换挂新日志,新轮 thinking 增量实时推送
        fe.send(pub(Channels.workerInput(k, workerProps.getWorkerId()), "task.input",
                "{\"taskId\":\"" + taskId + "\",\"text\":\"THINK:再运行\"}"));
        String rerun = fe.await(t -> t.contains(streamCh(taskId))
                && t.contains("\"event\":\"thinking\"") && t.contains("\"operate\":\"append\""),
                "再运行新输出推送");
        assertTrue(rerun.contains("\"target\":\"" + sessionId + "\""),
                "再运行仍定向到该会话: " + rerun);
        awaitDone(taskId);
    }

    @Test
    void unsubDestroysPusher() {
        String taskId = create("你好");
        awaitDone(taskId);

        sub(fe, streamCh(taskId));
        awaitPusherCount(1, "订阅建立推送器");
        fe.send("{\"type\":\"unsub\",\"channel\":\"" + streamCh(taskId) + "\"}");
        awaitPusherCount(0, "退订销毁推送器");
    }

    @Test
    void frontendDisconnectSweepsPusher() {
        String taskId = create("你好");
        awaitDone(taskId);

        sub(fe, streamCh(taskId));
        awaitPusherCount(1, "订阅建立推送器");
        fe.close();
        fe = null; // 已断开,tearDown 无需再关
        awaitPusherCount(0, "前端断连(leave 通知/清扫)销毁推送器");
    }

    @Test
    void taskInputFallbackRebuildsPusherAfterWorkerReconnect() {
        String taskId = create("你好");
        awaitDone(taskId);

        sub(fe, streamCh(taskId));
        awaitPusherCount(1, "订阅建立推送器");
        pool.primary().forceReconnect();
        awaitPusherCount(0, "worker 断链清扫该连接的推送器");

        // 等重连完成并留出重订阅落定窗口,再模拟「前端未刷新仍继续输入」
        long deadline = System.currentTimeMillis() + 20_000;
        while (!pool.anyConnected()) {
            assertTrue(System.currentTimeMillis() < deadline, "worker 未在 20s 内重连");
            sleep(50);
        }
        sleep(800);

        fe.send(pub(Channels.workerInput(k, workerProps.getWorkerId()), "task.input",
                "{\"taskId\":\"" + taskId + "\",\"text\":\"THINK:兜底重建\"}"));
        awaitPusherCount(1, "task.input 兜底重建推送器");
        String rerun = fe.await(t -> t.contains(streamCh(taskId))
                && t.contains("\"event\":\"thinking\"") && t.contains("\"target\":\""
                + sessionId + "\""), "新轮输出仍定向推到该前端会话");
        assertTrue(rerun.contains("\"operate\":\"append\""), "thinking 增量为 append: " + rerun);
        awaitDone(taskId);
    }

    @Test
    void nonOwnedTaskJoinIgnored() {
        sub(fe, Channels.taskStream(k, "t_nope123"));
        sleep(700);
        assertEquals(0, pushers.pusherCount(), "非本 worker 任务的订阅通知被忽略(内存/磁盘均无)");
    }

    @Test
    void joinIdempotentPerSessionTask() {
        String taskId = create("你好");
        awaitDone(taskId);

        sub(fe, streamCh(taskId));
        sub(fe, streamCh(taskId));
        awaitPusherCount(1, "重复 join 幂等(同 sessionId|taskId 只建一个推送器)");
    }

    // ---- 帮助方法 ----

    private String streamCh(String taskId) {
        return Channels.taskStream(k, taskId);
    }

    /** 等活跃推送器数达到 n(推送器建/销均由异步通知驱动)。 */
    private void awaitPusherCount(int n, String what) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (pushers.pusherCount() != n) {
            assertTrue(System.currentTimeMillis() < deadline,
                    "超时等待推送器数=" + n + "(" + what + "),当前 " + pushers.pusherCount());
            sleep(50);
        }
    }

    /** 等任务终态 done(释放并发槽位,避免拖垮同上下文其它用例)。 */
    private void awaitDone(String taskId) {
        fe.await(t -> t.contains("\"event\":\"task.updated\"") && t.contains("\"status\":\"done\"")
                && t.contains(taskId), "任务终态 done");
    }

    private String hello(WsTestClient c) {
        c.send("{\"type\":\"hello\",\"ver\":" + Frames.PROTOCOL_VERSION
                + ",\"role\":\"frontend\",\"apiKey\":\"" + KEY
                + "\",\"clientId\":\"fe-pusher\"}");
        String welcome = c.await(t -> t.contains("\"type\":\"welcome\""), "welcome");
        return Json.parse(welcome).path("sessionId").asString();
    }

    private void sub(WsTestClient c, String channel) {
        c.send("{\"type\":\"sub\",\"channel\":\"" + channel + "\"}");
    }

    private String pub(String channel, String event, String payloadJson) {
        return "{\"type\":\"pub\",\"mid\":\"m-" + REQ.incrementAndGet()
                + "\",\"channel\":\"" + channel + "\",\"event\":\"" + event
                + "\",\"ts\":" + System.currentTimeMillis() + ",\"payload\":" + payloadJson + "}";
    }

    private String rpc(String method, String paramsJson) {
        String reqId = "r-" + REQ.incrementAndGet();
        fe.send(pub(Channels.workerCmd(k, workerProps.getWorkerId()), "rpc",
                "{\"reqId\":\"" + reqId + "\",\"method\":\"" + method
                        + "\",\"params\":" + paramsJson + "}"));
        return fe.await(t -> t.contains(reqId) && (t.contains("rpc.ok") || t.contains("rpc.err")),
                "rpc " + method);
    }

    private String create(String input) {
        tools.jackson.databind.node.ObjectNode params = Json.obj()
                .put("input", input).put("workspace", WS.toString());
        String resp = rpc("task.run", Json.write(params));
        assertTrue(resp.contains("rpc.ok"), "创建失败: " + resp);
        return Json.parse(resp).path("payload").path("result").path("taskId").asString();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
