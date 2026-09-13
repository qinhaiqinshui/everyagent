package dev.everyagent.worker.modules;

import dev.everyagent.contract.json.Json;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.hub.HubLink;
import dev.everyagent.worker.proto.Channels;
import dev.everyagent.worker.proto.RpcMethods;
import dev.everyagent.worker.rpc.RpcDispatcher;
import dev.everyagent.worker.task.TaskStore;
import dev.everyagent.worker.tools.RipgrepBinary;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * task.search 单元测试(不依赖 Spring/真实 hub):
 * ① compileSearchPattern 纯函数(全字包裹 / 固定串转义 / 大小写标志);
 * ② 临时目录上的真实 rg 进程搜索:按 workspaceId 枚举任务、rounds.jsonl 命中聚合、
 *    user/finalReply 干净文本二次匹配、字段名噪音丢弃(搜 finalReply 不命中)、
 *    大小写/全字语义、workspaceId 过滤。
 * 真实 RpcDispatcher 分发 + mock HubLink 捕获出站帧(FsSearchServiceTest 同款);
 * 环境无 rg 时进程类用例跳过(assumeTrue),纯函数用例照常。
 */
class TaskSearchServiceTest {

    @TempDir
    Path tempDir;

    private RpcDispatcher dispatcher;
    private TaskStore store;
    /** rg 是否可用(不可用则真实进程用例跳过)。 */
    private boolean ready;

    @BeforeEach
    void setUp() throws Exception {
        WorkerProperties props = new WorkerProperties();
        props.setHomeDir(tempDir.resolve("home").toString());
        Path rgBin = locateRg();
        if (rgBin != null) {
            props.getTools().setRgPath(rgBin.toString());
        }
        ready = rgBin != null;

        dispatcher = new RpcDispatcher(null, new WorkerProperties());
        store = new TaskStore(props);
        new TaskSearchService(dispatcher, store, new RipgrepBinary(props));
    }

    /** 测试环境 rg 定位:程序根 runtime/bin(IDE/打包)→ 模块父目录(maven,user.dir=模块)→ PATH。 */
    private static Path locateRg() {
        boolean win = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win");
        String name = win ? "rg.exe" : "rg";
        for (String base : List.of("runtime/bin", "../runtime/bin")) {
            Path p = Path.of(base, name).toAbsolutePath().normalize();
            if (Files.isRegularFile(p) && Files.isExecutable(p)) {
                return p;
            }
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(File.pathSeparator)) {
                if (dir.isBlank()) {
                    continue;
                }
                Path p = Path.of(dir.trim()).resolve(name);
                if (Files.isRegularFile(p) && Files.isExecutable(p)) {
                    return p;
                }
            }
        }
        return null;
    }

    // ---- 帮助:造数据 / 收发 ----

    /** 手工造一个任务目录:meta.json + rounds.jsonl(不 track,纯磁盘冷任务)。 */
    private Path seedTask(String workspaceId, String taskId, String title, String status, String rounds)
            throws Exception {
        Path dir = tempDir.resolve("home").resolve("workspaces").resolve(workspaceId)
                .resolve("tasks").resolve(taskId);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("meta.json"),
                "{\"taskId\":\"" + taskId + "\",\"title\":\"" + title + "\",\"status\":\"" + status
                        + "\",\"workspace\":\"/ws/" + workspaceId + "\",\"workspaceId\":\"" + workspaceId + "\"}");
        Files.writeString(dir.resolve("rounds.jsonl"), rounds);
        return dir;
    }

    /** 标准夹具:任务 t1 两轮,第二轮 user/finalReply 均含 needle。 */
    private void seedDefault() throws Exception {
        seedTask("defaultworkspace", "t1", "第一个任务", "done",
                "{\"index\":1,\"startSeq\":\"1\",\"endSeq\":\"10\",\"user\":\"hello world\","
                        + "\"finalReply\":\"hi there\",\"subs\":[]}\n"
                        + "{\"index\":2,\"startSeq\":\"11\",\"endSeq\":\"20\",\"user\":\"needle in user input\","
                        + "\"finalReply\":\"the answer needle\",\"subs\":[]}\n");
    }

    /** 发起一次 task.search(经真实分发器),返回末帧 {event, payload}。 */
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

        ObjectNode payload = Json.obj()
                .put("reqId", "req-" + System.nanoTime())
                .put("method", RpcMethods.TASK_SEARCH);
        payload.set("params", params);
        ObjectNode frame = Json.obj()
                .put("channel", Channels.workerCmd("k", "w"))
                .put("event", "rpc");
        frame.set("payload", payload);
        dispatcher.onHubMessage(link, frame);

        assertTrue(replied.await(30, TimeUnit.SECONDS), "task.search 未在 30s 内应答");
        Object[] last;
        synchronized (frames) {
            last = frames.get(frames.size() - 1);
        }
        return Json.obj().put("event", (String) last[1]).set("payload", (JsonNode) last[3]);
    }

    /** 内联(未分批)应答:断言 ok 并取出 result.files(按 taskId 建索引)。 */
    private Map<String, JsonNode> inlineTasks(ObjectNode params) throws Exception {
        JsonNode reply = call(params);
        assertEquals("rpc.ok", reply.path("event").asString(), reply.toString());
        JsonNode result = reply.path("payload").path("result");
        Map<String, JsonNode> byTask = new HashMap<>();
        result.path("files").forEach(t -> byTask.put(t.path("taskId").asString(), t));
        return byTask;
    }

    private static ObjectNode params(String workspaceId, String pattern) {
        return Json.obj().put("workspaceId", workspaceId).put("pattern", pattern);
    }

    /** 断言 rpc.err 并返回错误消息(参数非法类用例,不依赖 rg 可用性)。rpc.err payload = {reqId, code, message}。 */
    private String callErr(ObjectNode params) throws Exception {
        JsonNode reply = call(params);
        assertEquals("rpc.err", reply.path("event").asString(), reply.toString());
        return reply.path("payload").path("message").asString("");
    }

    // ---- ① 二次匹配 Pattern(纯函数) ----

    @Test
    void compileSearchPatternWholeWordWraps() {
        java.util.regex.Pattern p = TaskSearchService.compileSearchPattern("cat", false, false, true);
        assertTrue(p.matcher("a cat here").find());
        assertTrue(!p.matcher("concatenate").find(), "全字:concatenate 不命中");
    }

    @Test
    void compileSearchPatternFixedStringEscapesMeta() {
        java.util.regex.Pattern p = TaskSearchService.compileSearchPattern("foo.bar", false, false, false);
        assertTrue(p.matcher("foo.bar").find());
        assertTrue(!p.matcher("fooXbar").find(), "固定串的 . 不是通配");
    }

    @Test
    void compileSearchPatternCaseSensitiveFlag() {
        java.util.regex.Pattern ci = TaskSearchService.compileSearchPattern("needle", false, false, false);
        assertTrue(ci.matcher("NEEDLE").find(), "默认忽略大小写");
        java.util.regex.Pattern cs = TaskSearchService.compileSearchPattern("needle", false, true, false);
        assertTrue(!cs.matcher("NEEDLE").find(), "大小写敏感时不命中大写");
    }

    @Test
    void compileSearchPatternDoesNotMatchAcrossLines() {
        // 不加 DOTALL:'.' 不跨换行,与 rg 单行搜索一致(finalReply 含真换行时)。
        java.util.regex.Pattern p = TaskSearchService.compileSearchPattern("foo.*bar", true, false, false);
        assertTrue(p.matcher("foo bar").find());
        assertTrue(!p.matcher("foo\nbar").find(), "多行模式:'.' 不匹配换行");
    }

    // ---- ①.5 参数校验(非法请求稳定拒绝,不依赖 rg) ----

    @Test
    void requiresWorkspaceId() throws Exception {
        ObjectNode params = Json.obj().put("pattern", "needle");
        String err = callErr(params);
        assertTrue(err.contains("workspaceId"), "缺 workspaceId 应可读报错: " + err);
    }

    @Test
    void rejectsPathTraversalWorkspaceId() throws Exception {
        for (String bad : List.of("..", "../etc", "a/b", "a\\b", "/abs", ".", "a.b")) {
            String err = callErr(params(bad, "needle"));
            assertTrue(err.contains("workspaceId"), "非法 id 应报 workspaceId 错误: " + bad + " → " + err);
        }
    }

    @Test
    void rejectsBlankPattern() throws Exception {
        String err = callErr(params("defaultworkspace", "   "));
        assertTrue(err.contains("pattern"), "空白 pattern 应报错: " + err);
    }

    // ---- ② 真实 rg 进程 ----

    @Test
    void realSearchAggregatesTasksWithCleanTextMatch() throws Exception {
        Assumptions.assumeTrue(ready, "环境无 rg,跳过真实进程用例");
        seedDefault();
        Map<String, JsonNode> tasks = inlineTasks(params("defaultworkspace", "needle"));
        JsonNode t1 = tasks.get("t1");
        assertTrue(t1 != null, "应聚合命中任务 t1: " + tasks.keySet());
        assertEquals("第一个任务", t1.path("title").asString());
        assertEquals("done", t1.path("status").asString());
        JsonNode matches = t1.path("matches");
        // user 与 finalReply 各命中一次 → 2 条
        assertEquals(2, matches.size(), "user + finalReply 各一条: " + matches);
        JsonNode userHit = matches.path(0);
        assertEquals(2, userHit.path("roundIndex").asInt());
        assertEquals("user", userHit.path("field").asString());
        assertEquals("needle in user input", userHit.path("line").asString());
        assertEquals(0, userHit.path("matchIndex").asInt());
        assertEquals("needle", userHit.path("matchText").asString());
        JsonNode replyHit = matches.path(1);
        assertEquals("finalReply", replyHit.path("field").asString());
        assertEquals(11, replyHit.path("matchIndex").asInt(), "the answer needle: needle@11");
    }

    @Test
    void fieldNameNoiseIsDiscarded() throws Exception {
        Assumptions.assumeTrue(ready, "环境无 rg,跳过真实进程用例");
        seedDefault();
        // 搜字段名 finalReply:rg 会命中 JSON 字段名(每行),但干净文本不含该词 → 全部丢弃。
        Map<String, JsonNode> tasks = inlineTasks(params("defaultworkspace", "finalReply"));
        assertTrue(tasks.isEmpty(), "字段名噪音应被后处理丢弃: " + tasks.keySet());
    }

    @Test
    void workspaceFilterLimitsEnumeration() throws Exception {
        Assumptions.assumeTrue(ready, "环境无 rg,跳过真实进程用例");
        seedDefault();
        seedTask("w_other", "t2", "另一个工作区任务", "done",
                "{\"index\":1,\"startSeq\":\"1\",\"endSeq\":\"10\",\"user\":\"needle here\","
                        + "\"finalReply\":\"ok\",\"subs\":[]}\n");
        Map<String, JsonNode> tasks = inlineTasks(params("defaultworkspace", "needle"));
        assertTrue(tasks.containsKey("t1"), "只搜 defaultworkspace 任务: " + tasks.keySet());
        assertNull(tasks.get("t2"), "w_other 工作区任务不应出现");
    }

    @Test
    void caseSensitiveAndWholeWordSemantics() throws Exception {
        Assumptions.assumeTrue(ready, "环境无 rg,跳过真实进程用例");
        seedTask("defaultworkspace", "t3", "语义任务", "done",
                "{\"index\":1,\"startSeq\":\"1\",\"endSeq\":\"10\",\"user\":\"NEEDLE upper\","
                        + "\"finalReply\":\"needle standalone needlefish\",\"subs\":[]}\n");
        // 默认忽略大小写:大写 NEEDLE 命中
        Map<String, JsonNode> ci = inlineTasks(params("defaultworkspace", "needle"));
        assertEquals(2, ci.get("t3").path("matches").size(), "ignore-case 下 user/finalReply 均命中");
        // 大小写敏感:NEEDLE 不再命中 user;finalReply 里小写 needle 仍命中
        Map<String, JsonNode> cs = inlineTasks(params("defaultworkspace", "needle").put("caseSensitive", true));
        assertEquals(1, cs.get("t3").path("matches").size(), "case-sensitive 只命中小写 needle");
        assertEquals("finalReply", cs.get("t3").path("matches").path(0).path("field").asString());
        // 全字:NEEDLE(user)与 standalone needle(finalReply)各命中一次,needlefish 不算 → 共 2 条
        Map<String, JsonNode> ww = inlineTasks(params("defaultworkspace", "needle").put("wholeWord", true));
        assertEquals(2, ww.get("t3").path("matches").size(), "全字命中 NEEDLE + standalone needle 两处");
    }
}
