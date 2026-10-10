package dev.everyagent.worker.modules.search;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.event.Channels;
import dev.everyagent.plugin.api.spi.SearchProvider;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 统一 {@code search} RPC 单测(架构 §5.5/§8.5),不依赖 Spring:
 * ① 四要素入参(workspace 必填 jailed / pattern / kinds 分派 / filters 不透明透传);
 * ② 核心零类型感知的聚合行为:kind 分派(缺省=全部)、跨 provider 同 kind 同位置去重、
 *    各 provider 自身触顶的「或」、rpc.data 分批、无 provider 时可读报错;
 * ③ provider 护栏:异常/超时 WARN 跳过、rg 不可用可读错误;
 * ④ 内置三引擎(经 kinds 固定单类)的等价旧语义:内容聚合、文件名 basename、包含/排除、范围、大小写。
 * 真实 RpcDispatcher 分发 + mock HubLink 捕获出站帧;环境无 rg 时进程类用例跳过(assumeTrue)。
 */
class SearchServiceTest {

    @TempDir
    Path tempDir;

    private RpcDispatcher dispatcher;
    private WorkspaceManager workspaces;
    private SearchProviderRegistry registry;
    private SearchService service;
    private WorkerProperties props;
    private boolean ready;
    private Path ws;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(tempDir.resolve(".git")); // rg 仓库边界,与 TEMP 位置解耦
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
        registry = new SearchProviderRegistry();
        service = builtInService(dispatcher, workspaces, registry, props);
    }

    /** 装配带内置三引擎的 SearchService。 */
    private static SearchService builtInService(RpcDispatcher d, WorkspaceManager w,
            SearchProviderRegistry reg, WorkerProperties p) {
        RgSearchEngine engine = new RgSearchEngine(new RipgrepBinary(p), p);
        reg.register(new FileContentSearchProvider(engine, p));
        reg.register(new FileNameSearchProvider(engine, p));
        reg.register(new TaskSearchProvider(new TaskStore(p), engine, p));
        return new SearchService(d, w, reg, p);
    }

    // ---- 帮助:夹具 / 收发 ----

    /** 内容夹具:a.txt 两行命中、sub/b.txt 一行(大写)、.hidden/h.txt 一行、c.txt 无、meta.log 一行。 */
    private void seed() throws Exception {
        Files.createDirectories(ws.resolve("sub"));
        Files.createDirectories(ws.resolve(".hidden"));
        Files.writeString(ws.resolve("a.txt"), "needle one\nnope here\nneedle two\n");
        Files.writeString(ws.resolve("sub/b.txt"), "NEEDLE upper\nplain\n");
        Files.writeString(ws.resolve(".hidden/h.txt"), "hidden needle\n");
        Files.writeString(ws.resolve("c.txt"), "nothing here\n");
        Files.writeString(ws.resolve("meta.log"), "needle in log\n");
    }

    /** 文件名夹具:needle.txt、sub/NEEDLE-log.md、sub/other.txt、zz.txt。 */
    private void seedNames() throws Exception {
        Files.createDirectories(ws.resolve("sub"));
        Files.writeString(ws.resolve("needle.txt"), "内容无关");
        Files.writeString(ws.resolve("sub/NEEDLE-log.md"), "内容无关");
        Files.writeString(ws.resolve("sub/other.txt"), "needle 在内容里但文件名不匹配");
        Files.writeString(ws.resolve("zz.txt"), "needle");
    }

    private ObjectNode params(String pattern, Consumer<ObjectNode> tune) {
        ObjectNode p = Json.obj()
                .put("workspace", ws.toAbsolutePath().normalize().toString())
                .put("pattern", pattern);
        if (tune != null) {
            tune.accept(p);
        }
        return p;
    }

    private static ArrayNode arr(String... values) {
        ArrayNode a = Json.arr();
        for (String v : values) {
            a.add(v);
        }
        return a;
    }

    /** 发起一次 search(经真实分发器),返回末帧 {event, payload};rpc.data 批次逐项收集进 sink。 */
    private JsonNode call(RpcDispatcher d, ObjectNode params, List<JsonNode> sink) throws Exception {
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
            if ("rpc.data".equals(args[1])) {
                if (sink != null) {
                    ((JsonNode) args[3]).path("batch").forEach(sink::add);
                }
                return null;
            }
            replied.countDown();
            return null;
        }).when(link).pub(any(), any(), any(), any(), any());

        ObjectNode payload = Json.obj().put("reqId", "req-" + System.nanoTime()).put("method", RpcMethods.SEARCH);
        payload.set("params", params);
        ObjectNode frame = Json.obj().put("channel", Channels.workerCmd("k", "w")).put("event", "rpc");
        frame.set("payload", payload);
        d.onHubMessage(link, frame);

        assertTrue(replied.await(30, TimeUnit.SECONDS), "search 未在 30s 内应答");
        Object[] last;
        synchronized (frames) {
            last = frames.get(frames.size() - 1);
        }
        return Json.obj().put("event", (String) last[1]).set("payload", (JsonNode) last[3]);
    }

    private JsonNode call(ObjectNode params) throws Exception {
        return call(dispatcher, params, null);
    }

    /** 内联应答:断言 ok 并返回 items。 */
    private List<JsonNode> inlineItems(ObjectNode params) throws Exception {
        JsonNode reply = call(params);
        assertEquals("rpc.ok", reply.path("event").asString(), reply.toString());
        return toList(reply.path("payload").path("result").path("items"));
    }

    private static List<JsonNode> toList(JsonNode arr) {
        List<JsonNode> out = new ArrayList<>();
        arr.forEach(out::add);
        return out;
    }

    private static Map<String, List<JsonNode>> byPath(List<JsonNode> items) {
        Map<String, List<JsonNode>> m = new HashMap<>();
        for (JsonNode it : items) {
            m.computeIfAbsent(it.path("path").asString(), k -> new ArrayList<>()).add(it);
        }
        return m;
    }

    // ---- ① 参数校验 ----

    @Test
    void missingWorkspaceRejected() throws Exception {
        JsonNode reply = call(Json.obj().put("pattern", "x"));
        assertEquals("rpc.err", reply.path("event").asString(), reply.toString());
        assertEquals("BAD_PARAMS", reply.path("payload").path("code").asString());
    }

    @Test
    void missingPatternRejected() throws Exception {
        seed();
        JsonNode reply = call(Json.obj().put("workspace", ws.toString()));
        assertEquals("rpc.err", reply.path("event").asString(), reply.toString());
        assertEquals("BAD_PARAMS", reply.path("payload").path("code").asString());
    }

    @Test
    void emptyRegistryReadableError() throws Exception {
        RpcDispatcher d = new RpcDispatcher(null, new WorkerProperties());
        new SearchService(d, workspaces, new SearchProviderRegistry(), props);
        JsonNode reply = call(d, params("x", null), null);
        assertEquals("rpc.err", reply.path("event").asString(), reply.toString());
        assertEquals("BAD_PARAMS", reply.path("payload").path("code").asString());
        assertTrue(reply.path("payload").path("message").asString().contains("搜索提供者"),
                reply.toString());
    }

    @Test
    void unknownKindsReadableError() throws Exception {
        JsonNode reply = call(params("x", p -> p.set("kinds", arr("no-such-kind"))));
        assertEquals("rpc.err", reply.path("event").asString(), reply.toString());
        assertEquals("BAD_PARAMS", reply.path("payload").path("code").asString());
    }

    // ---- ② kind 分派 ----

    @Test
    void contentKindRunsContentProviderOnly() throws Exception {
        Assumptions.assumeTrue(ready, "环境无 rg,跳过真实进程用例");
        seed();
        seedNames();
        Map<String, List<JsonNode>> byPath = byPath(inlineItems(params("needle",
                p -> p.set("kinds", arr("file-content")))));
        assertTrue(byPath.containsKey("a.txt"), byPath.keySet().toString());
        assertTrue(byPath.containsKey("sub/b.txt"));
        assertTrue(byPath.containsKey(".hidden/h.txt"), " --hidden 生效");
        assertNull(byPath.get("c.txt"), "无命中文件不出现");
        assertFalse(byPath.containsKey("needle.txt"), "file-name 结果不混入 file-content 分派");
        JsonNode m1 = byPath.get("a.txt").get(0);
        assertEquals("file-content", m1.path("kind").asString());
        assertEquals("builtin.file-content", m1.path("providerId").asString());
        assertEquals(1, m1.path("lineNumber").asInt());
        assertEquals("needle one", m1.path("line").asString());
        assertEquals(0, m1.path("matchIndex").asInt());
        assertEquals("needle", m1.path("matchText").asString());
        assertEquals(2, byPath.get("a.txt").size());
    }

    @Test
    void fileNameKindRunsNameProviderOnly() throws Exception {
        Assumptions.assumeTrue(ready, "环境无 rg,跳过真实进程用例");
        seed();
        seedNames();
        List<JsonNode> items = inlineItems(params("needle", p -> p.set("kinds", arr("file-name"))));
        Map<String, List<JsonNode>> byPath = byPath(items);
        assertEquals(2, items.size(), byPath.keySet().toString());
        assertTrue(byPath.containsKey("needle.txt") && byPath.containsKey("sub/NEEDLE-log.md"),
                byPath.keySet().toString());
        for (JsonNode it : items) {
            assertEquals("file-name", it.path("kind").asString());
            assertEquals("builtin.file-name", it.path("providerId").asString());
            assertFalse(it.has("lineNumber"), "file-name 项无 lineNumber/matches");
        }
    }

    @Test
    void omittedKindsRunsAllRegisteredProviders() throws Exception {
        Assumptions.assumeTrue(ready, "环境无 rg,跳过真实进程用例");
        seed();
        seedNames();
        List<JsonNode> items = inlineItems(params("needle", null)); // 不传 kinds = 全部
        Set<String> kinds = new java.util.HashSet<>();
        items.forEach(it -> kinds.add(it.path("kind").asString()));
        assertTrue(kinds.contains("file-content"), kinds.toString());
        assertTrue(kinds.contains("file-name"), kinds.toString());
    }

    // ---- ③ filters 不透明透传 + provider 自解释 ----

    @Test
    void caseSensitiveFilterPassedThrough() throws Exception {
        Assumptions.assumeTrue(ready, "环境无 rg,跳过真实进程用例");
        seed();
        // 缺省忽略大小写:sub/b.txt(NEEDLE)命中
        Map<String, List<JsonNode>> ci = byPath(inlineItems(params("needle",
                p -> p.set("kinds", arr("file-content")))));
        assertTrue(ci.containsKey("sub/b.txt"));
        // 过滤袋 file-content.caseSensitive=true:NEEDLE 不再命中
        Map<String, List<JsonNode>> cs = byPath(inlineItems(params("needle", p -> {
            p.set("kinds", arr("file-content"));
            p.set("filters", Json.obj().put("file-content.caseSensitive", true));
        })));
        assertNull(cs.get("sub/b.txt"), cs.keySet().toString());
        assertTrue(cs.containsKey("a.txt"));
    }

    @Test
    void wholeWordFilterPassedThrough() throws Exception {
        Assumptions.assumeTrue(ready, "环境无 rg,跳过真实进程用例");
        Files.createDirectories(ws);
        Files.writeString(ws.resolve("cat.txt"), "cat catalog\nconcat cat\n");
        List<JsonNode> items = inlineItems(params("cat", p -> {
            p.set("kinds", arr("file-content"));
            p.set("filters", Json.obj().put("file-content.wholeWord", true));
        }));
        assertEquals(2, items.size(), items.toString());
        assertEquals(0, items.get(0).path("matchIndex").asInt());
        assertEquals(7, items.get(1).path("matchIndex").asInt());
    }

    @Test
    void includeFilterWithNamespaceKey() throws Exception {
        Assumptions.assumeTrue(ready, "环境无 rg,跳过真实进程用例");
        seed();
        Map<String, List<JsonNode>> inc = byPath(inlineItems(params("needle", p -> {
            p.set("kinds", arr("file-content"));
            p.set("filters", Json.obj().put("file-content.include", "*.txt"));
        })));
        assertNull(inc.get("meta.log"), "include 链外文件被剪: " + inc.keySet());
        assertTrue(inc.containsKey("a.txt"));
    }

    @Test
    void scopeFilterJailedAndPushedDownAsSearchPath() throws Exception {
        Assumptions.assumeTrue(ready, "环境无 rg,跳过真实进程用例");
        seed();
        Map<String, List<JsonNode>> scoped = byPath(inlineItems(params("needle", p -> {
            p.set("kinds", arr("file-content"));
            p.set("filters", Json.obj().put("file-content.scope", "sub"));
        })));
        assertTrue(scoped.containsKey("sub/b.txt"), scoped.keySet().toString());
        assertFalse(scoped.containsKey("a.txt"), "范围外文件不命中: " + scoped.keySet());

        // 越界范围拒收(scope 经沙箱 resolveExisting jailed)→ rpc.err
        JsonNode denied = call(params("needle", p -> {
            p.set("kinds", arr("file-content"));
            p.set("filters", Json.obj().put("file-content.scope", "../"));
        }));
        assertEquals("rpc.err", denied.path("event").asString(), denied.toString());
        // 不存在范围拒收
        JsonNode missing = call(params("needle", p -> {
            p.set("kinds", arr("file-content"));
            p.set("filters", Json.obj().put("file-content.scope", "no-such-dir"));
        }));
        assertEquals("rpc.err", missing.path("event").asString(), missing.toString());
    }

    @Test
    void invalidRegexRejectedWithBadParams() throws Exception {
        Assumptions.assumeTrue(ready, "环境无 rg,跳过真实进程用例");
        Files.writeString(ws.resolve("r.txt"), "x\n");
        JsonNode reply = call(params("[", p -> {
            p.set("kinds", arr("file-content"));
            p.set("filters", Json.obj().put("file-content.isRegex", true));
        }));
        assertEquals("rpc.err", reply.path("event").asString(), reply.toString());
        assertEquals("BAD_PARAMS", reply.path("payload").path("code").asString());
        assertTrue(reply.path("payload").path("message").asString().contains("正则"), reply.toString());
    }

    // ---- ④ 上限 / 分批 ----

    @Test
    void perProviderCapTruncatesAndFlagsTruncated() throws Exception {
        Assumptions.assumeTrue(ready, "环境无 rg,跳过真实进程用例");
        seed();
        props.getSearch().setFileMaxResults(2); // provider 自持上限(核心无 maxResults 入参)
        JsonNode reply = call(params("needle", p -> p.set("kinds", arr("file-content"))));
        assertEquals("rpc.ok", reply.path("event").asString(), reply.toString());
        JsonNode result = reply.path("payload").path("result");
        assertTrue(result.path("truncated").asBoolean(), "触顶应标记截断: " + result);
        assertEquals(2, result.path("matchCount").asInt());
    }

    @Test
    void bigResultBatchedIntoRpcDataWithSummaryOk() throws Exception {
        Assumptions.assumeTrue(ready, "环境无 rg,跳过真实进程用例");
        Files.createDirectories(ws);
        Files.writeString(ws.resolve("a.txt"), "needle one\nneedle two\nneedle three\n");
        props.getSearch().setInlineMaxBytes(1);
        props.getSearch().setChunkBytes(1);
        List<JsonNode> parts = new ArrayList<>();
        JsonNode reply = call(dispatcher, params("needle", p -> p.set("kinds", arr("file-content"))), parts);
        assertEquals("rpc.ok", reply.path("event").asString(), reply.toString());
        JsonNode summary = reply.path("payload").path("result");
        assertFalse(summary.has("items"), "分批路径末帧只带汇总: " + summary);
        assertEquals(3, summary.path("matchCount").asInt());
        assertEquals(3, summary.path("itemCount").asInt());
        assertEquals(3, parts.size(), "chunk-bytes=1 → 每个结果项独占一批: " + parts.size());
    }

    // ---- ⑤ provider 聚合 / 护栏 ----

    @Test
    void crossProviderSameKindSamePositionDeduped() throws Exception {
        SearchProviderRegistry reg = new SearchProviderRegistry();
        reg.register(stubProvider("dup-a", Set.of("dup"), null, dimItem("dup", "k1"), false));
        reg.register(stubProvider("dup-b", Set.of("dup"), null, dimItem("dup", "k1"), false));
        RpcDispatcher d = new RpcDispatcher(null, new WorkerProperties());
        new SearchService(d, workspaces, reg, props);
        List<JsonNode> items = toList(call(d, params("x", p -> p.set("kinds", arr("dup"))), null)
                .path("payload").path("result").path("items"));
        assertEquals(1, items.size(), "同 kind 同位置去重: " + items);
        assertEquals("dup-a", items.get(0).path("providerId").asString(), "先注册者胜(内置/插件 order)");
    }

    @Test
    void crossProviderDifferentPositionKept() throws Exception {
        SearchProviderRegistry reg = new SearchProviderRegistry();
        reg.register(stubProvider("dup-a", Set.of("dup"), null, dimItem("dup", "k1"), false));
        reg.register(stubProvider("dup-b", Set.of("dup"), null, dimItem("dup", "k2"), false));
        RpcDispatcher d = new RpcDispatcher(null, new WorkerProperties());
        new SearchService(d, workspaces, reg, props);
        List<JsonNode> items = toList(call(d, params("x", p -> p.set("kinds", arr("dup"))), null)
                .path("payload").path("result").path("items"));
        assertEquals(2, items.size(), items.toString());
    }

    @Test
    void providerFieldsProviderIdSelfRespectedScorePassedKindAndFieldsFlattened() throws Exception {
        SearchProviderRegistry reg = new SearchProviderRegistry();
        Map<String, Object> fields = new java.util.LinkedHashMap<>();
        fields.put("path", "x.md");
        fields.put("lineNumber", 7);
        reg.register(stubProvider("plugin-a", Set.of("plugin"), null,
                new SearchProvider.SearchResultItem("plugin", "self.plugin", 0.75, "pk", fields), false));
        RpcDispatcher d = new RpcDispatcher(null, new WorkerProperties());
        new SearchService(d, workspaces, reg, props);
        JsonNode item = toList(call(d, params("x", p -> p.set("kinds", arr("plugin"))), null)
                .path("payload").path("result").path("items")).get(0);
        assertEquals("plugin", item.path("kind").asString());
        assertEquals("self.plugin", item.path("providerId").asString(), "自带 providerId 尊重不覆盖");
        assertEquals(0.75, item.path("score").asDouble(), "score 透传");
        assertEquals("x.md", item.path("path").asString(), "字段袋展开到顶层");
        assertEquals(7, item.path("lineNumber").asInt());
        assertFalse(item.has("fields"), "不保留嵌套 fields 键");
    }

    @Test
    void providerTruncationOrAggregated() throws Exception {
        SearchProviderRegistry reg = new SearchProviderRegistry();
        reg.register(stubProvider("t", Set.of("t"), null, dimItem("t", "k1"), true));
        RpcDispatcher d = new RpcDispatcher(null, new WorkerProperties());
        new SearchService(d, workspaces, reg, props);
        JsonNode result = call(d, params("x", p -> p.set("kinds", arr("t"))), null)
                .path("payload").path("result");
        assertTrue(result.path("truncated").asBoolean(), "任一 provider 触顶 → 应答 truncated=true");
    }

    @Test
    void providerExceptionSkippedOthersStillMerged() throws Exception {
        Assumptions.assumeTrue(ready, "环境无 rg,跳过真实进程用例");
        seed();
        registry.register(stubProvider("boom", Set.of("boom"), new RuntimeException("boom"), null, false));
        JsonNode reply = call(params("needle", p -> p.set("kinds", arr("file-content", "boom"))));
        assertEquals("rpc.ok", reply.path("event").asString(), "坏 provider 仅 WARN 跳过");
        List<JsonNode> items = toList(reply.path("payload").path("result").path("items"));
        assertTrue(items.stream().anyMatch(it -> "a.txt".equals(it.path("path").asString())),
                "内置结果不受坏 provider 影响: " + items);
    }

    @Test
    void providerExceedingTimeoutBudgetSkipped() throws Exception {
        Assumptions.assumeTrue(ready, "环境无 rg,跳过真实进程用例");
        seed();
        registry.register(slowProvider("slow", Set.of("slow"), 2_000));
        service.setProviderTimeoutMs(50);
        long start = System.nanoTime();
        JsonNode reply = call(params("needle", p -> p.set("kinds", arr("file-content", "slow"))));
        long elapsed = (System.nanoTime() - start) / 1_000_000;
        assertEquals("rpc.ok", reply.path("event").asString(), reply.toString());
        assertTrue(elapsed < 1_500, "超时预算应截断慢 provider: " + elapsed + "ms");
        List<JsonNode> items = toList(reply.path("payload").path("result").path("items"));
        assertTrue(items.stream().anyMatch(it -> "a.txt".equals(it.path("path").asString())));
        assertFalse(items.stream().anyMatch(it -> "slow.md".equals(it.path("path").asString())),
                "超时 provider 结果不进聚合");
    }

    @Test
    void rgMissingReadableError() throws Exception {
        WorkerProperties bad = new WorkerProperties();
        bad.setHomeDir(tempDir.resolve("home2").toString());
        bad.getTools().setRgPath(tempDir.resolve("no-such-rg").toString());
        RpcDispatcher d = new RpcDispatcher(null, new WorkerProperties());
        builtInService(d, workspaces, new SearchProviderRegistry(), bad);
        JsonNode reply = call(d, params("x", p -> p.set("kinds", arr("file-content"))), null);
        assertEquals("rpc.err", reply.path("event").asString(), reply.toString());
        assertEquals("INTERNAL", reply.path("payload").path("code").asString(), reply.toString());
        assertTrue(reply.path("payload").path("message").asString().contains("rg 不可用"), reply.toString());
    }

    // ---- 桩 provider ----

    private static SearchProvider.SearchResultItem dimItem(String kind, String positionKey) {
        Map<String, Object> fields = new java.util.LinkedHashMap<>();
        fields.put("path", "dim.md");
        return new SearchProvider.SearchResultItem(kind, null, null, positionKey, fields);
    }

    private static SearchProvider stubProvider(String id, Set<String> kinds, RuntimeException error,
            SearchProvider.SearchResultItem item, boolean truncated) {
        return new StubProvider(id, kinds, error, 0,
                new SearchProvider.SearchResult(item == null ? List.of() : List.of(item), truncated));
    }

    private static SearchProvider slowProvider(String id, Set<String> kinds, long delayMs) {
        return new StubProvider(id, kinds, null, delayMs, SearchProvider.SearchResult.empty());
    }

    private static final class StubProvider implements SearchProvider {
        private final String id;
        private final Set<String> kinds;
        private final RuntimeException error;
        private final long delayMs;
        private final SearchResult result;

        StubProvider(String id, Set<String> kinds, RuntimeException error, long delayMs, SearchResult result) {
            this.id = id;
            this.kinds = kinds;
            this.error = error;
            this.delayMs = delayMs;
            this.result = result;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public Set<String> kinds() {
            return kinds;
        }

        @Override
        public SearchResult search(SearchRequest req) {
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (error != null) {
                throw error;
            }
            return result;
        }
    }
}