package dev.everyagent.worker;

import dev.everyagent.contract.frame.Frames;
import dev.everyagent.contract.ids.Ids;
import dev.everyagent.contract.json.Json;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.hub.HubPool;
import dev.everyagent.worker.modules.ConfigStore.ResolvedConfig;
import dev.everyagent.worker.proto.Channels;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task.poll 集成测试:任务流纯拉取(pull 模式)的 worker 端服务验收。
 * 覆盖:终态任务 rounds 尾段 / events 增量 afterSeq / beforeSeq 翻页 /
 * afterSeq+beforeSeq 同给区间查询(开区间不含端点、升序、limit 头部截断 + hasMore、
 * 以 lastSeq 推进 afterSeq 续拉拼回全量,旧三形态行为不变);
 * rounds count 语义(两轮任务 count=1 只含末轮,count=2 含全量);
 * 不存在 taskId → NOT_FOUND;热任务长轮询(短 waitMs 超时路径:空批次应答不抛异常)。
 * 复用 WorkerIntegrationTest 的测试基建(FakeHub + FakeChatModel + WsTestClient)。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class WorkerTaskPollTest {

    private static final String KEY = "test-key-poll";
    private static final AtomicLong REQ = new AtomicLong();
    private static final java.nio.file.Path WS =
            java.nio.file.Path.of("target/test-workspace-poll").toAbsolutePath().normalize();
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
        r.add("worker.worker-id", () -> "test-worker-poll");
        r.add("worker.hub-initial-backoff-ms", () -> "100");
        r.add("worker.hub-max-backoff-ms", () -> "300");
        r.add("worker.limits.max-concurrent-tasks", () -> "4");
        r.add("worker.limits.max-concurrent-subs", () -> "4");
        r.add("worker.limits.ask-timeout-ms", () -> "120000");
        r.add("worker.limits.sub-wait-timeout-ms", () -> "15000");
        r.add("worker.retry.max-request-retries", () -> "0");
        r.add("worker.home-dir", () -> "target/test-home-poll-" + System.nanoTime());
        r.add("worker.workspace-root", () -> "target/test-workspace-poll");
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

    // ---- task.poll 用例 ----

    @Test
    void terminalTaskRoundsAndEventsModes() {
        String taskId = create("你好,轮次测试");
        fe.await(t -> t.contains("\"event\":\"task.updated\"") && t.contains("\"status\":\"done\"")
                && t.contains(taskId), "任务终态 done");

        // rounds 尾段:终态任务应返回最近 1 轮(含 user.message + message),live=false,status=done
        PollResp rounds = pollUntil("{\"taskId\":\"" + taskId + "\",\"mode\":\"rounds\",\"count\":1}",
                p -> !p.isErr() && !p.result.path("live").asBoolean(false) && !p.data.isEmpty()
                        && p.data.stream().anyMatch(e -> e.path("event").asString().equals("message")),
                "终态 rounds 尾段 message");
        List<String> names = rounds.data.stream().map(e -> e.path("event").asString()).toList();
        assertTrue(names.contains("user.message"), "rounds 尾段含 user.message: " + names);
        assertTrue(names.contains("message"), "rounds 尾段含 message: " + names);
        assertEquals("done", rounds.result.path("task").path("status").asString(), "终态 status=done");
        assertFalse(rounds.result.path("live").asBoolean(false), "终态 live=false");
        assertTrue(rounds.result.path("lastSeq").asLong() > 0);
        for (JsonNode e : rounds.data) {
            assertTrue(e.path("seq").asLong() > 0, "事件 seq 为正");
            assertTrue(e.path("event").asString().length() > 0);
        }

        // events 全量:afterSeq=0 → 全部事件
        PollResp all = poll("{\"taskId\":\"" + taskId + "\",\"mode\":\"events\",\"afterSeq\":0}");
        assertFalse(all.isErr(), "全量拉取应 ok: " + String.valueOf(all.err));
        assertFalse(all.data.isEmpty(), "events 全量不应为空");
        long last = all.result.path("lastSeq").asLong();
        assertTrue(last > 0, "lastSeq 为正");
        assertTrue(last >= rounds.result.path("lastSeq").asLong());

        // 增量:afterSeq=last → 无新事件,空批次 + firstSeq/lastSeq=0
        PollResp inc = poll("{\"taskId\":\"" + taskId + "\",\"mode\":\"events\",\"afterSeq\":" + last + "}");
        assertFalse(inc.isErr(), String.valueOf(inc.err));
        assertTrue(inc.data.isEmpty(), "afterSeq=last 增量应为空: " + inc.data);
        assertEquals(0, inc.result.path("firstSeq").asLong());
        assertEquals(0, inc.result.path("lastSeq").asLong());

        // 向前翻页:beforeSeq=last → 返回 last 之前最近一批,全部 seq < beforeSeq
        PollResp prev = poll("{\"taskId\":\"" + taskId + "\",\"mode\":\"events\",\"beforeSeq\":" + last + "}");
        assertFalse(prev.isErr(), String.valueOf(prev.err));
        assertFalse(prev.data.isEmpty(), "beforeSeq 翻页不应为空");
        for (JsonNode e : prev.data) {
            assertTrue(e.path("seq").asLong() < last, "翻页事件 seq 应 < beforeSeq: " + e);
        }
        assertTrue(prev.data.get(prev.data.size() - 1).path("seq").asLong() < last);

        // 未知 mode → BAD_PARAMS(区间查询语义见 rangeQueryWithBothCursors)
        PollResp badMode = poll("{\"taskId\":\"" + taskId + "\",\"mode\":\"nope\"}");
        assertTrue(badMode.isErr(), "未知 mode 应报错");
        assertEquals("BAD_PARAMS", badMode.err.path("code").asString());
    }

    @Test
    void rangeQueryWithBothCursors() {
        String taskId = create("区间查询测试");
        fe.await(t -> t.contains("\"event\":\"task.updated\"") && t.contains("\"status\":\"done\"")
                && t.contains(taskId), "任务终态 done");

        // 全量快照:升序 seq 基准(终态任务只有磁盘持久事件)
        PollResp all = poll("{\"taskId\":\"" + taskId + "\",\"mode\":\"events\",\"afterSeq\":0}");
        assertFalse(all.isErr(), String.valueOf(all.err));
        assertTrue(all.data.size() >= 3, "测试前提至少 3 条事件(实际 " + all.data.size() + ")");
        List<Long> seqs = all.data.stream().map(e -> e.path("seq").asLong()).toList();
        for (int i = 1; i < seqs.size(); i++) {
            assertTrue(seqs.get(i) > seqs.get(i - 1), "全量快照 seq 升序: " + seqs);
        }
        long first = seqs.get(0);
        long last = seqs.get(seqs.size() - 1);

        // 开区间:afterSeq=first、beforeSeq=last → 恰为中间事件,两端端点不含
        PollResp mid = poll("{\"taskId\":\"" + taskId + "\",\"afterSeq\":" + first
                + ",\"beforeSeq\":" + last + "}");
        assertFalse(mid.isErr(), String.valueOf(mid.err));
        assertEquals(seqs.subList(1, seqs.size() - 1),
                mid.data.stream().map(e -> e.path("seq").asLong()).toList(),
                "区间=去掉两端的中间事件(开区间不含端点)");
        assertFalse(mid.data.isEmpty(), "至少 3 条事件 ⇒ 区间非空");
        assertFalse(mid.result.path("hasMore").asBoolean(false), "整区间一次返回 hasMore=false");
        assertEquals(mid.data.get(0).path("seq").asLong(), mid.result.path("firstSeq").asLong(),
                "firstSeq=区间首条");
        assertEquals(mid.data.get(mid.data.size() - 1).path("seq").asLong(),
                mid.result.path("lastSeq").asLong(), "lastSeq=区间末条");

        // 端点各外扩 1:区间=全量等价
        PollResp whole = poll("{\"taskId\":\"" + taskId + "\",\"afterSeq\":" + (first - 1)
                + ",\"beforeSeq\":" + (last + 1) + "}");
        assertFalse(whole.isErr(), String.valueOf(whole.err));
        assertEquals(seqs, whole.data.stream().map(e -> e.path("seq").asLong()).toList(),
                "外扩 1 后区间=全量");

        // limit 头部截断 + hasMore:以 lastSeq 推进 afterSeq 续拉,分批拼回全量
        List<Long> paged = new ArrayList<>();
        long cursor = first - 1;
        boolean sawHasMore = false;
        for (int i = 0; i < 50; i++) {
            PollResp p = poll("{\"taskId\":\"" + taskId + "\",\"afterSeq\":" + cursor
                    + ",\"beforeSeq\":" + (last + 1) + ",\"limit\":2}");
            assertFalse(p.isErr(), String.valueOf(p.err));
            List<Long> batch = p.data.stream().map(e -> e.path("seq").asLong()).toList();
            for (Long s : batch) {
                assertTrue(s > cursor && s < last + 1, "分批事件落在 (afterSeq,beforeSeq) 内: " + s);
                if (!paged.isEmpty()) {
                    assertTrue(s > paged.get(paged.size() - 1), "跨批仍升序: " + s);
                }
                paged.add(s);
            }
            boolean hm = p.result.path("hasMore").asBoolean(false);
            if (!batch.isEmpty() && hm) {
                sawHasMore = true;
            }
            if (!hm) {
                break;
            }
            cursor = p.result.path("lastSeq").asLong();
            assertTrue(cursor > 0, "hasMore=true 时 lastSeq 为正(可推进游标)");
        }
        assertTrue(sawHasMore, "limit=2 且事件 >=3 条,首轮截断必然 hasMore=true");
        assertEquals(seqs, paged, "limit=2 分批续拉拼回全量");

        // 旧三形态回归:仅 afterSeq / 仅 beforeSeq 行为不因区间语义改变
        PollResp inc = poll("{\"taskId\":\"" + taskId + "\",\"mode\":\"events\",\"afterSeq\":"
                + last + "}");
        assertFalse(inc.isErr(), String.valueOf(inc.err));
        assertTrue(inc.data.isEmpty(), "仅 afterSeq=last 增量为空(旧形态不变)");
        PollResp prev = poll("{\"taskId\":\"" + taskId + "\",\"mode\":\"events\",\"beforeSeq\":"
                + last + "}");
        assertFalse(prev.isErr(), String.valueOf(prev.err));
        assertFalse(prev.data.isEmpty(), "仅 beforeSeq 翻页不空(旧形态不变)");
        for (JsonNode e : prev.data) {
            assertTrue(e.path("seq").asLong() < last, "仅 beforeSeq 仍为上界开区间翻页: " + e);
        }
    }

    @Test
    void pollUnknownTaskIsNotFound() {
        PollResp p = poll("{\"taskId\":\"t_nonexistent\"}");
        assertTrue(p.isErr(), "不存在任务应 NOT_FOUND");
        assertEquals("NOT_FOUND", p.err.path("code").asString());
        assertTrue(p.err.path("message").asString().contains("t_nonexistent"),
                "错误信息应含 taskId: " + p.err);
    }

    @Test
    void roundsCountSemantics() {
        String taskId = create("第一轮问候");
        fe.await(t -> t.contains("\"event\":\"task.updated\"") && t.contains("\"status\":\"done\"")
                && t.contains(taskId), "首轮 done");
        // 终态冷启动再运行,凑出两轮 user.message
        fe.send(pub(Channels.workerInput(k, workerProps.getWorkerId()), "task.input",
                "{\"taskId\":\"" + taskId + "\",\"text\":\"THINK:第二轮追问\"}"));
        // events 全量轮询直到两轮完成(live=false)且含 2 条 user.message
        PollResp two = pollUntil("{\"taskId\":\"" + taskId + "\",\"mode\":\"events\",\"afterSeq\":0}",
                p -> !p.isErr() && !p.result.path("live").asBoolean(false)
                        && p.data.stream().filter(e -> e.path("event").asString().equals("user.message")).count() == 2,
                "两轮完成且 live=false");
        long firstUserSeq = two.data.stream()
                .filter(e -> e.path("event").asString().equals("user.message"))
                .mapToLong(e -> e.path("seq").asLong()).min().orElseThrow();
        long secondUserSeq = two.data.stream()
                .filter(e -> e.path("event").asString().equals("user.message"))
                .mapToLong(e -> e.path("seq").asLong()).max().orElseThrow();
        assertTrue(secondUserSeq > firstUserSeq);

        // count=1 → 只含最近 1 轮(不含第一轮 user.message)
        PollResp one = poll("{\"taskId\":\"" + taskId + "\",\"mode\":\"rounds\",\"count\":1}");
        assertFalse(one.isErr(), String.valueOf(one.err));
        assertTrue(one.data.stream().noneMatch(e -> e.path("seq").asLong() == firstUserSeq),
                "count=1 不应含第一轮 user.message: " + one.data);
        assertTrue(one.data.stream().anyMatch(e -> e.path("seq").asLong() == secondUserSeq),
                "count=1 应含第二轮 user.message");

        // count=2 → 从第一轮起全量(含第一轮 user.message)
        PollResp twoRounds = poll("{\"taskId\":\"" + taskId + "\",\"mode\":\"rounds\",\"count\":2}");
        assertFalse(twoRounds.isErr(), String.valueOf(twoRounds.err));
        assertTrue(twoRounds.data.stream().anyMatch(e -> e.path("seq").asLong() == firstUserSeq),
                "count=2 应含第一轮 user.message");
        assertTrue(twoRounds.data.stream().anyMatch(e -> e.path("seq").asLong() == secondUserSeq));
        // 两轮 user.message 均完整落盘
        assertEquals(2, twoRounds.data.stream()
                .filter(e -> e.path("event").asString().equals("user.message")).count());
    }

    @Test
    void liveTaskLongPollTimeoutReturnsEmptyBatch() {
        String taskId = create("ASK:要继续吗");
        fe.await(t -> t.contains("\"event\":\"task.updated\"")
                && t.contains("\"status\":\"waiting-user\"") && t.contains(taskId), "等待用户输入(waiting-user)");

        // 快照当前全部事件拿到最大 seq(waiting-user 空闲,期间无新事件)
        PollResp all = poll("{\"taskId\":\"" + taskId + "\",\"afterSeq\":0}");
        assertFalse(all.isErr(), String.valueOf(all.err));
        assertFalse(all.data.isEmpty());
        long last = all.result.path("lastSeq").asLong();
        assertTrue(last > 0, "快照 lastSeq 为正");

        // afterSeq=last 且 waitMs=80:初查为空 → 长轮询挂起 80ms → 超时放行 → 空批次 ok 应答
        PollResp w = poll("{\"taskId\":\"" + taskId + "\",\"afterSeq\":" + last + ",\"waitMs\":80}");
        assertFalse(w.isErr(), "长轮询超时不应抛错: " + String.valueOf(w.err));
        assertTrue(w.data.isEmpty(), "热任务无增量应返回空批次");
        assertEquals(0, w.result.path("firstSeq").asLong());
        assertEquals(0, w.result.path("lastSeq").asLong());
        assertTrue(w.result.path("live").asBoolean(false), "waiting-user 非终态 live=true");
        assertEquals("waiting-user", w.result.path("task").path("status").asString());

        // 清理:取消,释放并发槽位
        String cancel = rpc("task.cancel", Json.write(Json.obj().put("taskId", taskId)));
        assertTrue(cancel.contains("rpc.ok"), cancel);
    }

    @Test
    void pollRequiresTaskId() {
        PollResp p = poll("{}");
        assertTrue(p.isErr(), "缺 taskId 应 BAD_PARAMS");
        assertEquals("BAD_PARAMS", p.err.path("code").asString());
    }

    // ---- 返工(文件变更迁移):task.poll roundId 参数(events 模式按轮稳定主键定位区间)----

    @Test
    void pollRoundIdReturnsRoundEventRange() {
        String taskId = create("你好,轮次测试");
        fe.await(t -> t.contains("\"event\":\"task.updated\"") && t.contains("\"status\":\"done\"")
                && t.contains(taskId), "任务终态 done");

        // 从 task.rounds 取该轮 roundId 与 startSeq/endSeq(roundId 由开轮路径稳定生成)
        String roundsFrame = rpc("task.rounds", Json.write(Json.obj().put("taskId", taskId)));
        assertTrue(roundsFrame.contains("rpc.ok"), "task.rounds 应 ok: " + roundsFrame);
        JsonNode rr = Json.parse(roundsFrame).path("payload").path("result").path("rounds");
        assertEquals(1, rr.size());
        String roundId = rr.get(0).path("roundId").asString();
        assertFalse(roundId.isEmpty(), "roundId 应非空: " + rr);
        long startSeq = Long.parseLong(rr.get(0).path("startSeq").asString());
        long endSeq = Long.parseLong(rr.get(0).path("endSeq").asString());
        assertTrue(endSeq > startSeq);

        // events 模式带 roundId:返回该轮区间(含轮起点 user.message 与最终回复 message),seq 升序
        PollResp p = poll("{\"taskId\":\"" + taskId + "\",\"mode\":\"events\",\"roundId\":\"" + roundId + "\"}");
        assertFalse(p.isErr(), String.valueOf(p.err));
        assertFalse(p.data.isEmpty(), "roundId 区间不应为空");
        assertTrue(p.data.stream().anyMatch(e -> e.path("seq").asLong() == startSeq
                        && "user.message".equals(e.path("event").asString())),
                "区间应含轮起点 user.message: " + p.data);
        assertTrue(p.data.stream().anyMatch(e -> e.path("seq").asLong() == endSeq
                        && "message".equals(e.path("event").asString())),
                "区间应含最终回复 message: " + p.data);
        long prev = 0;
        for (JsonNode e : p.data) {
            long s = e.path("seq").asLong();
            assertTrue(s >= startSeq && s <= endSeq, "区间事件 seq 落在 [startSeq,endSeq] 内: " + e);
            assertTrue(s > prev, "区间事件 seq 升序: " + p.data);
            prev = s;
        }
        assertFalse(p.result.path("hasMore").asBoolean(false), "单轮一次返回 hasMore=false");
    }

    @Test
    void pollUnknownRoundIdIsBadParams() {
        // 先建一个终态任务保证 taskId 存在,roundId 不存在 → BAD_PARAMS
        String taskId = create("你好,轮次测试");
        fe.await(t -> t.contains("\"event\":\"task.updated\"") && t.contains("\"status\":\"done\"")
                && t.contains(taskId), "任务终态 done");
        PollResp p = poll("{\"taskId\":\"" + taskId + "\",\"mode\":\"events\",\"roundId\":\"round_nope\"}");
        assertTrue(p.isErr(), "不存在的 roundId 应 BAD_PARAMS");
        assertEquals("BAD_PARAMS", p.err.path("code").asString());
        assertTrue(p.err.path("message").asString().contains("round_nope"),
                "错误信息应含 roundId: " + p.err);
    }

    // ---- 帮助方法 ----

    /** task.poll 应答封装:data 批次 + ok/err 结果。 */
    private record PollResp(List<JsonNode> data, JsonNode result, JsonNode err) {
        boolean isErr() {
            return err != null;
        }
    }

    /** 发一次 task.poll,收集该 reqId 的全部 rpc.data 帧与最终 ok/err。 */
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

    /** 轮询 task.poll 直到谓词满足(终态落盘等异步完成场景)。 */
    private PollResp pollUntil(String paramsJson, Predicate<PollResp> cond, String what) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            PollResp p = poll(paramsJson);
            if (cond.test(p)) {
                return p;
            }
            sleep(150);
        }
        throw new AssertionError("task.poll 超时等待: " + what);
    }

    private void hello(WsTestClient c) {
        c.send("{\"type\":\"hello\",\"ver\":" + Frames.PROTOCOL_VERSION
                + ",\"role\":\"frontend\",\"apiKey\":\"" + KEY
                + "\",\"clientId\":\"fe-poll\"}");
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

    /** 非 task.poll 的 RPC(如 task.cancel):等到该 reqId 的 ok/err,返回最终帧。 */
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