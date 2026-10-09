package dev.everyagent.worker.modules.search;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.event.Channels;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.hub.HubLink;
import dev.everyagent.worker.modules.WorkspaceManager;
import dev.everyagent.worker.plugin.registry.SearchProviderRegistry;
import dev.everyagent.worker.proto.RpcMethods;
import dev.everyagent.worker.rpc.RpcDispatcher;
import dev.everyagent.worker.task.TaskStore;
import dev.everyagent.worker.tools.RipgrepBinary;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 内置任务搜索引擎(kind {@code task})单测:经统一 {@code search}(kinds=[task])验证等价旧
 * {@code search} 语义——按 workspaceId 枚举任务、rounds.jsonl 命中聚合、user/finalReply
 * 干净文本二次匹配、字段名噪音丢弃、大小写/全字语义;另有 {@code compileSearchPattern} 纯函数。
 */
class TaskSearchProviderTest {

    @TempDir
    Path tempDir;

    private RpcDispatcher dispatcher;
    private WorkspaceManager workspaces;
    private WorkerProperties props;
    private Path ws;
    private boolean ready;

    @BeforeEach
    void setUp() throws Exception {
        props = new WorkerProperties();
        props.setHomeDir(tempDir.resolve("home").toString());
        ws = tempDir.resolve("ws");
        Files.createDirectories(ws);
        props.setWorkspaceRoot(ws.toString());
        Path rg = RgSearchEngineTest.locateRg();
        if (rg != null) {
            props.getTools().setRgPath(rg.toString());
        }
        ready = rg != null;

        dispatcher = new RpcDispatcher(null, new WorkerProperties());
        workspaces = mock(WorkspaceManager.class);
        when(workspaces.resolve(any())).thenReturn(new WorkspaceManager.Root(ws, ws.toRealPath()));
        when(workspaces.idOfRoot(any())).thenReturn("defaultworkspace");
        SearchProviderRegistry registry = new SearchProviderRegistry();
        RgSearchEngine engine = new RgSearchEngine(new RipgrepBinary(props), props);
        registry.register(new TaskSearchProvider(new TaskStore(props), engine, props));
        new SearchService(dispatcher, workspaces, registry, props);
    }

    // ---- 纯函数 ----

    @Test
    void compileSearchPatternWholeWordWraps() {
        Pattern p = TaskSearchProvider.compileSearchPattern("cat", false, false, true);
        assertTrue(p.matcher("a cat here").find());
        assertTrue(!p.matcher("concatenate").find(), "全字:concatenate 不命中");
    }

    @Test
    void compileSearchPatternFixedStringEscapesMeta() {
        Pattern p = TaskSearchProvider.compileSearchPattern("foo.bar", false, false, false);
        assertTrue(p.matcher("foo.bar").find());
        assertTrue(!p.matcher("fooXbar").find(), "固定串的 . 不是通配");
    }

    @Test
    void compileSearchPatternCaseSensitiveFlag() {
        assertTrue(TaskSearchProvider.compileSearchPattern("needle", false, false, false)
                .matcher("NEEDLE").find());
        assertTrue(!TaskSearchProvider.compileSearchPattern("needle", false, true, false)
                .matcher("NEEDLE").find());
    }

    @Test
    void compileSearchPatternDoesNotMatchAcrossLines() {
        Pattern p = TaskSearchProvider.compileSearchPattern("foo.*bar", true, false, false);
        assertTrue(p.matcher("foo bar").find());
        assertTrue(!p.matcher("foo\nbar").find(), "多行模式:'.' 不匹配换行");
    }

    // ---- 真实 rg 进程 ----

    private void seedTask(String workspaceId, String taskId, String title, String status, String rounds)
            throws Exception {
        Path dir = props.resolveHomeDir().resolve("workspaces").resolve(workspaceId)
                .resolve("tasks").resolve(taskId);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("meta.json"),
                "{\"taskId\":\"" + taskId + "\",\"title\":\"" + title + "\",\"status\":\"" + status
                        + "\",\"workspace\":\"/ws/" + workspaceId + "\",\"workspaceId\":\"" + workspaceId + "\"}");
        Files.writeString(dir.resolve("rounds.jsonl"), rounds);
    }

    private void seedDefault() throws Exception {
        seedTask("defaultworkspace", "t1", "第一个任务", "done",
                "{\"index\":1,\"startSeq\":\"1\",\"endSeq\":\"10\",\"user\":\"hello world\","
                        + "\"finalReply\":\"hi there\",\"subs\":[]}\n"
                        + "{\"index\":2,\"startSeq\":\"11\",\"endSeq\":\"20\",\"user\":\"needle in user input\","
                        + "\"finalReply\":\"the answer needle\",\"subs\":[]}\n");
    }

    private List<JsonNode> tasks(ObjectNode params) throws Exception {
        JsonNode reply = call(params);
        assertEquals("rpc.ok", reply.path("event").asString(), reply.toString());
        List<JsonNode> out = new ArrayList<>();
        reply.path("payload").path("result").path("items").forEach(out::add);
        return out;
    }

    private ObjectNode params(java.util.function.Consumer<ObjectNode> tune) {
        ObjectNode p = Json.obj()
                .put("workspace", ws.toAbsolutePath().normalize().toString())
                .put("pattern", "needle");
        p.set("kinds", Json.arr().add("task"));
        if (tune != null) {
            tune.accept(p);
        }
        return p;
    }

    private JsonNode call(ObjectNode params) throws Exception {
        CountDownLatch replied = new CountDownLatch(1);
        List<Object[]> frames = new ArrayList<>();
        HubLink link = mock(HubLink.class);
        when(link.k()).thenReturn("k");
        when(link.workerId()).thenReturn("w");
        doAnswer(inv -> {
            Object[] args = inv.getArguments();
            synchronized (frames) {
                frames.add(args);
            }
            if (!"rpc.data".equals(args[1])) {
                replied.countDown();
            }
            return null;
        }).when(link).pub(any(), any(), any(), any(), any());
        ObjectNode payload = Json.obj().put("reqId", "req-" + System.nanoTime()).put("method", RpcMethods.SEARCH);
        payload.set("params", params);
        ObjectNode frame = Json.obj().put("channel", Channels.workerCmd("k", "w")).put("event", "rpc");
        frame.set("payload", payload);
        dispatcher.onHubMessage(link, frame);
        assertTrue(replied.await(30, TimeUnit.SECONDS), "search 未在 30s 内应答");
        Object[] last;
        synchronized (frames) {
            last = frames.get(frames.size() - 1);
        }
        return Json.obj().put("event", (String) last[1]).set("payload", (JsonNode) last[3]);
    }

    @Test
    void realSearchAggregatesTaskHitsWithCleanTextMatch() throws Exception {
        Assumptions.assumeTrue(ready, "环境无 rg,跳过真实进程用例");
        seedDefault();
        List<JsonNode> items = tasks(params(null));
        assertTrue(items.size() >= 2, "user + finalReply 各一条: " + items);
        JsonNode userHit = items.get(0);
        assertEquals("task", userHit.path("kind").asString());
        assertEquals("builtin.task", userHit.path("providerId").asString());
        assertEquals("t1", userHit.path("taskId").asString());
        assertEquals("第一个任务", userHit.path("title").asString());
        assertEquals("done", userHit.path("status").asString());
        assertEquals(2, userHit.path("roundIndex").asInt());
        assertEquals("user", userHit.path("field").asString());
        assertEquals("needle in user input", userHit.path("line").asString());
        assertEquals(0, userHit.path("matchIndex").asInt());
        assertEquals("needle", userHit.path("matchText").asString());

        JsonNode replyHit = items.get(1);
        assertEquals("finalReply", replyHit.path("field").asString());
        assertEquals(11, replyHit.path("matchIndex").asInt(), "the answer needle: needle@11");
    }

    @Test
    void fieldNameNoiseIsDiscarded() throws Exception {
        Assumptions.assumeTrue(ready, "环境无 rg,跳过真实进程用例");
        seedDefault();
        List<JsonNode> items = tasks(params(p -> p.put("pattern", "finalReply")));
        assertTrue(items.isEmpty(), "字段名噪音应被后处理丢弃: " + items);
    }

    @Test
    void caseSensitiveAndWholeWordFiltersPassedThrough() throws Exception {
        Assumptions.assumeTrue(ready, "环境无 rg,跳过真实进程用例");
        seedTask("defaultworkspace", "t3", "语义任务", "done",
                "{\"index\":1,\"startSeq\":\"1\",\"endSeq\":\"10\",\"user\":\"NEEDLE upper\","
                        + "\"finalReply\":\"needle standalone needlefish\",\"subs\":[]}\n");
        // 缺省忽略大小写:user/finalReply 均命中
        assertEquals(2, tasks(params(null)).size());
        // 大小写敏感:只命中小写 needle(finalReply)
        List<JsonNode> cs = tasks(params(p -> p.set("filters",
                Json.obj().put("task.caseSensitive", true))));
        assertEquals(1, cs.size(), cs.toString());
        assertEquals("finalReply", cs.get(0).path("field").asString());
        // 全字:NEEDLE(user)+standalone needle(finalReply),needlefish 不算 → 2 条
        List<JsonNode> ww = tasks(params(p -> p.set("filters",
                Json.obj().put("task.wholeWord", true))));
        assertEquals(2, ww.size(), ww.toString());
    }

    @Test
    void onlyCurrentWorkspaceTasksSearched() throws Exception {
        Assumptions.assumeTrue(ready, "环境无 rg,跳过真实进程用例");
        seedDefault();
        seedTask("w_other", "t2", "另一个工作区任务", "done",
                "{\"index\":1,\"startSeq\":\"1\",\"endSeq\":\"10\",\"user\":\"needle here\","
                        + "\"finalReply\":\"ok\",\"subs\":[]}\n");
        List<JsonNode> items = tasks(params(null));
        assertTrue(items.stream().allMatch(it -> "t1".equals(it.path("taskId").asString())),
                "只搜 defaultworkspace 任务: " + items);
    }
}