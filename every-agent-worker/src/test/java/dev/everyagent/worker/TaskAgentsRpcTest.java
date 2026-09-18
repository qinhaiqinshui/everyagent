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
import dev.everyagent.worker.task.TaskEntry;
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
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

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
 * task.agents 集成测试(前端打开任务详情拉取子 agent 台账的唯一取数口)。
 * 覆盖:不存在任务 NOT_FOUND / 缺 taskId BAD_PARAMS;
 * 终态(已驱逐出内存的磁盘)任务读 agents.json(2 个子 agent,含 usage+context+lastText 透传、
 * 按 createdAt 升序稳定排序、mainAgentId 正确);
 * 旧任务回退(目录只有 meta.json 带 agents 数组、无 agents.json → 返回 meta.agents 内容);
 * live 任务读内存 agentLedger(waiting-user 任务驻留内存,直接往 ledger 放摘要模拟运行中台账,
 * 免去真实子 agent 的构造成本)。
 * 复用 WorkerTaskPollTest / TaskRoundsRpcTest 的测试基建(FakeHub + FakeChatModel + WsTestClient)。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class TaskAgentsRpcTest {

    private static final String KEY = "test-key-agents";
    private static final AtomicLong REQ = new AtomicLong();
    private static final Path WS = Path.of("target/test-workspace-agents").toAbsolutePath().normalize();
    /** 系统目录(每次运行唯一,@AfterAll 统一清理)。 */
    private static final Path HOME_DIR = TestCleanup.register(
            Path.of("target/test-home-agents-" + System.nanoTime()).toAbsolutePath().normalize());
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
                // 覆写带 events 的 4 参重载:buildAgentModel 普通模型路径实际分派到这里
                // (3 参重载只是兼容入口,拦不到 agent 装配)。
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
        r.add("worker.worker-id", () -> "test-worker-agents");
        r.add("worker.hub-initial-backoff-ms", () -> "100");
        r.add("worker.hub-max-backoff-ms", () -> "300");
        r.add("worker.limits.max-concurrent-tasks", () -> "4");
        r.add("worker.limits.max-concurrent-subs", () -> "4");
        r.add("worker.limits.ask-timeout-ms", () -> "120000");
        r.add("worker.limits.sub-wait-timeout-ms", () -> "15000");
        r.add("worker.retry.max-request-retries", () -> "0");
        r.add("worker.home-dir", () -> HOME_DIR.toString());
        r.add("worker.workspace-root", () -> "target/test-workspace-agents");
        // 模型配置(只读):直接经 Spring 属性注入 worker.models(同 WorkerIntegrationTest 桩法,
        // 不依赖本机 ~/.everyagent/application-worker.yaml;实际构建由 FakeChatModel 替身接管)。
        r.add("worker.models[0].config-id", () -> "test-main");
        r.add("worker.models[0].name", () -> "测试主模型");
        r.add("worker.models[0].provider", () -> "openai-compat");
        r.add("worker.models[0].base-url", () -> "http://fake-main");
        r.add("worker.models[0].model", () -> "model-main");
        r.add("worker.models[0].api-key", () -> "sk-test");
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

    @Test
    void unknownTaskIsNotFound() {
        RpcResp r = call("task.agents", "{\"taskId\":\"t_nonexistent\"}");
        assertTrue(r.isErr(), "不存在任务应 NOT_FOUND");
        assertEquals("NOT_FOUND", r.err().path("code").asString());
        assertTrue(r.err().path("message").asString().contains("t_nonexistent"),
                "错误信息应含 taskId: " + r.err());
    }

    @Test
    void missingTaskIdIsBadParams() {
        RpcResp r = call("task.agents", "{}");
        assertTrue(r.isErr(), "缺 taskId 应 BAD_PARAMS");
        assertEquals("BAD_PARAMS", r.err().path("code").asString());
    }

    @Test
    void diskAgentsJsonReturnedSortedWithUsageContextLastText() throws java.io.IOException {
        String taskId = create("你好,台账测试");
        awaitEvicted(taskId); // finish 全部完成(flush/驱逐),磁盘为完整真相源,live=null 走磁盘路径
        Path dir = store.dirOf(taskId);
        Path agentsFile = dir.resolve("agents.json");
        assertTrue(Files.isRegularFile(agentsFile) == false,
                "无子 agent 的任务不应有 agents.json(空台账不落盘): " + agentsFile);

        // 手工写 agents.json:故意把 createdAt 较大的放前面,验证应答按 createdAt 升序稳定排序。
        // 一个带 usage+context+lastText,一个只有基础字段(可选字段省略透传)。
        ObjectNode withUsage = Json.obj()
                .put("agentId", "sub_b")
                .put("kind", "sub")
                .put("title", "调研B")
                .put("createdAt", 2000L)
                .put("status", "completed")
                .put("lastText", "B 已完成");
        withUsage.set("usage", Json.obj()
                .put("inputTokens", 100).put("outputTokens", 50).put("totalTokens", 150));
        withUsage.set("context", Json.obj()
                .put("inputTokens", 40).put("contextWindowTokens", 200000).put("model", "gpt-test"));
        ObjectNode basic = Json.obj()
                .put("agentId", "sub_a")
                .put("kind", "sub")
                .put("title", "调研A")
                .put("createdAt", 1000L)
                .put("status", "stopped");
        ObjectNode root = Json.obj();
        ArrayNode arr = Json.arr();
        arr.add(withUsage); // 乱序:b(2000) 在前
        arr.add(basic); // a(1000) 在后
        root.set("agents", arr);
        Files.writeString(agentsFile, Json.write(root), StandardCharsets.UTF_8);

        RpcResp r = call("task.agents", "{\"taskId\":\"" + taskId + "\"}");
        assertFalse(r.isErr(), String.valueOf(r.err()));
        JsonNode agents = r.result().path("agents");
        assertTrue(agents.isArray(), "agents 应为数组: " + r.result());
        assertEquals(2, agents.size(), "应返回 2 个子 agent: " + agents);
        // 升序:sub_a(1000) 在前、sub_b(2000) 在后
        assertEquals("sub_a", agents.get(0).path("agentId").asString(), "按 createdAt 升序: " + agents);
        assertEquals("sub_b", agents.get(1).path("agentId").asString(), "按 createdAt 升序: " + agents);
        // 完整项字段透传(usage 累计 / context 最近一轮上下文快照 / lastText)
        JsonNode b = agents.get(1);
        assertEquals(150, b.path("usage").path("totalTokens").asLong(), "usage 透传: " + b);
        assertEquals(40, b.path("context").path("inputTokens").asLong(), "context 透传: " + b);
        assertEquals(200000, b.path("context").path("contextWindowTokens").asLong(), "context 透传: " + b);
        assertEquals("gpt-test", b.path("context").path("model").asString(), "context 透传: " + b);
        assertEquals("B 已完成", b.path("lastText").asString(), "lastText 透传: " + b);
        assertEquals("completed", b.path("status").asString());
        // 基础项:可选字段保持省略(不存在而非 null)
        JsonNode a = agents.get(0);
        assertEquals("stopped", a.path("status").asString());
        assertTrue(a.path("usage").isMissingNode(), "无 usage 的项应省略字段: " + a);
        assertTrue(a.path("context").isMissingNode(), "无 context 的项应省略字段: " + a);
        // mainAgentId 与 meta.json 一致
        JsonNode meta = Json.parse(Files.readString(dir.resolve("meta.json"), StandardCharsets.UTF_8));
        assertEquals(meta.path("mainAgentId").asString(), r.result().path("mainAgentId").asString(),
                "mainAgentId 取自 meta: " + r.result());
        assertFalse(r.result().path("mainAgentId").asString("").isEmpty(), "mainAgentId 应非空");
    }

    @Test
    void legacyMetaAgentsFallback() throws java.io.IOException {
        String taskId = create("你好,旧任务台账");
        awaitEvicted(taskId);
        Path dir = store.dirOf(taskId);
        assertTrue(!Files.isRegularFile(dir.resolve("agents.json")),
                "无 agents.json 才触发旧格式回退: " + dir);

        // 旧任务形态:meta.json 内带 agents 数组(台账随 meta 落盘的旧格式)
        ObjectNode meta = (ObjectNode) Json.parse(Files.readString(dir.resolve("meta.json"), StandardCharsets.UTF_8));
        ArrayNode legacy = Json.arr();
        legacy.add(Json.obj()
                .put("agentId", "sub_old")
                .put("kind", "sub")
                .put("title", "旧任务子agent")
                .put("createdAt", 3000L)
                .put("status", "stopped")
                .put("lastText", "旧格式快照"));
        meta.set("agents", legacy);
        Files.writeString(dir.resolve("meta.json"), Json.write(meta), StandardCharsets.UTF_8);

        RpcResp r = call("task.agents", "{\"taskId\":\"" + taskId + "\"}");
        assertFalse(r.isErr(), String.valueOf(r.err()));
        JsonNode agents = r.result().path("agents");
        assertEquals(1, agents.size(), "回退读 meta.agents: " + r.result());
        assertEquals("sub_old", agents.get(0).path("agentId").asString());
        assertEquals("旧任务子agent", agents.get(0).path("title").asString());
        assertEquals("旧格式快照", agents.get(0).path("lastText").asString(), "meta.agents 内容原样透传");
        assertEquals(meta.path("mainAgentId").asString(), r.result().path("mainAgentId").asString(),
                "mainAgentId 仍取自 meta: " + r.result());
    }

    @Test
    void noAgentsReturnsEmptyArray() throws java.io.IOException {
        String taskId = create("你好,无子agent任务");
        awaitEvicted(taskId);
        RpcResp r = call("task.agents", "{\"taskId\":\"" + taskId + "\"}");
        assertFalse(r.isErr(), String.valueOf(r.err()));
        JsonNode agents = r.result().path("agents");
        assertTrue(agents.isArray() && agents.isEmpty(), "无子 agent → agents=[](非 null): " + r.result());
        assertTrue(r.result().path("mainAgentId").isTextual(), "mainAgentId 恒为字符串: " + r.result());
    }

    @Test
    void liveTaskReadsInMemoryLedger() {
        // live 路径:waiting-user 任务驻留内存(finish 未发生、未驱逐)。
        // 真实子 agent 需要完整 AgentRunner 回合,构造成本高;agentLedger 是 TaskEntry 公开字段,
        // 直接放摘要即等价于「活实体已实时刷新进台账」的状态(与 SubAgentManager 写入口径相同)。
        String taskId = create("ASK:继续吗");
        fe.await(t -> t.contains("\"event\":\"task.updated\"")
                && t.contains("\"status\":\"waiting-user\"") && t.contains(taskId), "等待用户输入(waiting-user)");
        TaskEntry live = mgr.get(taskId);
        assertFalse(live == null, "waiting-user 任务应在内存");
        live.agentLedger.put("sub_live_b", Json.obj()
                .put("agentId", "sub_live_b").put("kind", "sub").put("title", "live B")
                .put("createdAt", 5000L).put("status", "running").put("lastText", "b 进行中"));
        live.agentLedger.put("sub_live_a", Json.obj()
                .put("agentId", "sub_live_a").put("kind", "sub").put("title", "live A")
                .put("createdAt", 4000L).put("status", "completed"));

        RpcResp r = call("task.agents", "{\"taskId\":\"" + taskId + "\"}");
        assertFalse(r.isErr(), String.valueOf(r.err()));
        JsonNode agents = r.result().path("agents");
        assertEquals(2, agents.size(), "live 任务取内存台账: " + agents);
        assertEquals("sub_live_a", agents.get(0).path("agentId").asString(), "createdAt 升序: " + agents);
        assertEquals("sub_live_b", agents.get(1).path("agentId").asString(), "createdAt 升序: " + agents);
        assertEquals("running", agents.get(1).path("status").asString());
        assertEquals("b 进行中", agents.get(1).path("lastText").asString());
        assertEquals(live.mainAgentId, r.result().path("mainAgentId").asString(),
                "live 任务 mainAgentId 取内存字段: " + r.result());
        assertFalse(r.result().path("mainAgentId").asString("").isEmpty());

        // 清理:取消并等待终态驱逐,释放并发槽位
        RpcResp cancel = call("task.cancel", "{\"taskId\":\"" + taskId + "\"}");
        assertFalse(cancel.isErr(), String.valueOf(cancel.err()));
        awaitEvicted(taskId);
    }

    // ---- 帮助方法 ----

    /** RPC 应答封装:rpc.data 批次(可多帧)+ 最终 ok(result)/err。 */
    private record RpcResp(List<JsonNode> data, JsonNode result, JsonNode err) {
        boolean isErr() {
            return err != null;
        }
    }

    /** 发一次 RPC(任意方法),收集该 reqId 的全部 rpc.data 帧与最终 ok/err。 */
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

    /** 等待任务从内存驱逐(finish 全部完成:flush/索引/移除)。 */
    private void awaitEvicted(String taskId) {
        long deadline = System.currentTimeMillis() + 20_000;
        while (mgr.get(taskId) != null) {
            assertTrue(System.currentTimeMillis() < deadline, "任务未在 20s 内终态驱逐: " + taskId);
            sleep(50);
        }
    }

    private void hello(WsTestClient c) {
        c.send("{\"type\":\"hello\",\"ver\":" + Frames.PROTOCOL_VERSION
                + ",\"role\":\"frontend\",\"apiKey\":\"" + KEY
                + "\",\"clientId\":\"fe-agents\"}");
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
        ObjectNode params = Json.obj()
                .put("input", input).put("workspace", WS.toString());
        RpcResp r = call("task.run", Json.write(params));
        assertFalse(r.isErr(), "创建失败: " + String.valueOf(r.err()));
        return r.result().path("taskId").asString();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
