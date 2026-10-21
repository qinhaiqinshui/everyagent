package dev.everyagent.worker;

import dev.everyagent.contract.frame.Frames;
import dev.everyagent.contract.ids.Ids;
import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.spi.TokenEstimator;
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
 * task.rounds 集成测试(开轮落盘 + rounds RPC + 旧任务惰性全量生成)。
 * 覆盖:不存在任务 NOT_FOUND / 缺 taskId BAD_PARAMS;
 * 旧任务(删除 rounds.jsonl 模拟)首次调用惰性全量生成且内容正确、二次调用直接读文件;
 * 取消任务(cancelled)由模型收尾 message 闭合(有最终回复即闭合;无收尾则保持开轮路径写入的 endSeq="" 行);
 * 运行中任务(SLOW 分片流式中 live,无插件依赖)open 正确且未闭合轮以 endSeq="" 落盘;
 * 续跑任务(rounds.jsonl 尾行未闭合 + 任务 live)open 由含锚点窗口重扫历史开轮(scanOpenRound 口径);
 * open 轮携带未闭合行 roundId(开轮即有、跨闭合稳定,旧行缺失 must-ignore 不携带)。
 * 复用 WorkerTaskPollTest 的测试基建(FakeHub + FakeChatModel + WsTestClient)。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class TaskRoundsRpcTest {

    /** 测试用 TokenEstimator 桩:factor 恒 1.0 的粗估桩(与原 ModelRateLimiter.estimateTokens 同口径)。 */
    private static final TokenEstimator STUB_ESTIMATOR = new TokenEstimator() {
        @Override public long estimate(String text, String configId) { return rawTokens(text); }
        private static long rawTokens(String s) {
            if (s == null || s.isEmpty()) { return 0; }
            long cjk = 0, other = 0;
            for (int i = 0; i < s.length(); ) {
                int cp = s.codePointAt(i);
                i += Character.charCount(cp);
                Character.UnicodeScript sc = Character.UnicodeScript.of(cp);
                boolean isCjk = sc == Character.UnicodeScript.HAN
                        || sc == Character.UnicodeScript.HIRAGANA
                        || sc == Character.UnicodeScript.KATAKANA
                        || sc == Character.UnicodeScript.HANGUL;
                if (isCjk) { cjk++; }
                else if (!Character.isWhitespace(cp) && !Character.isISOControl(cp)) { other++; }
            }
            return cjk + (other + 3) / 4;
        }
    };

    private static final String KEY = "test-key-rounds";
    private static final AtomicLong REQ = new AtomicLong();
    private static final Path WS = Path.of("target/test-workspace-rounds").toAbsolutePath().normalize();
    /** 系统目录(每次运行唯一,@AfterAll 统一清理)。 */
    private static final Path HOME_DIR = TestCleanup.register(
            Path.of("target/test-home-rounds-" + System.nanoTime()).toAbsolutePath().normalize());
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
                // 覆写带 events 的 4 参重载:buildAgentModel 普通模型路径实际分派到这里
                // (模型限流改造引入该路径后,仅覆写 3 参重载拦不到 agent 装配,
                // 任务会打到假 base-url 上 UnknownHostException)。
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
        r.add("worker.worker-id", () -> "test-worker-rounds");
        r.add("worker.hub-initial-backoff-ms", () -> "100");
        r.add("worker.hub-max-backoff-ms", () -> "300");
        r.add("worker.limits.max-concurrent-tasks", () -> "4");
        r.add("worker.limits.max-concurrent-subs", () -> "4");
        r.add("worker.limits.ask-timeout-ms", () -> "120000");
        r.add("worker.limits.sub-wait-timeout-ms", () -> "15000");
        r.add("worker.retry.max-request-retries", () -> "0");
        r.add("worker.home-dir", () -> HOME_DIR.toString());
        r.add("worker.workspace-root", () -> "target/test-workspace-rounds");
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
        RpcResp r = call("task.rounds", "{\"taskId\":\"t_nonexistent\"}");
        assertTrue(r.isErr(), "不存在任务应 NOT_FOUND");
        assertEquals("NOT_FOUND", r.err().path("code").asString());
        assertTrue(r.err().path("message").asString().contains("t_nonexistent"),
                "错误信息应含 taskId: " + r.err());
    }

    @Test
    void missingTaskIdIsBadParams() {
        RpcResp r = call("task.rounds", "{}");
        assertTrue(r.isErr(), "缺 taskId 应 BAD_PARAMS");
        assertEquals("BAD_PARAMS", r.err().path("code").asString());
    }

    @Test
    void oldTaskLazyGenerateThenReadFile() throws java.io.IOException {
        String taskId = create("你好,轮次测试");
        awaitEvicted(taskId); // finish 全部完成(flush/驱逐),磁盘为完整真相源
        Path roundsFile = store.dirOf(taskId).resolve("rounds.jsonl");
        assertTrue(Files.isRegularFile(roundsFile), "正常终态任务的 rounds.jsonl 应已存在(开轮落盘 + advisor 闭合)");

        // 模拟旧任务:删除 rounds.jsonl → 首次 task.rounds 惰性全量生成
        Files.delete(roundsFile);
        RpcResp first = call("task.rounds", "{\"taskId\":\"" + taskId + "\"}");
        assertFalse(first.isErr(), "首次调用应 ok: " + String.valueOf(first.err()));
        JsonNode rounds = first.result().path("rounds");
        assertEquals(1, rounds.size(), "应生成 1 轮: " + rounds);
        JsonNode r0 = rounds.get(0);
        assertEquals(1, r0.path("index").asLong());
        assertEquals("你好,轮次测试", r0.path("user").asString());
        assertTrue(r0.path("finalReply").asString().contains("回声"),
                "finalReply 应为 AI 最终回复: " + r0.path("finalReply").asString());
        assertTrue(r0.path("finalReply").asString().contains("你好"));
        // wire:seq 一律字符串;闭合轮 endSeq 为非空字符串且 > startSeq;agentRanges 恒数组
        assertTrue(r0.path("startSeq").isString(), "startSeq 应为字符串(wire 口径)");
        long startSeq = Long.parseLong(r0.path("startSeq").asString());
        long endSeq = Long.parseLong(r0.path("endSeq").asString());
        assertTrue(startSeq > 0 && endSeq > startSeq, "闭合轮 endSeq > startSeq");
        assertTrue(r0.path("agentRanges").isArray());
        assertTrue(r0.path("agentRanges").isEmpty(), "无子 agent: " + r0.path("agentRanges"));
        assertEquals("done", first.result().path("status").asString());
        assertFalse(first.result().path("live").asBoolean(false));
        assertTrue(first.result().path("open").isNull(), "终态 open=null");
        assertTrue(Files.isRegularFile(roundsFile), "惰性生成应落盘 rounds.jsonl");
        assertEquals(1, store.readRounds(store.dirOf(taskId)).size(), "落盘内容与应答一致");

        // 二次调用直接读文件:改写 rounds.jsonl 为单条特征行,应答应原样返回(不再重新扫描事件)
        Files.writeString(roundsFile, "{\"index\":7,\"startSeq\":\"7300000000000001\","
                + "\"endSeq\":\"\",\"user\":\"手动写入的轮\",\"finalReply\":\"\",\"subs\":[]}\n",
                StandardCharsets.UTF_8);
        RpcResp second = call("task.rounds", "{\"taskId\":\"" + taskId + "\"}");
        assertFalse(second.isErr(), String.valueOf(second.err()));
        JsonNode rounds2 = second.result().path("rounds");
        assertEquals(1, rounds2.size(), "二次调用应直接读文件: " + rounds2);
        assertEquals("手动写入的轮", rounds2.get(0).path("user").asString());
        assertEquals(7, rounds2.get(0).path("index").asLong());
        assertEquals("", rounds2.get(0).path("endSeq").asString(), "未闭合行原样读出");
    }

    @Test
    void cancelledTaskRoundClosedByModelSummaryReply() {
        String taskId = create("ASK:要继续吗");
        fe.await(t -> t.contains("\"event\":\"task.updated\"")
                && t.contains("\"status\":\"waiting-user\"") && t.contains(taskId), "等待用户输入(waiting-user)");
        RpcResp cancel = call("task.cancel", "{\"taskId\":\"" + taskId + "\"}");
        assertFalse(cancel.isErr(), "取消应 ok: " + String.valueOf(cancel.err()));
        awaitEvicted(taskId); // finish 全部完成(flush/驱逐),磁盘为完整真相源

        // 用户口径:cancel → ask 工具以"提问已被取消"作为 toolResult 返回 → 模型收尾一条
        // 「无工具调用且有正文」的 message("已确认:…")→ 该轮按口径闭合(有最终回复即闭合,
        // 与旧实现一致;若模型未收尾出正文,则该轮保持开轮路径写入的 endSeq="" 未闭合行)。
        RpcResp r = call("task.rounds", "{\"taskId\":\"" + taskId + "\"}");
        assertFalse(r.isErr(), String.valueOf(r.err()));
        JsonNode rounds = r.result().path("rounds");
        assertEquals(1, rounds.size(), "取消任务仍是一轮(中间无新输入): " + rounds);
        JsonNode last = rounds.get(rounds.size() - 1);
        assertTrue(last.path("startSeq").isString() && last.path("startSeq").asString().length() > 5);
        long startSeq = Long.parseLong(last.path("startSeq").asString());
        // 取消竞态两分支(上方注释口径):模型已收到取消 toolResult 并收尾 → 闭合,endSeq>startSeq
        // 且 finalReply=收尾正文;取消先停流、模型未收尾 → 保持开轮路径写入的 endSeq="" 未闭合行。
        String endSeqRaw = last.path("endSeq").asString();
        if (!endSeqRaw.isEmpty()) {
            long endSeq = Long.parseLong(endSeqRaw);
            assertTrue(endSeq > startSeq, "模型收尾 message 闭合该轮: " + last);
            assertEquals("ASK:要继续吗", last.path("user").asString());
            assertTrue(last.path("finalReply").asString().contains("已确认"),
                    "finalReply = 模型收尾正文: " + last.path("finalReply").asString());
        } else {
            assertEquals("ASK:要继续吗", last.path("user").asString());
            assertEquals("", last.path("finalReply").asString(), "未收尾分支无最终回复");
        }
        assertEquals("cancelled", r.result().path("status").asString());
        assertFalse(r.result().path("live").asBoolean(false));
        assertTrue(r.result().path("open").isNull(), "终态任务 open=null");
    }

    @Test
    void liveTaskOpenRoundPersistedAsUnclosedRow() throws java.io.IOException {
        // SLOW:分片延迟输出 ~9s(纯模型替身路径,无 ask-user 插件依赖)——流式期间任务 live,
        // 与 waiting-user 同为「运行中未闭合轮」场景;开轮行随 user.message 消费即落盘。
        String taskId = create("SLOW:慢速轮次");
        String userSeq = awaitUserMessageSeq(taskId);

        RpcResp r = awaitOpenRound(taskId);
        assertTrue(r.result().path("live").asBoolean(false), "流式中 live=true");
        // 开轮即落盘:运行中未闭合轮以 endSeq="" 行持久化(不再是终态补写)。
        JsonNode rounds = r.result().path("rounds");
        assertEquals(1, rounds.size(), "运行中未闭合轮已落盘为 1 行: " + rounds);
        assertEquals(userSeq, rounds.get(0).path("startSeq").asString());
        assertEquals("", rounds.get(0).path("endSeq").asString(), "未闭合轮 endSeq 为空");
        assertEquals("SLOW:慢速轮次", rounds.get(0).path("user").asString());
        JsonNode open = r.result().path("open");
        assertFalse(open.isNull(), "运行中仍有未闭合轮 open: " + r.result());
        assertEquals(userSeq, open.path("startSeq").asString(), "open.startSeq = 当前未闭合轮 user.message seq");
        assertEquals("SLOW:慢速轮次", open.path("user").asString());
        assertTrue(Files.isRegularFile(store.dirOf(taskId).resolve("rounds.jsonl")),
                "运行中未闭合轮已落盘(rounds.jsonl 应存在)");
        // open 轮 roundId:取自 rounds.jsonl 未闭合行(开轮落盘即生成),与 rounds[] 该行相同
        assertTrue(open.path("roundId").isTextual() && !open.path("roundId").asString().isBlank(),
                "open 轮应携带未闭合行 roundId: " + open);
        assertEquals(rounds.get(0).path("roundId").asString(), open.path("roundId").asString(),
                "open.roundId = rounds[] 未闭合行的 roundId(同一行)");
        String openRoundId = open.path("roundId").asString();

        awaitEvicted(taskId); // SLOW 终片最终回复闭合该轮 → 终态驱逐

        // 跨闭合稳定:闭合后重拉,该轮 roundId 与 open 轮相同(闭合改写沿用 prior.roundId,
        // 开轮生成一次不再变)
        RpcResp after = call("task.rounds", "{\"taskId\":\"" + taskId + "\"}");
        assertFalse(after.isErr(), String.valueOf(after.err()));
        JsonNode roundsAfter = after.result().path("rounds");
        assertEquals(1, roundsAfter.size(), "仍是一轮: " + roundsAfter);
        assertTrue(Long.parseLong(roundsAfter.get(0).path("endSeq").asString())
                > Long.parseLong(roundsAfter.get(0).path("startSeq").asString()),
                "SLOW 最终回复后该轮闭合: " + roundsAfter.get(0));
        assertEquals(openRoundId, roundsAfter.get(0).path("roundId").asString(),
                "闭合后 roundId 不变(开轮即有、跨闭合稳定)");
        assertTrue(after.result().path("open").isNull(), "终态 open=null");
    }

    @Test
    void resumedLiveTaskOpenRoundScannedFromUnclosedTailRow() throws java.io.IOException {
        String taskId = create("SLOW:续跑轮次");
        String userSeq = awaitUserMessageSeq(taskId);

        // 模拟「中断后续跑」任务的落盘状态:开轮路径已写未闭合尾行(endSeq=""),
        // 任务重新 live(冷启动续跑入队/等待输入),open 须由含锚点窗口重扫出历史开轮。
        // 预置行不带 roundId(旧行)→ open 不得携带 roundId(老数据 must-ignore)。
        Path roundsFile = store.dirOf(taskId).resolve("rounds.jsonl");
        Files.writeString(roundsFile, "{\"index\":1,\"startSeq\":\"" + userSeq + "\","
                + "\"endSeq\":\"\",\"user\":\"SLOW:续跑轮次\",\"finalReply\":\"\",\"subs\":[]}\n",
                StandardCharsets.UTF_8);

        RpcResp r = awaitOpenRound(taskId);
        assertTrue(r.result().path("live").asBoolean(false), "流式中 live=true");
        JsonNode open = r.result().path("open");
        assertFalse(open.isNull(), "未闭合尾行 + live:开轮 user.message(seq==anchor)须进窗口被重扫出: "
                + r.result());
        assertEquals(userSeq, open.path("startSeq").asString(),
                "open.startSeq = 未闭合尾行 startSeq(含锚点窗口重扫历史开轮)");
        assertEquals("SLOW:续跑轮次", open.path("user").asString(), "open.user = 开轮输入(非中间输入)");
        assertTrue(!open.has("roundId"),
                "预置旧行无 roundId 时 open 不携带该字段(老数据 must-ignore): " + open);
        // rounds 应答原样读文件:预置未闭合行在场;open 实时不落盘 → 文件行数不变
        JsonNode rounds = r.result().path("rounds");
        assertEquals(1, rounds.size(), "应答 rounds = 文件原样 1 行: " + rounds);
        assertEquals(userSeq, rounds.get(0).path("startSeq").asString());
        assertEquals("", rounds.get(0).path("endSeq").asString());
        assertEquals(1, Files.readAllLines(roundsFile, StandardCharsets.UTF_8).size(),
                "open 实时扫描不落盘,rounds.jsonl 行数不变");

        // 清理:取消并等待终态驱逐(finish 会把该未闭合行改判闭合,与本用例断言无冲突)
        RpcResp cancel = call("task.cancel", "{\"taskId\":\"" + taskId + "\"}");
        assertFalse(cancel.isErr(), String.valueOf(cancel.err()));
        awaitEvicted(taskId);
    }

    // ---- 返工:roundId wire ----

    @Test
    void roundsResponseCarriesRoundId() throws java.io.IOException {
        String taskId = create("你好,轮次测试");
        awaitEvicted(taskId); // finish 全部完成,磁盘为完整真相源
        Path dir = store.dirOf(taskId);

        // 新代码开轮即生成稳定 roundId:首次 task.rounds 应答的 rounds[] 应携带 roundId
        RpcResp first = call("task.rounds", "{\"taskId\":\"" + taskId + "\"}");
        assertFalse(first.isErr(), String.valueOf(first.err()));
        JsonNode r0 = first.result().path("rounds").get(0);
        assertTrue(r0.path("roundId").isTextual() && !r0.path("roundId").asString().isBlank(),
                "开轮生成的 roundId 应随 rounds 应答返回: " + r0);

        // 预置带 roundId 的一行 → 应答原样携带
        String rid = "round_seeded_1";
        String line = "{\"index\":1,\"startSeq\":\"" + r0.path("startSeq").asString()
                + "\",\"endSeq\":\"" + r0.path("endSeq").asString()
                + "\",\"roundId\":\"" + rid + "\","
                + "\"subs\":[]}\n";
        Files.writeString(dir.resolve("rounds.jsonl"), line, StandardCharsets.UTF_8);

        RpcResp second = call("task.rounds", "{\"taskId\":\"" + taskId + "\"}");
        assertFalse(second.isErr(), String.valueOf(second.err()));
        JsonNode seeded = second.result().path("rounds").get(0);
        assertEquals(rid, seeded.path("roundId").asString(), "roundId 随应答携带");
        assertEquals("done", second.result().path("status").asString());
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

    /** 轮询 events 模式直到 user.message 出现,返回其 wire seq(开轮行随消费即落盘)。 */
    private String awaitUserMessageSeq(String taskId) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            RpcResp all = call("task.poll", "{\"taskId\":\"" + taskId + "\",\"mode\":\"events\",\"afterSeq\":0}");
            assertFalse(all.isErr(), String.valueOf(all.err()));
            String seq = all.data().stream()
                    .filter(e -> "user.message".equals(e.path("event").asString()))
                    .map(e -> e.path("seq").asString()).findFirst().orElse("");
            if (!seq.isEmpty()) {
                return seq;
            }
            sleep(200);
        }
        throw new AssertionError("15s 内未见 user.message: " + taskId);
    }

    /**
     * 轮询 task.rounds 直到开轮行落盘且 open 出现(运行中任务实时扫描),返回该次应答。
     * 开轮行落盘与 user.message 可见之间存在毫秒级竞态,轮询吸收(直播窗口内必达)。
     */
    private RpcResp awaitOpenRound(String taskId) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            RpcResp r = call("task.rounds", "{\"taskId\":\"" + taskId + "\"}");
            assertFalse(r.isErr(), String.valueOf(r.err()));
            if (r.result().path("rounds").size() == 1 && !r.result().path("open").isNull()) {
                return r;
            }
            sleep(200);
        }
        throw new AssertionError("15s 内未见 open 轮(开轮行落盘): " + taskId);
    }

    private void hello(WsTestClient c) {
        c.send("{\"type\":\"hello\",\"ver\":" + Frames.PROTOCOL_VERSION
                + ",\"role\":\"frontend\",\"apiKey\":\"" + KEY
                + "\",\"clientId\":\"fe-rounds\"}");
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

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
