package dev.everyagent.worker;

import dev.everyagent.contract.frame.Frames;
import dev.everyagent.contract.ids.Ids;
import dev.everyagent.contract.json.Json;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.hub.HubPool;
import dev.everyagent.worker.network.NetworkToken;
import dev.everyagent.worker.modules.ConfigStore.ResolvedConfig;
import dev.everyagent.worker.proto.Channels;
import dev.everyagent.worker.slash.SlashTokenEncoder;
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
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2–M4 + 持久化/生命周期验收:任务创建→流式(瞬态)→落盘(持久)→task.poll 拉取→输入注入→
 * ask 全流程→取消→子 agent→幂等/并发上限→终态继续对话(冷启动)→用户删除。
 * 磁盘真相源:done 后内存驱逐,task.poll 全量必走 data/tasks/&lt;taskId&gt;/。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class WorkerIntegrationTest {

    private static final String KEY = "test-key-1";
    private static final AtomicLong REQ = new AtomicLong();
    /** 默认工作区(workspace-root 同值,task.run 新建必填 workspace 参数)。 */
    private static final java.nio.file.Path WS =
            java.nio.file.Path.of("target/test-workspace").toAbsolutePath().normalize();

    /** 预占固定端口:worker 的 hub URL 须在属性解析期就知道指向本应用 FakeHub。 */
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
                    // 按模型名分派(模型池测试):model-b 正常回声;model-fail 抛运行时错误;
                    // model-net 抛 IO 错误;其余(默认 gpt-4o-mini)保持 FakeChatModel 既有行为。
                    String model = cfg.snapshot().model();
                    if ("model-b".equals(model)) {
                        return new FakeChatModel();
                    }
                    if ("model-fail".equals(model)) {
                        return new FailChatModel();
                    }
                    if ("model-net".equals(model)) {
                        return new FailIoChatModel();
                    }
                    return new FakeChatModel();
                }
            };
        }

        /** chatClient @Bean 直接注入 ChatModel(架构 §5.2 ChatClient 装配),测试需提供 fake 实例。 */
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
        // hub-key 为 WorkerProperties 必填(既有契约漂移,WorkerTaskPollTest 已按新契约补);
        // FakeHub 不校验,占位即可
        r.add("worker.hubs[0].hub-key", () -> "test-hub-key");
        r.add("worker.worker-id", () -> "test-worker");
        r.add("worker.hub-initial-backoff-ms", () -> "100");
        r.add("worker.hub-max-backoff-ms", () -> "300");
        r.add("worker.limits.max-concurrent-tasks", () -> "2");
        r.add("worker.limits.max-concurrent-subs", () -> "2");
        r.add("worker.limits.ask-timeout-ms", () -> "120000");
        r.add("worker.limits.sub-wait-timeout-ms", () -> "15000");
        // 瞬时错误重试关到 0:模型池的 IO 错误测试无需退避(3s/15s/… 会拖爆用例),
        // FAIL: 用例本就立即失败不受影响
        r.add("worker.retry.max-request-retries", () -> "0");
        // 系统目录每次运行唯一:models.json 不落真实用户主目录,任务落盘也不跨运行串状态
        r.add("worker.home-dir", () -> "target/test-home-" + System.nanoTime());
        r.add("worker.workspace-root", () -> "target/test-workspace");
        // 模型配置(只读):直接经 Spring 属性注入 worker.models(模型池用例的故障/正常桩模型)。
        // 默认配置仍来自 application.yml 的 default 占位,FakeChatModel 分派只认这些池模型名。
        r.add("worker.models[0].config-id", () -> "pool-b");
        r.add("worker.models[0].name", () -> "模型B");
        r.add("worker.models[0].provider", () -> "openai-compat");
        r.add("worker.models[0].base-url", () -> "http://fake-b");
        r.add("worker.models[0].model", () -> "model-b");
        r.add("worker.models[0].api-key", () -> "sk-test");
        r.add("worker.models[1].config-id", () -> "pool-fail");
        r.add("worker.models[1].name", () -> "故障模型");
        r.add("worker.models[1].provider", () -> "openai-compat");
        r.add("worker.models[1].base-url", () -> "http://fake-fail");
        r.add("worker.models[1].model", () -> "model-fail");
        r.add("worker.models[1].api-key", () -> "sk-test");
        r.add("worker.models[2].config-id", () -> "pool-net");
        r.add("worker.models[2].name", () -> "断网模型");
        r.add("worker.models[2].provider", () -> "openai-compat");
        r.add("worker.models[2].base-url", () -> "http://fake-net");
        r.add("worker.models[2].model", () -> "model-net");
        r.add("worker.models[2].api-key", () -> "sk-test");
        // 池配置(provider=model-pool):model 用逗号分隔成员 configId,首个 = 主模型。
        r.add("worker.models[3].config-id", () -> "pool-main");
        r.add("worker.models[3].name", () -> "容灾池");
        r.add("worker.models[3].provider", () -> "model-pool");
        r.add("worker.models[3].model", () -> "pool-fail,pool-b");
        r.add("worker.models[4].config-id", () -> "pool-net-pool");
        r.add("worker.models[4].name", () -> "断网池");
        r.add("worker.models[4].provider", () -> "model-pool");
        r.add("worker.models[4].model", () -> "pool-net,pool-b");
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

    // ---- 用例 ----

    @Test
    void taskLifecycleTransientLiveAndPersistentDisk() {
        String taskId = create("THINK:你好,任务一");
        assertTrue(taskId.startsWith("t_"), "短 ID: " + taskId);

        // 瞬态事件纯拉取(不落盘):THINK 脚本 150ms×2 出 thinking —— 任务在跑时 task.poll
        // (磁盘 ∪ 内存 EventLog 归并)实时可读到瞬态 thinking;瞬态 delta 与权威 message 共享
        // 轮 seq,TaskManager 按 seq 归并后同 seq 以 message 定稿,故 delta 单帧经 task.poll
        // 不可逐帧拉取属正常,由下方「message 携带整轮 thinking + 磁盘无瞬态行」断言兜底。
        syncUntil(taskId, e -> e.path("event").asString().equals("thinking"), "实时 thinking(内存缓冲)");
        fe.await(t -> t.contains("\"event\":\"task.updated\"") && t.contains("\"status\":\"done\"")
                && t.contains(taskId), "任务终态 done");

        // done 后内存驱逐,sync = 纯磁盘回放:只有持久事件,seq 有洞(瞬态占 seq 不落盘)
        // 耗时不再发 task_duration trace,改为回填 rounds.jsonl(由 TaskRoundsRpcTest 覆盖)。
        List<JsonNode> events = sync(taskId, 0);
        List<String> names = events.stream().map(e -> e.path("event").asString()).toList();
        assertEquals(List.of("agent.status", "user.message", "message", "usage", "agent.status"),
                names,
                "磁盘只留持久事件(agent.status 主 agent 开跑/终态,无耗时 trace): "
                        + names);
        List<Long> seqs = events.stream().map(e -> e.path("seq").asLong()).toList();
        // seq 为雪花 ID(非自增从 1 起):只断言为正与严格递增;瞬态占 seq 不落盘 → 磁盘回放 seq 有洞合法。
        assertTrue(seqs.get(0) > 0, "seq 为正(雪花 ID): " + seqs);
        for (int i = 1; i < seqs.size(); i++) {
            assertTrue(seqs.get(i) > seqs.get(i - 1), "seq 严格递增(洞合法)");
        }
        JsonNode message = events.get(names.indexOf("message"));
        assertEquals("思考完成", message.path("payload").path("thinking").asString(),
                "message 行携带整轮 thinking");
        assertTrue(message.path("payload").path("text").asString().contains("回声:"),
                "message 行携带完整正文");
        assertNull(message.path("payload").path("agentId").asString(null), "主 agent wire 无 agentId");
        JsonNode usageEv = events.stream().filter(e -> e.path("event").asString().equals("usage")).findFirst().orElse(null);
        assertTrue(usageEv != null && usageEv.path("payload").has("total"), "usage 事件带 total 用量");

        // 磁盘布局:按 agent 分文件;每行带 agentId,无 ext null 字段。
        // 注意:瞬态事件(如 thinking/delta)占 seq 但以短占位行 {"seq":N} 落盘(续号用,<30 字符),
        // 读侧(readEvents)跳过短行 → 回放只见完整事件;故"完整事件行数 == 回放数",而非"总行数 == 回放数"。
        JsonNode meta = readMeta(taskId);
        String mainAgentId = meta.path("mainAgentId").asString();
        assertTrue(mainAgentId.startsWith("a_"), mainAgentId);
        java.nio.file.Path dir = workerProps.resolveDataDir().resolve("tasks").resolve(taskId);
        List<String> lines;
        try {
            lines = Files.readAllLines(dir.resolve(mainAgentId + ".jsonl"));
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        long fullLines = lines.stream().filter(l -> l.length() >= 30).count();
        assertEquals(names.size(), fullLines,
                "完整事件行数与回放一致(瞬态占位行被读侧跳过): " + names);
        for (String line : lines) {
            if (line.length() < 30) {
                continue; // 瞬态占位行 {"seq":N} 仅续号用,无 agentId/ext(豁免)
            }
            assertTrue(line.contains("\"agentId\":\"" + mainAgentId + "\""), "每行记 agentId: " + line);
            assertFalse(line.contains("\"ext\""), "ext null 不写行: " + line);
        }
    }

    @Test
    void askUserFullFlow() {
        String taskId = create("请确认 ASK:要继续吗");

        // FakeChatModel 即时触发工具 —— 按 G3 用 task.poll 轮询任务历史
        JsonNode askCreate = syncUntil(taskId,
                e -> e.path("event").asString().equals("ask.create"), "ask.create");
        String askId = askCreate.path("payload").path("askId").asString();
        assertTrue(askId.startsWith("q_"), askId);
        // 多问题形态:ask.create 透传 questions 数组(含 worker 分配的 id)
        JsonNode questions = askCreate.path("payload").path("questions");
        assertTrue(questions.isArray() && questions.size() == 1, "ask.create 含 questions: " + questions);
        assertTrue(questions.path(0).path("id").asString().startsWith(askId + "_"),
                "子问题 id 与 askId 关联: " + questions);
        fe.await(t -> t.contains("\"event\":\"task.updated\"") && t.contains("\"status\":\"waiting-user\""),
                "状态 waiting-user");

        // 模拟前端折叠:answer 已含「题干：答案」(ask_user 工具结果写问题+答案)
        fe.send(pub(Channels.workerInput(k, workerProps.getWorkerId()), "ask.reply",
                "{\"askId\":\"" + askId + "\",\"answer\":\"要继续吗：是\"}"));
        syncUntil(taskId, e -> e.path("event").asString().equals("ask.resolved"), "ask.resolved 落盘");
        fe.await(t -> t.contains("\"event\":\"task.updated\"") && t.contains("\"status\":\"done\""),
                "恢复后完成");
        List<JsonNode> events = sync(taskId, 0);
        List<String> names = events.stream().map(e -> e.path("event").asString()).toList();
        assertTrue(names.contains("tool.result"), names.toString());
        assertTrue(names.contains("ask.resolved"), names.toString());
        // 工具轮:message 带真实 toolCall id,tool.result 按 callId 配对
        JsonNode callMsg = events.stream().filter(e -> e.path("event").asString().equals("message"))
                .findFirst().orElseThrow();
        String callId = callMsg.path("payload").path("toolCalls").path(0).path("id").asString();
        JsonNode toolResult = events.stream().filter(e -> e.path("event").asString().equals("tool.result"))
                .findFirst().orElseThrow();
        assertEquals(callId, toolResult.path("payload").path("callId").asString(), "callId 配对");
        JsonNode answerMsg = events.stream()
                .filter(e -> e.path("event").asString().equals("message") && !e.path("payload").has("toolCalls"))
                .findFirst().orElseThrow();
        assertTrue(answerMsg.path("payload").path("text").asString().contains("已确认"),
                "工具结果汇总进终答: " + answerMsg);
        // 工具结果本身写回了「题干：答案」(经 tool.result summary → 模型上下文)
        assertTrue(answerMsg.path("payload").path("text").asString().contains("要继续吗：是"),
                "工具结果含问题+答案: " + answerMsg);
    }

    @Test
    void cancelMidStream() {
        String taskId = create("SLOW:慢速任务");
        // SLOW 脚本首片 1200ms 后出:task.poll 拉到首片 delta = 任务在跑,可安全取消
        syncUntil(taskId, e -> e.path("event").asString().equals("delta"), "首片 delta");

        String resp = rpc("task.cancel", "{\"taskId\":\"" + taskId + "\"}");
        assertTrue(resp.contains("\"status\":\"cancelling\""), resp);
        syncUntil(taskId, e -> e.path("event").asString().equals("cancelled"), "cancelled 事件落盘");
        fe.await(t -> t.contains("\"event\":\"task.updated\"") && t.contains("\"status\":\"cancelled\""),
                "终态 cancelled");
        assertTrue(sync(taskId, 0).stream().anyMatch(e -> e.path("event").asString().equals("cancelled")),
                "cancelled 事件落盘");
    }

    @Test
    void inputInjectedBetweenTurns() {
        String taskId = create("SLOW:第一轮");
        syncUntil(taskId, e -> e.path("event").asString().equals("delta"), "首片 delta");
        fe.send(pub(Channels.workerInput(k, workerProps.getWorkerId()), "task.input",
                "{\"taskId\":\"" + taskId + "\",\"text\":\"第二轮输入\"}"));
        fe.await(t -> t.contains("\"event\":\"task.updated\"") && t.contains("\"status\":\"done\""),
                "两轮后完成");
        List<JsonNode> events = sync(taskId, 0);
        long userMsgs = events.stream().filter(e -> e.path("event").asString().equals("user.message")).count();
        assertEquals(2, userMsgs, "第二条输入必须注入为第二轮 user.message");
    }

    @Test
    void subAgentFlow() {
        String taskId = create("SUB:帮我查资料");
        JsonNode subStarted = syncUntil(taskId,
                e -> e.path("event").asString().equals("agent.started"), "agent.started");
        String subId = subStarted.path("payload").path("agentId").asString();
        assertTrue(subId.startsWith("sub_"), subId);
        // 子 agent 权威输出 message(与主同名,靠 payload.agentId 区分归属;瞬态 delta 在快子
        // 任务下竞态不可靠,以落盘权威定稿断言——最终 sync 块再核对配对/磁盘布局)
        syncUntil(taskId, e -> e.path("event").asString().equals("message")
                        && subId.equals(e.path("payload").path("agentId").asString()),
                "子 agent message(带 agentId)");
        fe.await(t -> t.contains("\"event\":\"task.updated\"") && t.contains("\"status\":\"done\""),
                "收口后完成");
        List<JsonNode> events = sync(taskId, 0);
        List<String> names = events.stream().map(e -> e.path("event").asString()).toList();
        assertTrue(names.contains("agent.done"), names.toString());
        // run_agent 下发:主 message.toolCalls + tool.result 配对
        JsonNode runAgentMsg = events.stream().filter(e -> e.path("event").asString().equals("message")
                && e.path("payload").path("toolCalls").path(0).path("name").asString().equals("run_agent"))
                .findFirst().orElse(null);
        assertNotNull(runAgentMsg, "主 message 带 run_agent 工具调用下发");
        String callId = runAgentMsg.path("payload").path("toolCalls").path(0).path("id").asString();
        JsonNode subDone = events.stream()
                .filter(e -> e.path("event").asString().equals("agent.done")).findFirst().orElseThrow();
        assertEquals(subId, subDone.path("payload").path("agentId").asString());
        JsonNode subMsg = events.stream()
                .filter(e -> e.path("event").asString().equals("message")
                        && e.path("payload").path("agentId").asString().equals(subId)).findFirst().orElseThrow();
        assertEquals(subId, subMsg.path("payload").path("agentId").asString(), "子事件与主事件同名,靠 agentId 区分");
        JsonNode toolResult = events.stream().filter(e -> e.path("event").asString().equals("tool.result"))
                .findFirst().orElseThrow();
        assertEquals(callId, toolResult.path("payload").path("callId").asString(), "run_agent 结果配对");
        // 磁盘:子 agent 独立文件
        JsonNode meta = readMeta(taskId);
        java.nio.file.Path dir = workerProps.resolveDataDir().resolve("tasks").resolve(taskId);
        assertTrue(Files.isRegularFile(dir.resolve(subId + ".jsonl")), "子 agent 分文件: " + dir);
        assertTrue(Files.isRegularFile(dir.resolve(meta.path("mainAgentId").asString() + ".jsonl")));
    }

    @Test
    void modelErrorFailsTask() {
        String taskId = create("FAIL:模拟故障");
        syncUntil(taskId, e -> e.path("event").asString().equals("error"), "error");
        fe.await(t -> t.contains("\"event\":\"task.updated\"") && t.contains("\"status\":\"failed\""),
                "终态 failed");
        assertTrue(sync(taskId, 0).stream().anyMatch(e -> e.path("event").asString().equals("error")));
    }

    @Test
    void idempotencyAndBusy() {
        String busyId = create("SLOW:占位任务", "idem-busy");
        syncUntil(busyId, e -> e.path("event").asString().equals("delta"), "首片 delta");

        String again = rpc("task.run", Json.write(Json.obj()
                .put("input", "SLOW:占位任务").put("idempotencyKey", "idem-busy")
                .put("workspace", WS.toString())));
        assertTrue(again.contains(busyId), "同幂等键应返回原任务: " + again);
        assertTrue(again.contains("\"deduplicated\":true"), again);

        String second = create("SLOW:第二个任务", "idem-two"); // 占满 2 并发
        String third = rpc("task.run", "{\"input\":\"第三个任务\"}");
        assertTrue(third.contains("BUSY"), "超过并发上限应 BUSY: " + third);

        rpc("task.cancel", "{\"taskId\":\"" + busyId + "\"}");
        rpc("task.cancel", "{\"taskId\":\"" + second + "\"}");
    }

    @Test
    void workspaceIsolationAndDiskPersistence() {
        java.nio.file.Path wsA = java.nio.file.Path.of("target/test-ws-a-" + System.nanoTime())
                .toAbsolutePath().normalize();
        java.nio.file.Path wsB = java.nio.file.Path.of("target/test-ws-b-" + System.nanoTime())
                .toAbsolutePath().normalize();
        // 无 workspace → BAD_PARAMS(多工作区并行后隐式全局态是竞态源)
        assertTrue(rpc("task.run", "{\"input\":\"无工作区\"}").contains("BAD_PARAMS"));

        String ta = create("WSA:A 的任务", null, wsA.toString());
        String tb = create("WSB:B 的任务", null, wsB.toString());
        fe.await(t -> t.contains("\"event\":\"task.updated\"") && t.contains("\"status\":\"done\"")
                && t.contains(ta), "A 任务完成");
        fe.await(t -> t.contains("\"event\":\"task.updated\"") && t.contains("\"status\":\"done\"")
                && t.contains(tb), "B 任务完成");

        String listA = rpc("tasks.list", Json.write(Json.obj().put("workspace", wsA.toString())));
        assertTrue(listA.contains(ta) && !listA.contains(tb), "A 过滤只见 A: " + listA);
        String listB = rpc("tasks.list", Json.write(Json.obj().put("workspace", wsB.toString())));
        assertTrue(listB.contains(tb) && !listB.contains(ta), "B 过滤只见 B: " + listB);
        String all = rpc("tasks.list", "{}");
        assertTrue(all.contains(ta) && all.contains(tb), "无过滤见全部: " + all);

        // 摘要带 workspace;任务目录住 data/tasks/<taskId>,工作区目录内无任务数据
        boolean found = false;
        for (JsonNode taskNode : Json.parse(all).path("payload").path("result").path("tasks")) {
            if (ta.equals(taskNode.path("taskId").asString())) {
                assertEquals(wsA.toString(), taskNode.path("workspace").asString(),
                        "摘要带 workspace 字段: " + taskNode);
                found = true;
            }
        }
        assertTrue(found, "列表包含任务 A: " + all);
        java.nio.file.Path dirA = workerProps.resolveDataDir().resolve("tasks").resolve(ta);
        assertTrue(Files.isRegularFile(dirA.resolve("meta.json")),
                "meta.json 落系统目录: " + dirA);
        assertTrue(Files.isRegularFile(dirA.resolve(readMeta(ta).path("mainAgentId").asString() + ".jsonl")),
                "主 agent 分文件落系统目录");
        assertFalse(Files.exists(wsA.resolve("meta.json")), "工作区目录内无任务数据");
        String wsList = rpc("workspaces.list", "{}");
        assertFalse(wsList.contains("wsKey"), "注册表不再有 wsKey: " + wsList);

        // 双工作区任务磁盘回放完整(终态即驱逐,sync 必走磁盘)
        List<JsonNode> eventsA = sync(ta, 0);
        assertFalse(eventsA.isEmpty());
        List<Long> seqs = eventsA.stream().map(e -> e.path("seq").asLong()).toList();
        for (int i = 1; i < seqs.size(); i++) {
            assertTrue(seqs.get(i) > seqs.get(i - 1), "A 任务磁盘回放 seq 有序");
        }
    }

    @Test
    void terminalTaskInputRerunsWithContext() {
        String taskId = create("第一轮问候");
        fe.await(t -> t.contains("\"event\":\"task.updated\"") && t.contains("\"status\":\"done\"")
                && t.contains(taskId), "首轮 done");
        long createdAt = readMeta(taskId).path("createdAt").asLong();
        long firstRunLastSeq = sync(taskId, 0).getLast().path("seq").asLong();

        // 终态直接发输入 = 冷启动再运行(无续跑概念,状态自动翻回)
        fe.send(pub(Channels.workerInput(k, workerProps.getWorkerId()), "task.input",
                "{\"taskId\":\"" + taskId + "\",\"text\":\"THINK:第二轮追问\"}"));
        syncUntil(taskId, e -> e.path("event").asString().equals("user.message")
                && e.path("payload").path("text").asString().contains("第二轮追问"), "第二轮 user.message");
        // THINK 脚本带 150ms×2 延迟:等第二轮末条 message 落盘再全量断言(避免读到运行中间态)
        syncUntil(taskId, e -> e.path("event").asString().equals("message")
                && e.path("seq").asLong() > firstRunLastSeq, "第二轮 message");

        List<JsonNode> events = sync(taskId, 0);
        List<String> names = events.stream().map(e -> e.path("event").asString()).toList();
        assertEquals(2, names.stream().filter("user.message"::equals).count(), "两轮输入");
        assertEquals(2, names.stream().filter("message"::equals).count(), "两条权威消息");
        List<Long> seqs = events.stream().map(e -> e.path("seq").asLong()).toList();
        for (int i = 1; i < seqs.size(); i++) {
            assertTrue(seqs.get(i) > seqs.get(i - 1), "seq 跨运行连续递增");
        }
        // 冷启动上下文:第二轮回答携带历史(FakeChatModel 按会话 user 数打标)
        JsonNode secondAnswer = events.stream()
                .filter(e -> e.path("event").asString().equals("message"))
                .reduce((first, second) -> second).orElseThrow();
        assertTrue(secondAnswer.path("payload").path("text").asString().contains("上下文2:"),
                "二次回答含上文: " + secondAnswer.path("payload").path("text").asString());
        assertEquals("思考完成", secondAnswer.path("payload").path("thinking").asString(),
                "再运行 message 仍带整轮 thinking");
        // createdAt 不随续写重置;mainAgentId 稳定(同一 jsonl 文件续写)
        JsonNode meta2 = readMeta(taskId);
        assertEquals(createdAt, meta2.path("createdAt").asLong(), "createdAt 沿用原值");
        java.nio.file.Path dir = workerProps.resolveDataDir().resolve("tasks").resolve(taskId);
        try (var files = Files.list(dir)) {
            // rounds.jsonl 是轮次索引(§5.8),与事件日志分开,不计入
            assertEquals(1, files.filter(p -> {
                String n = p.getFileName().toString();
                return n.endsWith(".jsonl") && !n.equals("rounds.jsonl");
            }).count(), "同 mainAgentId 续写同一文件");
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        // 磁盘无瞬态行
        try {
            for (String line : Files.readAllLines(dir.resolve(meta2.path("mainAgentId").asString() + ".jsonl"))) {
                assertFalse(line.contains("\"event\":\"delta\"") || line.contains("\"event\":\"thinking\""),
                        "磁盘无瞬态行: " + line);
            }
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void taskRunWithTaskIdRerunsTerminalTask() {
        String taskId = create("第一轮问候");
        fe.await(t -> t.contains("\"event\":\"task.updated\"") && t.contains("\"status\":\"done\"")
                && t.contains(taskId), "首轮 done");
        long createdAt = readMeta(taskId).path("createdAt").asLong();
        long firstRunLastSeq = sync(taskId, 0).getLast().path("seq").asLong();

        // 终态 task.run{taskId} = 同 taskId 冷启动续跑(不借 task.input)
        String resp = rpc("task.run", Json.write(Json.obj()
                .put("taskId", taskId).put("input", "THINK:第二轮追问")));
        assertTrue(resp.contains("rpc.ok") && resp.contains(taskId), resp);
        syncUntil(taskId, e -> e.path("event").asString().equals("message")
                && e.path("seq").asLong() > firstRunLastSeq, "第二轮 message");

        List<JsonNode> events = sync(taskId, 0);
        assertEquals(2, events.stream()
                .filter(e -> e.path("event").asString().equals("user.message")).count(), "两轮输入");
        JsonNode secondAnswer = events.stream()
                .filter(e -> e.path("event").asString().equals("message"))
                .reduce((first, second) -> second).orElseThrow();
        assertTrue(secondAnswer.path("payload").path("text").asString().contains("上下文2:"),
                "续跑载入历史: " + secondAnswer.path("payload").path("text").asString());
        assertEquals(createdAt, readMeta(taskId).path("createdAt").asLong(), "createdAt 沿用原值");
    }

    @Test
    void taskRunUnknownTaskIsNotFound() {
        String resp = rpc("task.run", Json.write(Json.obj()
                .put("taskId", "t_nope").put("input", "hello")));
        assertTrue(resp.contains("NOT_FOUND"), resp);
    }

    @Test
    void taskRunOnRunningTaskEnqueuesAndQueueVisible() {
        String taskId = create("SLOW:慢速占位");
        syncUntil(taskId, e -> e.path("event").asString().equals("delta"),
                "首片 delta(慢速脚本 ~9s,窗口足够)");

        // 运行中 task.run{taskId} = 入队(前端镜像以为终态但 worker 已在跑的竞态兜底)
        String resp = rpc("task.run", Json.write(Json.obj()
                .put("taskId", taskId).put("input", "queue-one")));
        assertTrue(resp.contains("\"queued\":true"), resp);
        fe.await(t -> t.contains("\"event\":\"task.updated\"")
                && t.contains("\"pendingInputs\":[\"queue-one\"]"), "入队即广播 pendingInputs");

        // task.input 频道同路径再入一条
        fe.send(pub(Channels.workerInput(k, workerProps.getWorkerId()), "task.input",
                "{\"taskId\":\"" + taskId + "\",\"text\":\"queue-two\"}"));
        fe.await(t -> t.contains("\"event\":\"task.updated\"")
                && t.contains("\"pendingInputs\":[\"queue-one\",\"queue-two\"]"), "两条快照");

        // 刷新重连可见:tasks.list 内存行与 task.updated 均带队列快照
        assertTrue(rpc("tasks.list", "{}").contains("\"pendingInputs\""), "tasks.list 带队列");

        // 消费:队列逐条缩短(user.message 同拍广播),终态清空不残留
        fe.await(t -> t.contains("\"event\":\"task.updated\"")
                && t.contains("\"pendingInputs\":[\"queue-two\"]"), "消费一条少一条");
        fe.await(t -> t.contains("\"event\":\"task.updated\"") && t.contains("\"pendingInputs\":[]")
                && t.contains(taskId), "队列清空");
        fe.await(t -> t.contains("\"event\":\"task.updated\"") && t.contains("\"status\":\"done\"")
                && t.contains(taskId), "两轮后完成");
        assertEquals(3, sync(taskId, 0).stream()
                .filter(e -> e.path("event").asString().equals("user.message")).count(),
                "初始 + 两条队列输入");
        assertFalse(rpc("tasks.list", "{}").contains("queue-one"), "终态后队列不残留");
    }

    @Test
    void failedTaskInputReruns() {
        String taskId = create("FAIL:模拟故障");
        syncUntil(taskId, e -> e.path("event").asString().equals("error"), "首轮失败");
        fe.await(t -> t.contains("\"event\":\"task.updated\"") && t.contains("\"status\":\"failed\""),
                "终态 failed");

        fe.send(pub(Channels.workerInput(k, workerProps.getWorkerId()), "task.input",
                "{\"taskId\":\"" + taskId + "\",\"text\":\"再试一次\"}"));
        fe.await(t -> t.contains("\"event\":\"task.updated\"") && t.contains("\"status\":\"done\"")
                && t.contains(taskId), "二次运行完成");
        List<JsonNode> events = sync(taskId, 0);
        List<String> names = events.stream().map(e -> e.path("event").asString()).toList();
        assertEquals(2, names.stream().filter("user.message"::equals).count());
        JsonNode answer = events.stream()
                .filter(e -> e.path("event").asString().equals("message")).findFirst().orElseThrow();
        assertTrue(answer.path("payload").path("text").asString().contains("上下文2:"),
                "失败任务续问同样携带历史: " + answer.path("payload").path("text").asString());
    }

    @Test
    void deleteTaskRemovesDirAndBlocksRerun() {
        String taskId = create("待删除任务");
        fe.await(t -> t.contains("\"event\":\"task.updated\"") && t.contains("\"status\":\"done\""),
                "任务完成");
        java.nio.file.Path dir = workerProps.resolveDataDir().resolve("tasks").resolve(taskId);
        assertTrue(Files.isDirectory(dir));

        String resp = rpc("task.delete", "{\"taskId\":\"" + taskId + "\"}");
        assertTrue(resp.contains("\"deleted\":true"), resp);
        fe.await(t -> t.contains("\"event\":\"task.deleted\"") && t.contains(taskId), "task.deleted 通知");
        assertFalse(Files.exists(dir), "目录整体删除(用户删除是唯一出口)");
        assertFalse(rpc("tasks.list", "{}").contains(taskId), "列表不再包含");
        assertFalse(Files.exists(dir), "目录已删(sync 语义由磁盘目录承载)");
        // 删除后 input 不再触发运行(fire-and-forget,warn 丢弃)
        fe.send(pub(Channels.workerInput(k, workerProps.getWorkerId()), "task.input",
                "{\"taskId\":\"" + taskId + "\",\"text\":\"复活?\"}"));
        sleep(800);
        assertFalse(Files.exists(dir), "已删任务不复活(目录不重建)");
    }

    @Test
    void deleteRunningTaskRejected() {
        String taskId = create("SLOW:运行中删除");
        syncUntil(taskId, e -> e.path("event").asString().equals("delta"), "首片 delta");
        assertTrue(rpc("task.delete", "{\"taskId\":\"" + taskId + "\"}").contains("BAD_PARAMS"),
                "运行中删除被拒");
        rpc("task.cancel", "{\"taskId\":\"" + taskId + "\"}");
    }

    @Test
    void sysAndListMethods() {
        String methods = rpc("sys.methods", "{}");
        assertTrue(methods.contains("task.run"), methods);
        assertTrue(methods.contains("task.delete"), methods);
        assertTrue(methods.contains("fs.list") && methods.contains("git.status"), methods);
        String info = rpc("sys.info", "{}");
        assertTrue(info.contains("test-worker") && info.contains("\"hubConnected\":true"), info);

        String taskId = create("列表里的任务");
        fe.await(t -> t.contains("\"event\":\"task.updated\"") && t.contains("\"status\":\"done\""),
                "任务完成");
        String list = rpc("tasks.list", "{}");
        assertTrue(list.contains(taskId), list);
    }

    @Test
    void tasksListPaginationAndTaskIdsFilter() {
        // 逐个创建并等终态(并发上限 2):保持磁盘时序递增,「最近活跃」排序可预期。
        int n = 13;
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String id = create("分页任务 " + i);
            ids.add(id);
            fe.await(t -> t.contains("\"event\":\"task.updated\"") && t.contains("\"status\":\"done\"")
                    && t.contains(id), "任务完成 " + i);
        }

        // 第一页:最近 10 个,hasMore=true
        String page1 = rpc("tasks.list", Json.write(Json.obj().put("limit", 10).put("offset", 0)));
        JsonNode res1 = Json.parse(page1).path("payload").path("result");
        assertEquals(13, res1.path("total").asLong(), "total: " + page1);
        assertTrue(res1.path("hasMore").asBoolean(), "首页有更多: " + page1);
        assertEquals(10, res1.path("tasks").size(), "首页 10 条: " + page1);
        List<String> page1Ids = new ArrayList<>();
        for (JsonNode t : res1.path("tasks")) {
            page1Ids.add(t.path("taskId").asString());
        }
        assertEquals(ids.get(n - 1), page1Ids.get(0), "最新任务在首页首位: " + page1Ids);
        assertTrue(page1Ids.containsAll(ids.subList(3, n)), "首页含最近 10 个: " + page1Ids);

        // 第二页:offset=10 续拉,只剩 3 个,hasMore=false,且与首页无重叠
        String page2 = rpc("tasks.list", Json.write(Json.obj().put("limit", 10).put("offset", 10)));
        JsonNode res2 = Json.parse(page2).path("payload").path("result");
        assertEquals(3, res2.path("tasks").size(), "第二页 3 条: " + page2);
        assertFalse(res2.path("hasMore").asBoolean(), "第二页拉完: " + page2);
        List<String> page2Ids = new ArrayList<>();
        for (JsonNode t : res2.path("tasks")) {
            page2Ids.add(t.path("taskId").asString());
        }
        assertTrue(page2Ids.containsAll(ids.subList(0, 3)), "第二页含最早 3 个: " + page2Ids);
        for (String id : page1Ids) {
            assertFalse(page2Ids.contains(id), "分页不重叠: " + id);
        }

        // 定向拉取(分页窗口外老任务 / ensureLoaded 兜底):taskIds 过滤命中
        String targeted = rpc("tasks.list", Json.write(Json.obj()
                .set("taskIds", Json.arr().add(ids.get(0)))
                .put("limit", 1)));
        JsonNode res3 = Json.parse(targeted).path("payload").path("result");
        assertEquals(1, res3.path("tasks").size(), "定向拉取 1 条: " + targeted);
        assertEquals(ids.get(0), res3.path("tasks").path(0).path("taskId").asString(),
                "定向拉取命中: " + targeted);
        assertEquals(1, res3.path("total").asLong(), "定向 total: " + targeted);
    }

    @Test
    void configGetMasksApiKey() {
        String resp = rpc("config.get", "{}");
        assertTrue(resp.contains("models"), resp);
        assertFalse(resp.contains("\"apiKey\":\"sk-"), "apiKey 不得明文回传: " + resp);
    }

    // ---- 模型池(provider: model-pool 配置) ----

    /**
     * 池配置默认容灾:新建任务直接选池配置(configId=pool-main, model="pool-fail,pool-b"),
     * 无需 slash 开关——首成员 pool-fail 抛运行时错误 → 自动切 pool-b 回声成功;
     * 第二轮再运行任务仍指向池配置,容灾自动保持。
     */
    @Test
    void modelPoolConfigDefaultFailover() {
        String taskId = createWithConfig("你好,容灾", "pool-main");
        JsonNode msg = syncUntil(taskId,
                e -> "message".equals(e.path("event").asString())
                        && e.path("payload").path("text").asString().contains("回声:"),
                "容灾后模型B 应答");
        assertTrue(msg.path("payload").path("text").asString().contains("你好,容灾"),
                "回声带原文: " + msg);
        // 旧任务级开关已移除:meta 不应再有 modelPoolFailover 字段
        assertFalse(readMeta(taskId).has("modelPoolFailover"),
                "meta 不应再有 modelPoolFailover 字段: " + readMeta(taskId));

        // 第二轮:再运行 → 任务仍指向池配置 → 容灾自动保持(无需任何 slash token)
        String again = rpc("task.run", Json.write(Json.obj()
                .put("taskId", taskId)
                .put("input", "第二问,容灾自动保持")
                .put("configId", "pool-main")
                .put("workspace", WS.toString())));
        assertTrue(again.contains("rpc.ok"), "再运行失败: " + again);
        JsonNode msg2 = syncUntil(taskId, e -> "message".equals(e.path("event").asString())
                        && e.path("payload").path("text").asString().contains("回声:")
                        && e.path("payload").path("text").asString().contains("第二问,容灾自动保持"),
                "第二轮容灾自动保持应答");
        assertTrue(msg2.path("payload").path("text").asString().contains("第二问,容灾自动保持"),
                "第二轮回声带原文: " + msg2);
    }

    /**
     * 网络异常不介入:池配置 pool-net-pool=[pool-net(断网), pool-b(正常)],首成员抛
     * IOException 后不得切下一成员,任务直接失败且无任何回声(换模型救不了断网)。
     */
    @Test
    void modelPoolConfigSkipsNetworkError() {
        // pool-net 故障 + pool-b 正常(池内存在正常模型,若误切换会成功回声——断言不切换)

        String taskId = createWithConfig("断网测试", "pool-net-pool");
        fe.await(t -> t.contains("\"event\":\"task.updated\"") && t.contains("\"status\":\"failed\"")
                && t.contains(taskId), "网络错误任务失败");
        JsonNode err = syncUntil(taskId, e -> "error".equals(e.path("event").asString()), "网络错误事件");
        assertTrue(err.path("payload").path("message").asString().contains("模拟网络中断"),
                "网络异常原样上抛不切换: " + err);
        List<JsonNode> events = sync(taskId, 0);
        assertTrue(events.stream()
                        .noneMatch(e -> "message".equals(e.path("event").asString())
                                && e.path("payload").path("text").asString().contains("回声:")),
                "网络错误不得切换到池内模型(不得出现回声)");
    }

    @Test
    void slashTaskTokensApplyCancelRoundTrip() {
        // 1. slash.list 应含 network:on 条目(入口存在)
        String list = rpc("slash.list", "{}");
        assertTrue(list.contains("\"network:on\""), "slash.list 缺少 network:on: " + list);

        // 2. 建真实任务并等终态落盘(meta 走磁盘真相源路径)
        String taskId = create("你好,slash 任务");
        fe.await(t -> t.contains("\"event\":\"task.updated\"") && t.contains("\"status\":\"done\"")
                && t.contains(taskId), "任务完成");
        assertFalse(readMeta(taskId).has("slashTaskTokens")
                        || readMeta(taskId).path("slashTaskTokens").size() > 0,
                "初始任务不应有 slash 任务 token: " + readMeta(taskId));

        // 3. slash.select 不带 taskId → token + position=bottom;token payload 注入 slashId
        String select = rpc("slash.select", Json.write(Json.obj().put("id", "network:on")));
        JsonNode selectRes = Json.parse(select).path("payload").path("result").path("results").path(0);
        assertEquals("bottom", selectRes.path("position").asString(), "slash.select 应返回 bottom 位置: " + select);
        String token = selectRes.path("token").asString();
        assertNotNull(token, "slash.select 应返回 token: " + select);
        SlashTokenEncoder.ParsedToken parsed = SlashTokenEncoder.parseToken(token);
        assertNotNull(parsed, "返回的 token 应为合法 opaque: " + token);
        assertEquals("network.access", parsed.kind(), "kind 应为 network.access");
        assertEquals("network:on", parsed.payload().path("slashId").asString(),
                "token payload 应注入 slashId: " + parsed.payload());

        // 4. slash.taskTokens.apply 挂到任务 → applied=true
        String apply = rpc("slash.taskTokens.apply", Json.write(Json.obj()
                .put("taskId", taskId).put("id", "network:on").put("token", token)));
        JsonNode applyRes = Json.parse(apply).path("payload").path("result");
        assertTrue(applyRes.path("applied").asBoolean(false), "apply 应 applied=true: " + apply);

        // 5. meta.slashTaskTokens 包含该 token(apply 落盘后 readMeta 即见)
        JsonNode metaTokens = readMeta(taskId).path("slashTaskTokens");
        assertTrue(metaTokens.isArray() && metaTokens.size() == 1
                        && token.equals(metaTokens.path(0).asString()),
                "meta 应恰好含该 token: " + metaTokens);

        // 6. slash.cancel 移除 → removed=true
        String cancel = rpc("slash.cancel", Json.write(Json.obj()
                .put("id", "network:on").put("token", token).put("taskId", taskId)));
        JsonNode cancelRes = Json.parse(cancel).path("payload").path("result");
        assertTrue(cancelRes.path("removed").asBoolean(false), "cancel 应 removed=true: " + cancel);

        // 7. meta 不再含该 token(空数组字段会被移除更干净)
        JsonNode meta2 = readMeta(taskId);
        assertFalse(meta2.path("slashTaskTokens").isArray()
                        && meta2.path("slashTaskTokens").size() > 0,
                "取消后 meta 不应再有 slash 任务 token: " + meta2.path("slashTaskTokens"));
    }

    // ---- 帮助方法 ----

    /** 创建任务并指定模型(configId 覆盖;模型池测试用故障模型作任务模型)。 */
    private String createWithConfig(String input, String configId) {
        tools.jackson.databind.node.ObjectNode params = Json.obj()
                .put("input", input).put("workspace", WS.toString()).put("configId", configId);
        String resp = rpc("task.run", Json.write(params));
        assertTrue(resp.contains("rpc.ok"), "创建失败: " + resp);
        return Json.parse(resp).path("payload").path("result").path("taskId").asString();
    }

    @Test
    void rpcCancelUnknownIsNotFound() {
        String resp = rpc("rpc.cancel", "{\"reqId\":\"no-such\"}");
        assertTrue(resp.contains("NOT_FOUND"), resp);
    }

    @Test
    void unknownMethodRejected() {
        String resp = rpc("no.such.method", "{}");
        assertTrue(resp.contains("UNKNOWN_METHOD"), resp);
    }

    // ---- 帮助方法 ----

    private void hello(WsTestClient c) {
        c.send("{\"type\":\"hello\",\"ver\":" + Frames.PROTOCOL_VERSION + ",\"role\":\"frontend\",\"apiKey\":\""
                + KEY + "\",\"clientId\":\"fe-test\"}");
        c.await(t -> t.contains("\"type\":\"welcome\""), "welcome");
    }

    private void sub(WsTestClient c, String channel) {
        c.send("{\"type\":\"sub\",\"channel\":\"" + channel + "\"}");
    }

    private String pub(String channel, String event, String payloadJson) {
        return "{\"type\":\"pub\",\"mid\":\"m-" + REQ.incrementAndGet() + "\",\"channel\":\"" + channel
                + "\",\"event\":\"" + event + "\",\"ts\":" + System.currentTimeMillis()
                + ",\"payload\":" + payloadJson + "}";
    }

    /** 发 RPC 并等到该 reqId 的 ok/err;返回最终帧文本。 */
    private String rpc(String method, String paramsJson) {
        String reqId = "r-" + REQ.incrementAndGet();
        fe.send(pub(Channels.workerCmd(k, workerProps.getWorkerId()), "rpc",
                "{\"reqId\":\"" + reqId + "\",\"method\":\"" + method + "\",\"params\":" + paramsJson + "}"));
        return fe.await(t -> t.contains(reqId) && (t.contains("rpc.ok") || t.contains("rpc.err")),
                "rpc " + method);
    }

    private String create(String input) {
        return create(input, null, WS.toString());
    }

    private String create(String input, String idemKey) {
        return create(input, idemKey, WS.toString());
    }

    private String create(String input, String idemKey, String workspace) {
        // Windows 绝对路径必须经 Json.write 转义反斜杠:裸拼产生 \U 等非法转义,FakeHub 会静默丢帧
        tools.jackson.databind.node.ObjectNode params = Json.obj()
                .put("input", input).put("workspace", workspace);
        if (idemKey != null) {
            params.put("idempotencyKey", idemKey);
        }
        String resp = rpc("task.run", Json.write(params));
        assertTrue(resp.contains("rpc.ok"), "创建失败: " + resp);
        return Json.parse(resp).path("payload").path("result").path("taskId").asString();
    }

    /** task.poll 应答封装:data 批次(按 seq 升序) + ok/err 结果。 */
    private record PollResp(List<JsonNode> data, JsonNode result, JsonNode err) {
        boolean isErr() {
            return err != null;
        }
    }

    /** 发一次 task.poll,收集该 reqId 的全部 rpc.data 帧与最终 ok/err(与 WorkerTaskPollTest 同款解析)。 */
    private PollResp poll(String paramsJson) {
        String reqId = "r-" + REQ.incrementAndGet();
        fe.send(pub(Channels.workerCmd(k, workerProps.getWorkerId()), "rpc",
                "{\"reqId\":\"" + reqId + "\",\"method\":\"task.poll\",\"params\":" + paramsJson + "}"));
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

    private JsonNode readMeta(String taskId) {
        try {
            return Json.parse(Files.readString(
                    workerProps.resolveDataDir().resolve("tasks").resolve(taskId).resolve("meta.json")));
        } catch (java.io.IOException e) {
            throw new AssertionError("meta 读取失败 task=" + taskId, e);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
