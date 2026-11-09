package dev.everyagent.worker;

import dev.everyagent.contract.frame.Frames;
import dev.everyagent.contract.ids.Ids;
import dev.everyagent.contract.json.Json;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.plugin.registry.ChatModelEnhancerRegistry;
import dev.everyagent.worker.hub.HubPool;
import dev.everyagent.worker.modules.ConfigStore;
import dev.everyagent.worker.modules.ConfigStore.ResolvedConfig;
import dev.everyagent.plugin.api.event.Channels;
import dev.everyagent.worker.config.ChatModelFactory;
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
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task.dataDir 集成测试(任务数据目录绝对路径拉取,前端任务列表「复制任务路径」取数口)。
 * 覆盖:不存在任务 NOT_FOUND / 缺 taskId BAD_PARAMS;
 * 终态(已驱逐出内存的磁盘)任务与 live(waiting-user 驻留内存)任务均返回
 * workspaces/&lt;workspaceId&gt;/tasks/&lt;taskId&gt;/ 的宿主绝对路径(与 TaskStore.dirOf 一致)。
 * 复用 TaskAgentsRpcTest 的测试基建(FakeHub + FakeChatModel + WsTestClient)。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class TaskDataDirRpcTest {

    private static final String KEY = "test-key-datadir";
    private static final AtomicLong REQ = new AtomicLong();
    private static final Path WS = Path.of("target/test-workspace-datadir").toAbsolutePath().normalize();
    /** 系统目录(每次运行唯一,@AfterAll 统一清理)。 */
    private static final Path HOME_DIR = TestCleanup.register(
            Path.of("target/test-home-datadir-" + System.nanoTime()).toAbsolutePath().normalize());
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
        ChatModelFactory fakeModelFactory(WorkerProperties props,
                ChatModelEnhancerRegistry enhancerRegistry,
                ConfigStore configStore) {
            return new ChatModelFactory(props, enhancerRegistry, configStore) {
                @Override
                public org.springframework.ai.chat.model.ChatModel build(ResolvedConfig cfg,
                        org.springframework.ai.openai.OpenAiChatOptions options, String agentId,
                        dev.everyagent.plugin.api.model.EventEmitter events) {
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
        r.add("worker.worker-id", () -> "test-worker-datadir");
        r.add("worker.hub-initial-backoff-ms", () -> "100");
        r.add("worker.hub-max-backoff-ms", () -> "300");
        r.add("worker.limits.max-concurrent-tasks", () -> "4");
        r.add("worker.limits.max-concurrent-subs", () -> "4");
        r.add("worker.limits.ask-timeout-ms", () -> "120000");
        r.add("worker.limits.sub-wait-timeout-ms", () -> "15000");
        r.add("worker.retry.max-request-retries", () -> "0");
        r.add("worker.home-dir", () -> HOME_DIR.toString());
        r.add("worker.workspace-root", () -> "target/test-workspace-datadir");
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
        sub(fe, Channels.tasks(k, workerProps.getWorkerId()));
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
        RpcResp r = call("task.dataDir", "{\"taskId\":\"t_nonexistent\"}");
        assertTrue(r.isErr(), "不存在任务应 NOT_FOUND");
        assertEquals("NOT_FOUND", r.err().path("code").asString());
        assertTrue(r.err().path("message").asString().contains("t_nonexistent"),
                "错误信息应含 taskId: " + r.err());
    }

    @Test
    void missingTaskIdIsBadParams() {
        RpcResp r = call("task.dataDir", "{}");
        assertTrue(r.isErr(), "缺 taskId 应 BAD_PARAMS");
        assertEquals("BAD_PARAMS", r.err().path("code").asString());
    }

    @Test
    void diskTaskReturnsAbsoluteDataDirPath() {
        String taskId = create("你好,任务路径测试");
        awaitEvicted(taskId); // finish 全部完成(flush/驱逐),磁盘为真相源

        RpcResp r = call("task.dataDir", "{\"taskId\":\"" + taskId + "\"}");
        assertFalse(r.isErr(), String.valueOf(r.err()));
        String path = r.result().path("path").asString("");
        assertFalse(path.isEmpty(), "应答应含 path: " + r.result());
        assertEquals(store.dirOf(taskId).toAbsolutePath().toString(), path,
                "路径应与 TaskStore.dirOf 一致");
        assertTrue(path.endsWith(Path.of("tasks", taskId).toString()),
                "路径应落在 tasks/<taskId>: " + path);
        assertTrue(Path.of(path).isAbsolute(), "路径应为宿主绝对路径: " + path);
        assertTrue(Path.of(path).startsWith(HOME_DIR), "路径应在 worker 数据目录下: " + path);
    }

    @Test
    void liveTaskReturnsSameDataDirPath() {
        // live 路径:任务运行中驻留内存(mgr.get 命中即活任务;SLOW 模型分片延迟 ~9s 保证窗口)。
        // 覆盖「目录在盘 或 内存任务可见」三选一里的内存分支(track 已登记 taskWorkspace,dirOf 不依赖 scan)。
        String taskId = create("SLOW:任务路径测试");
        long deadline = System.currentTimeMillis() + 10_000;
        while (mgr.get(taskId) == null) {
            assertTrue(System.currentTimeMillis() < deadline, "任务未进入内存运行: " + taskId);
            sleep(50);
        }

        RpcResp r = call("task.dataDir", "{\"taskId\":\"" + taskId + "\"}");
        assertFalse(r.isErr(), String.valueOf(r.err()));
        assertEquals(store.dirOf(taskId).toAbsolutePath().toString(),
                r.result().path("path").asString(""), "live 任务路径同源 TaskStore.dirOf");

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
                + "\",\"clientId\":\"fe-datadir\"}");
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
