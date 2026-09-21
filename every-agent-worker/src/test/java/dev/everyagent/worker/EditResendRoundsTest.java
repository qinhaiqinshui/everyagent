package dev.everyagent.worker;

import dev.everyagent.contract.frame.Frames;
import dev.everyagent.contract.ids.Ids;
import dev.everyagent.contract.json.Json;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.task.ModelRateLimiterRegistry;
import dev.everyagent.worker.hub.HubPool;
import dev.everyagent.worker.modules.ConfigStore.ResolvedConfig;
import dev.everyagent.worker.proto.Channels;
import dev.everyagent.worker.task.ChatModelFactory;
import dev.everyagent.worker.task.TaskManager;
import dev.everyagent.worker.task.TaskStore;
import org.junit.jupiter.api.AfterAll;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 消息编辑重发(编辑点之前轮次保留)集成测试。
 * 复现用户报告:发送 3 条消息(A、b、c)→ 任务完成后编辑第 3 条重发 →
 * rounds.jsonl 只剩编辑后 1 轮(index=1),刷新后历史全部丢失。
 * 覆盖冷路径(终态任务 task.run 携带 editSeq → truncateForColdEdit + startRerun)。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class EditResendRoundsTest {

    private static final String KEY = "test-key-edit-rounds";
    private static final AtomicLong REQ = new AtomicLong();
    private static final Path WS = Path.of("target/test-workspace-edit-rounds").toAbsolutePath().normalize();
    private static final Path HOME_DIR = TestCleanup.register(
            Path.of("target/test-home-edit-rounds-" + System.nanoTime()).toAbsolutePath().normalize());
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
            return new ChatModelFactory(props, new ModelRateLimiterRegistry(props)) {
                @Override
                public org.springframework.ai.chat.model.ChatModel build(ResolvedConfig cfg,
                        org.springframework.ai.openai.OpenAiChatOptions options, String agentId,
                        dev.everyagent.worker.task.TaskEvents events) {
                    return new FakeChatModel();
                }

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
        r.add("worker.worker-id", () -> "test-worker-edit-rounds");
        r.add("worker.hub-initial-backoff-ms", () -> "100");
        r.add("worker.hub-max-backoff-ms", () -> "300");
        r.add("worker.limits.max-concurrent-tasks", () -> "4");
        r.add("worker.limits.max-concurrent-subs", () -> "4");
        r.add("worker.limits.ask-timeout-ms", () -> "120000");
        r.add("worker.limits.sub-wait-timeout-ms", () -> "15000");
        r.add("worker.retry.max-request-retries", () -> "0");
        r.add("worker.home-dir", () -> HOME_DIR.toString());
        r.add("worker.workspace-root", () -> "target/test-workspace-edit-rounds");
        // 模型配置(只读):经 Spring 属性注入占位配置(实际模型被 Cfg.fakeModelFactory 替换)
        r.add("worker.models[0].config-id", () -> "edit-default");
        r.add("worker.models[0].name", () -> "编辑测试模型");
        r.add("worker.models[0].provider", () -> "openai-compat");
        r.add("worker.models[0].base-url", () -> "http://fake-edit");
        r.add("worker.models[0].model", () -> "fake-edit-model");
        r.add("worker.models[0].api-key", () -> "sk-test");
        r.add("worker.models[0].default", () -> "true");
    }

    @Autowired
    WorkerProperties workerProps;

    @Autowired
    HubPool pool;

    @Autowired
    TaskStore store;

    @Autowired
    TaskManager mgr;

    private final String k = Ids.ownerKey(KEY);
    private WsTestClient fe;

    @BeforeEach
    void setUp() {
        long deadline = System.currentTimeMillis() + 20_000;
        while (!pool.anyConnected()) {
            assertTrue(System.currentTimeMillis() < deadline, "hub 未在 20s 内连上 FakeHub");
            sleep(50);
        }
        fe = WsTestClient.connect(URI.create("ws://127.0.0.1:" + PORT + "/fakehub"));
        hello(fe);
        sub(fe, Channels.tasks(k));
        sub(fe, Channels.workerEvt(k, workerProps.getWorkerId()));
    }

    @AfterEach
    void tearDown() {
        if (fe != null) {
            fe.close();
        }
    }

    @AfterAll
    static void cleanupDirs() {
        TestCleanup.deleteAll();
    }

    /**
     * 用户场景复现:发送 3 条消息(逐轮完成)→ 编辑第 3 条重发(冷路径)→
     * rounds.jsonl 应保留 3 轮(index 1/2/3),第 3 轮为编辑后文本;
     * 事件日志应保留前 2 轮的 user.message,第 3 轮旧消息被删、新消息在场。
     */
    @Test
    void coldEditKeepsPriorRounds() throws java.io.IOException {
        // 三轮逐次完成(每轮终态后再发下一条,与用户操作一致)
        String taskId = create("1+1=？");
        awaitEvicted(taskId);
        run(taskId, "2+2=？");
        awaitEvicted(taskId);
        run(taskId, "第三问原文");
        awaitEvicted(taskId);

        // 前置:此时 rounds.jsonl 应有 3 条闭合轮(编辑前的正常状态)
        Path roundsFile = store.dirOf(taskId).resolve("rounds.jsonl");
        List<String> before = Files.readAllLines(roundsFile, StandardCharsets.UTF_8);
        assertEquals(3, before.size(), "编辑前应有 3 轮: " + before);

        // 取第 3 条消息(被编辑目标)的 user.message seq
        String editSeq = lastUserMessageSeq(taskId, "第三问原文");
        assertFalse(editSeq.isEmpty(), "应找到第 3 条 user.message");

        // 编辑重发(冷路径:任务终态,task.run 携带 editSeq)
        RpcResp edit = runEdit(taskId, "上一个问题是什么", editSeq);
        assertFalse(edit.isErr(), "编辑重发应 ok: " + String.valueOf(edit.err()));
        awaitEvicted(taskId);

        // 断言 1:rounds.jsonl 保留 3 轮;第 3 轮 index=3、user=编辑后文本
        List<String> after = Files.readAllLines(roundsFile, StandardCharsets.UTF_8);
        assertEquals(3, after.size(), "编辑后 rounds.jsonl 应保留 3 轮(编辑点之前的轮次不得丢失): " + after);
        JsonNode r3 = Json.parse(after.get(2));
        assertEquals(3, r3.path("index").asLong(), "第 3 轮 index 应为 3: " + after.get(2));
        assertEquals("上一个问题是什么", r3.path("user").asString());
        JsonNode r1 = Json.parse(after.get(0));
        assertEquals("1+1=？", r1.path("user").asString(), "第 1 轮保留: " + after.get(0));
        JsonNode r2 = Json.parse(after.get(1));
        assertEquals("2+2=？", r2.path("user").asString(), "第 2 轮保留: " + after.get(1));

        // 断言 2:task.rounds 应答同口径(前端刷新后的取数口)
        RpcResp rounds = call("task.rounds", "{\"taskId\":\"" + taskId + "\"}");
        assertFalse(rounds.isErr(), String.valueOf(rounds.err()));
        assertEquals(3, rounds.result().path("rounds").size(),
                "task.rounds 应答应含 3 轮: " + rounds.result().path("rounds"));

        // 断言 3:事件日志保留前 2 轮 user.message;被编辑旧消息删除、新消息在场
        List<String> userTexts = pollUserMessageTexts(taskId);
        assertEquals(List.of("1+1=？", "2+2=？", "上一个问题是什么"), userTexts,
                "事件日志应保留编辑点之前的 user.message: " + userTexts);
    }

    /**
     * 热路径编辑(任务运行中 task.input 携带 editSeq → truncateForEdit):
     * SLOW 慢速轮进行中编辑该轮消息 → 截断磁盘+内存+重置落盘游标 → 队列消费新输入。
     * 断言:编辑点之前轮次(轮1)保留,被编辑轮删除、新轮 index 顺延。
     */
    @Test
    void hotEditKeepsPriorRounds() throws java.io.IOException {
        String taskId = create("轮次一");
        awaitEvicted(taskId);

        // 慢速轮(SLOW:分片延迟 ~9s),运行中窗口内编辑
        run(taskId, "SLOW:慢速轮");
        sleep(2000); // 等 user.message 已发出、模型流进行中(第一片 1.2s 后)
        String editSeq = lastUserMessageSeq(taskId, "SLOW:慢速轮");
        assertFalse(editSeq.isEmpty(), "应找到慢速轮 user.message");

        // 运行中编辑重发(热路径:task.input msg 携带 editSeq)
        fe.send(pub(Channels.workerInput(k, workerProps.getWorkerId()), "task.input",
                "{\"taskId\":\"" + taskId + "\",\"text\":\"热路径编辑后\",\"editSeq\":\"" + editSeq + "\"}"));
        awaitEvicted(taskId);

        // rounds:轮1 保留;慢速轮行删除;新轮 append(index=2,user=编辑后文本)
        Path roundsFile = store.dirOf(taskId).resolve("rounds.jsonl");
        List<String> lines = Files.readAllLines(roundsFile, StandardCharsets.UTF_8);
        assertEquals(2, lines.size(), "热路径编辑后应保留 2 轮: " + lines);
        JsonNode r1 = Json.parse(lines.get(0));
        assertEquals("轮次一", r1.path("user").asString());
        assertEquals(1, r1.path("index").asLong());
        JsonNode r2 = Json.parse(lines.get(1));
        assertEquals("热路径编辑后", r2.path("user").asString(), "被编辑轮替换为新文本: " + lines.get(1));
        assertEquals(2, r2.path("index").asLong(), "新轮 index 顺延(不得归 1): " + lines.get(1));

        // 事件日志:轮1 保留,慢速轮消息删除,新消息在场
        List<String> userTexts = pollUserMessageTexts(taskId);
        assertTrue(userTexts.contains("轮次一"), "轮1 保留: " + userTexts);
        assertTrue(userTexts.contains("热路径编辑后"), "新消息在场: " + userTexts);
        assertFalse(userTexts.contains("SLOW:慢速轮"), "被编辑旧消息应删除: " + userTexts);
    }

    // ---- 帮助方法 ----

    private record RpcResp(List<JsonNode> data, JsonNode result, JsonNode err) {
        boolean isErr() {
            return err != null;
        }
    }

    private RpcResp call(String method, String paramsJson) {
        String reqId = "r-" + REQ.incrementAndGet();
        fe.send(pub(Channels.workerCmd(k, workerProps.getWorkerId()), "rpc",
                "{\"reqId\":\"" + reqId + "\",\"method\":\"" + method
                        + "\",\"params\":" + paramsJson + "}"));
        List<JsonNode> data = new ArrayList<>();
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            String t = fe.awaitNew(x -> x.contains(reqId)
                            && (x.contains("\"event\":\"rpc.data\"")
                            || x.contains("\"event\":\"rpc.ok\"")
                            || x.contains("\"event\":\"rpc.err\"")),
                    "rpc " + method + " " + paramsJson);
            JsonNode payload = Json.parse(t).path("payload");
            if (t.contains("\"event\":\"rpc.data\"")) {
                for (JsonNode e : payload.path("batch")) {
                    data.add(e);
                }
            } else if (t.contains("\"event\":\"rpc.ok\"")) {
                return new RpcResp(data, payload.path("result"), null);
            } else {
                return new RpcResp(data, null, payload);
            }
        }
        throw new AssertionError("rpc 超时: " + method + " " + paramsJson);
    }

    /** 全量拉事件,返回全部 user.message 的 text 列表(按 seq 升序)。 */
    private List<String> pollUserMessageTexts(String taskId) {
        RpcResp r = call("task.poll", "{\"taskId\":\"" + taskId + "\",\"mode\":\"events\",\"afterSeq\":0,\"limit\":500}");
        assertFalse(r.isErr(), String.valueOf(r.err()));
        List<String> out = new ArrayList<>();
        for (JsonNode e : r.data()) {
            if ("user.message".equals(e.path("event").asString())) {
                out.add(e.path("payload").path("text").asString());
            }
        }
        return out;
    }

    /** 取指定文本的 user.message 的 wire seq(字符串,避免精度丢失)。 */
    private String lastUserMessageSeq(String taskId, String text) {
        RpcResp r = call("task.poll", "{\"taskId\":\"" + taskId + "\",\"mode\":\"events\",\"afterSeq\":0,\"limit\":500}");
        assertFalse(r.isErr(), String.valueOf(r.err()));
        String seq = "";
        for (JsonNode e : r.data()) {
            if ("user.message".equals(e.path("event").asString())
                    && text.equals(e.path("payload").path("text").asString())) {
                seq = e.path("seq").asString();
            }
        }
        return seq;
    }

    private void awaitEvicted(String taskId) {
        long deadline = System.currentTimeMillis() + 20_000;
        // 连续 3 次(≥150ms)不在内存才算驱逐:task.run 认领(diskTasks.remove)与
        // startRerun 放回 tasks 之间存在过渡窗口,单次 get==null 会误判「已完成」。
        int stable = 0;
        while (System.currentTimeMillis() < deadline) {
            if (mgr.get(taskId) == null) {
                if (++stable >= 3) {
                    return;
                }
            } else {
                stable = 0;
            }
            sleep(50);
        }
        throw new AssertionError("任务未在 20s 内终态驱逐: " + taskId);
    }

    private void hello(WsTestClient c) {
        c.send("{\"type\":\"hello\",\"ver\":" + Frames.PROTOCOL_VERSION
                + ",\"role\":\"frontend\",\"apiKey\":\"" + KEY
                + "\",\"clientId\":\"fe-edit-rounds\"}");
        c.await(t -> t.contains("\"type\":\"welcome\""), "welcome");
    }

    private void sub(WsTestClient c, String channel) {
        c.send("{\"type\":\"sub\",\"channel\":\"" + channel + "\"}");
    }

    private String pub(String channel, String event, String payloadJson) {
        return "{\"type\":\"pub\",\"mid\":\"m-" + REQ.incrementAndGet()
                + "\",\"channel\":\"" + channel + "\",\"event\":\"" + event
                + "\",\"ts\":" + System.currentTimeMillis() + ",\"payload\":" + payloadJson + "}";
    }

    private String create(String input) {
        tools.jackson.databind.node.ObjectNode params = Json.obj()
                .put("input", input).put("workspace", WS.toString());
        RpcResp r = call("task.run", Json.write(params));
        assertFalse(r.isErr(), "创建失败: " + String.valueOf(r.err()));
        return r.result().path("taskId").asString();
    }

    /** 续跑一条输入(任务已终态 → 冷启动)。 */
    private RpcResp run(String taskId, String input) {
        tools.jackson.databind.node.ObjectNode params = Json.obj()
                .put("taskId", taskId).put("input", input);
        return call("task.run", Json.write(params));
    }

    /** 编辑重发:task.run 携带 editSeq。 */
    private RpcResp runEdit(String taskId, String input, String editSeq) {
        tools.jackson.databind.node.ObjectNode params = Json.obj()
                .put("taskId", taskId).put("input", input).put("editSeq", editSeq);
        return call("task.run", Json.write(params));
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
