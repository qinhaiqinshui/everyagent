package dev.everyagent.worker.config;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.event.Channels;
import dev.everyagent.plugin.api.spi.SearchProvider;
import dev.everyagent.plugin.api.spi.SuggestionProvider;
import dev.everyagent.worker.hub.HubLink;
import dev.everyagent.worker.modules.WorkspaceManager;
import dev.everyagent.worker.modules.search.FileContentSearchProvider;
import dev.everyagent.worker.modules.search.RgSearchEngine;
import dev.everyagent.worker.modules.search.SearchService;
import dev.everyagent.worker.modules.search.TaskSearchProvider;
import dev.everyagent.worker.plugin.registry.SearchProviderRegistry;
import dev.everyagent.worker.proto.RpcMethods;
import dev.everyagent.worker.rpc.RpcDispatcher;
import dev.everyagent.worker.slash.SlashCommandRegistry;
import dev.everyagent.worker.slash.SlashMethods;
import dev.everyagent.worker.slash.SlashTaskScopeStore;
import dev.everyagent.worker.task.TaskStore;
import dev.everyagent.worker.tools.RipgrepBinary;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 搜索限制配置化(架构 §8.5「搜索限制配置化」/§7.17)测试:
 * <ol>
 *   <li>默认值与收编前的散落常量行为完全一致(六键:rg 超时 60s、文件 1000、任务 500、
 *       内联 262144、切批 196608、provider 预算 0 不限时);</li>
 *   <li>Spring Binder 把 {@code worker.search.*} 自定义值(relaxed kebab-case)绑进
 *       {@link WorkerProperties};</li>
 *   <li>配置生效到统一 {@code search} / {@code mention.query} 服务:provider 超时预算(慢
 *       provider 被跳过)、file-max-results / task-max-results(provider 自持上限)、rg 进程超时
 *       (假 rg 挂起被强杀返回已完成部分)、应答内联/切批阈值(小阈值强制走 rpc.data 分批)。</li>
 * </ol>
 * 真实 RpcDispatcher 分发 + mock HubLink 捕获出站帧;依赖真实 rg 的用例按环境栅栏跳过。
 */
class WorkerSearchPropertiesTest {

    @TempDir
    Path tempDir;

    // ---- ① 默认值 ----

    @Test
    void searchDefaultsMatchLegacyBehavior() {
        WorkerProperties.Search s = new WorkerProperties().getSearch();
        assertEquals(60_000L, s.getRgTimeoutMs(), "rg-timeout-ms 默认 60s");
        assertEquals(1000, s.getFileMaxResults(), "file-max-results 默认 1000");
        assertEquals(500, s.getTaskMaxResults(), "task-max-results 默认 500");
        assertEquals(262144, s.getInlineMaxBytes(), "inline-max-bytes 默认 256K(同 fs.read)");
        assertEquals(196608, s.getChunkBytes(), "chunk-bytes 默认 192K(同 fs.read)");
        assertEquals(0L, s.getProviderTimeoutMs(), "provider-timeout-ms 默认 0(不限时)");
    }

    @Test
    void setSearchNullCoalescesToDefaults() {
        WorkerProperties props = new WorkerProperties();
        props.setSearch(null);
        assertNotNull(props.getSearch());
        assertEquals(60_000L, props.getSearch().getRgTimeoutMs());
    }

    // ---- ② Spring 绑定(worker.search.* → WorkerProperties) ----

    @Test
    void binderBindsCustomSearchValues() {
        Map<String, Object> src = new LinkedHashMap<>();
        src.put("worker.search.rg-timeout-ms", "1234");
        src.put("worker.search.file-max-results", "7");
        src.put("worker.search.task-max-results", "8");
        src.put("worker.search.inline-max-bytes", "100");
        src.put("worker.search.chunk-bytes", "60");
        src.put("worker.search.provider-timeout-ms", "50");

        WorkerProperties props = new Binder(new MapConfigurationPropertySource(src))
                .bind("worker", Bindable.of(WorkerProperties.class)).get();

        assertEquals(1234L, props.getSearch().getRgTimeoutMs());
        assertEquals(7, props.getSearch().getFileMaxResults());
        assertEquals(8, props.getSearch().getTaskMaxResults());
        assertEquals(100, props.getSearch().getInlineMaxBytes());
        assertEquals(60, props.getSearch().getChunkBytes());
        assertEquals(50L, props.getSearch().getProviderTimeoutMs());
    }

    // ---- ③ 配置生效到服务 ----

    @Test
    void providerTimeoutFromConfigSkipsSlowProviderOnSearch() throws Exception {
        WorkerProperties props = baseProps();
        props.getSearch().setProviderTimeoutMs(50); // 配置注入预算(不经 setProviderTimeoutMs 接缝)
        RpcDispatcher dispatcher = new RpcDispatcher(null, new WorkerProperties());
        SearchProviderRegistry registry = new SearchProviderRegistry();
        registry.register(itemProvider("slow", 2_000, "slow.md"));
        registry.register(itemProvider("fast", 0, "fast.md"));
        new SearchService(dispatcher, mockWorkspaces(), registry, props);

        long start = System.nanoTime();
        JsonNode reply = call(dispatcher, RpcMethods.SEARCH, searchParams(arr("x")), null);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals("rpc.ok", reply.path("event").asString(), reply.toString());
        List<JsonNode> items = itemsOf(reply);
        assertEquals(1, items.size(), "慢 provider 被超时预算跳过,仅快 provider 命中: " + items);
        assertEquals("fast.md", items.get(0).path("path").asString());
        assertTrue(elapsedMs < 1_500, "配置的 50ms 预算应截断慢 provider: " + elapsedMs + "ms");
    }

    @Test
    void providerTimeoutFromConfigSkipsSlowSuggesterOnMentionQuery() throws Exception {
        WorkerProperties props = baseProps();
        props.getSearch().setProviderTimeoutMs(50);
        Path ws = seedWorkspace();
        Files.writeString(ws.resolve("readme.md"), "x");
        RpcDispatcher dispatcher = new RpcDispatcher(null, new WorkerProperties());
        SearchProviderRegistry registry = new SearchProviderRegistry();
        registry.register(new StubSuggester("slow", 0f,
                List.of(new SuggestionProvider.Suggestion("slow/y.md")), 2_000));
        registry.register(new StubSuggester("fast", 10f,
                List.of(new SuggestionProvider.Suggestion("fast/x.md")), 0));
        new SlashMethods(dispatcher, new SlashCommandRegistry(), mockWorkspaces(),
                mock(SlashTaskScopeStore.class), registry, props);

        long start = System.nanoTime();
        JsonNode reply = call(dispatcher, RpcMethods.MENTION_QUERY, Json.obj()
                .put("workspace", ws.toString()).put("path", ".").put("query", "read"), null);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals("rpc.ok", reply.path("event").asString(), reply.toString());
        JsonNode entries = reply.path("payload").path("result").path("entries");
        assertTrue(entries.size() >= 1, entries.toString());
        for (JsonNode e : entries) {
            assertFalse("slow/y.md".equals(e.path("path").asString()),
                    "超时 suggester 的建议不应进入应答: " + entries);
        }
        assertTrue(containsPath(entries, "fast/x.md"), "快 suggester 照常并入: " + entries);
        assertTrue(containsPath(entries, "readme.md"), "内置结果不受影响: " + entries);
        assertTrue(elapsedMs < 1_500, "配置的 50ms 预算应截断慢 suggester: " + elapsedMs + "ms");
    }

    @Test
    void fileMaxResultsFromConfigCapsProviderDefault() throws Exception {
        Assumptions.assumeTrue(locateRg() != null, "环境无 rg,跳过真实进程用例");
        WorkerProperties props = baseProps();
        props.getTools().setRgPath(locateRg().toString());
        props.getSearch().setFileMaxResults(2); // provider 自持上限(请求不传 maxResults)
        Path ws = seedWorkspace();
        Files.writeString(ws.resolve("a.txt"), "needle one\nnope\nneedle two\n");
        Files.writeString(ws.resolve("b.txt"), "needle three\n");
        RpcDispatcher dispatcher = new RpcDispatcher(null, new WorkerProperties());
        SearchProviderRegistry registry = new SearchProviderRegistry();
        RgSearchEngine engine = new RgSearchEngine(new RipgrepBinary(props), props);
        registry.register(new FileContentSearchProvider(engine, props));
        new SearchService(dispatcher, mockWorkspaces(), registry, props);

        JsonNode reply = call(dispatcher, RpcMethods.SEARCH, searchParams(arr("file-content")), null);
        assertEquals("rpc.ok", reply.path("event").asString(), reply.toString());
        JsonNode result = reply.path("payload").path("result");
        assertEquals(2, result.path("matchCount").asInt(), "缺省上限应取 file-max-results=2");
        assertTrue(result.path("truncated").asBoolean(), "触顶应标记截断: " + result);
    }

    @Test
    void taskMaxResultsFromConfigCapsProviderDefault() throws Exception {
        Assumptions.assumeTrue(locateRg() != null, "环境无 rg,跳过真实进程用例");
        WorkerProperties props = baseProps();
        props.getTools().setRgPath(locateRg().toString());
        props.getSearch().setTaskMaxResults(2); // provider 自持上限(请求不传 maxResults)
        seedTask(props, "defaultworkspace", "t1", 3);
        RpcDispatcher dispatcher = new RpcDispatcher(null, new WorkerProperties());
        SearchProviderRegistry registry = new SearchProviderRegistry();
        RgSearchEngine engine = new RgSearchEngine(new RipgrepBinary(props), props);
        registry.register(new TaskSearchProvider(new TaskStore(props), engine, props));
        new SearchService(dispatcher, mockWorkspaces(), registry, props);

        JsonNode reply = call(dispatcher, RpcMethods.SEARCH, searchParams(arr("task")), null);
        assertEquals("rpc.ok", reply.path("event").asString(), reply.toString());
        JsonNode result = reply.path("payload").path("result");
        assertEquals(2, result.path("matchCount").asInt(), "缺省上限应取 task-max-results=2");
        assertTrue(result.path("truncated").asBoolean(), "触顶应标记截断: " + result);
    }

    @Test
    void rgTimeoutFromConfigKillsHangingRg() throws Exception {
        boolean win = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win");
        Assumptions.assumeTrue(win, "假 rg 夹具为 Windows 批处理,非 Windows 跳过");
        Path fake = tempDir.resolve("fake-rg.cmd");
        String match = "{\"type\":\"match\",\"data\":{\"path\":{\"text\":\"a.txt\"},"
                + "\"lines\":{\"text\":\"needle\\\\n\"},\"line_number\":1,"
                + "\"submatches\":[{\"match\":{\"text\":\"needle\"},\"start\":0,\"end\":6}]}}";
        Files.writeString(fake, "@echo off\r\necho " + match + "\r\nping -n 4 127.0.0.1 >nul\r\n",
                StandardCharsets.ISO_8859_1);
        WorkerProperties props = baseProps();
        props.getTools().setRgPath(fake.toString());
        props.getSearch().setRgTimeoutMs(800);
        Path ws = seedWorkspace();
        Files.writeString(ws.resolve("a.txt"), "needle\n");
        RpcDispatcher dispatcher = new RpcDispatcher(null, new WorkerProperties());
        SearchProviderRegistry registry = new SearchProviderRegistry();
        RgSearchEngine engine = new RgSearchEngine(new RipgrepBinary(props), props);
        registry.register(new FileContentSearchProvider(engine, props));
        new SearchService(dispatcher, mockWorkspaces(), registry, props);

        long start = System.nanoTime();
        JsonNode reply = call(dispatcher, RpcMethods.SEARCH, searchParams(arr("file-content")), null);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals("rpc.ok", reply.path("event").asString(), reply.toString());
        JsonNode result = reply.path("payload").path("result");
        assertTrue(result.path("truncated").asBoolean(),
                "rg-timeout-ms=800 应强杀挂起的 rg 并置 truncated: " + result);
        assertEquals(1, result.path("matchCount").asInt(), "超时应返回已完成部分(1 项)");
        assertTrue(elapsedMs < 10_000, "不应等满假 rg 的 3s 挂起之后太久: " + elapsedMs + "ms");
    }

    @Test
    void inlineAndChunkThresholdsFromConfigShapeRpcDataBatches() throws Exception {
        Assumptions.assumeTrue(locateRg() != null, "环境无 rg,跳过真实进程用例");
        WorkerProperties props = baseProps();
        props.getTools().setRgPath(locateRg().toString());
        props.getSearch().setInlineMaxBytes(1);
        props.getSearch().setChunkBytes(1);
        Path ws = seedWorkspace();
        Files.writeString(ws.resolve("a.txt"), "needle one\n");
        Files.writeString(ws.resolve("b.txt"), "needle two\n");
        RpcDispatcher dispatcher = new RpcDispatcher(null, new WorkerProperties());
        SearchProviderRegistry registry = new SearchProviderRegistry();
        RgSearchEngine engine = new RgSearchEngine(new RipgrepBinary(props), props);
        registry.register(new FileContentSearchProvider(engine, props));
        new SearchService(dispatcher, mockWorkspaces(), registry, props);

        List<JsonNode> parts = new ArrayList<>();
        JsonNode reply = call(dispatcher, RpcMethods.SEARCH, searchParams(arr("file-content")), parts);
        assertEquals("rpc.ok", reply.path("event").asString(), reply.toString());
        JsonNode summary = reply.path("payload").path("result");
        assertFalse(summary.has("items"), "分批路径末帧只带汇总: " + summary);
        assertEquals(2, summary.path("matchCount").asInt());
        assertEquals(2, summary.path("itemCount").asInt());
        assertEquals(2, parts.size(), "chunk-bytes=1 → 每个结果项独占一批: " + parts.size());
    }

    // ---- 帮助:props / 夹具 / 收发 ----

    private WorkerProperties baseProps() throws Exception {
        Files.createDirectories(tempDir.resolve(".git"));
        WorkerProperties props = new WorkerProperties();
        props.setHomeDir(tempDir.resolve("home").toString());
        return props;
    }

    private WorkspaceManager mockWorkspaces() throws Exception {
        Path ws = seedWorkspace();
        WorkspaceManager workspaces = mock(WorkspaceManager.class);
        when(workspaces.resolve(any())).thenReturn(new WorkspaceManager.Root(ws, ws.toRealPath()));
        when(workspaces.idOfRoot(any())).thenReturn("defaultworkspace");
        return workspaces;
    }

    private Path seedWorkspace() throws Exception {
        Path ws = tempDir.resolve("ws");
        Files.createDirectories(ws);
        return ws;
    }

    private ObjectNode searchParams(JsonNode kinds) throws Exception {
        ObjectNode p = Json.obj()
                .put("workspace", seedWorkspace().toAbsolutePath().normalize().toString())
                .put("pattern", "needle");
        p.set("kinds", kinds);
        return p;
    }

    private static JsonNode arr(String... values) {
        var a = Json.arr();
        for (String v : values) {
            a.add(v);
        }
        return a;
    }

    private static void seedTask(WorkerProperties props, String workspaceId, String taskId, int rounds)
            throws Exception {
        Path dir = props.resolveHomeDir().resolve("workspaces").resolve(workspaceId)
                .resolve("tasks").resolve(taskId);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("meta.json"),
                "{\"taskId\":\"" + taskId + "\",\"title\":\"T\",\"status\":\"done\""
                        + ",\"workspace\":\"/ws\",\"workspaceId\":\"" + workspaceId + "\"}");
        StringBuilder lines = new StringBuilder();
        for (int i = 1; i <= rounds; i++) {
            lines.append("{\"index\":").append(i).append(",\"startSeq\":\"1\",\"endSeq\":\"10\"")
                    .append(",\"user\":\"needle round ").append(i)
                    .append("\",\"finalReply\":\"plain\",\"subs\":[]}\n");
        }
        Files.writeString(dir.resolve("rounds.jsonl"), lines.toString());
    }

    private JsonNode call(RpcDispatcher dispatcher, String method, ObjectNode params,
            List<JsonNode> sink) throws Exception {
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
        ObjectNode payload = Json.obj().put("reqId", "req-" + System.nanoTime()).put("method", method);
        payload.set("params", params);
        ObjectNode frame = Json.obj().put("channel", Channels.workerCmd("k", "w")).put("event", "rpc");
        frame.set("payload", payload);
        dispatcher.onHubMessage(link, frame);
        assertTrue(replied.await(30, TimeUnit.SECONDS), method + " 未在 30s 内应答");
        Object[] last;
        synchronized (frames) {
            last = frames.get(frames.size() - 1);
        }
        return Json.obj().put("event", (String) last[1]).set("payload", (JsonNode) last[3]);
    }

    private static List<JsonNode> itemsOf(JsonNode reply) {
        List<JsonNode> out = new ArrayList<>();
        reply.path("payload").path("result").path("items").forEach(out::add);
        return out;
    }

    private static boolean containsPath(JsonNode entries, String path) {
        for (JsonNode e : entries) {
            if (path.equals(e.path("path").asString())) {
                return true;
            }
        }
        return false;
    }

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

    // ---- 桩 provider ----

    /** 桩统一搜索 provider:固定返回一条 {@code {path}} 结果项(kind=x);delayMs>0 时先睡再返回。 */
    private static SearchProvider itemProvider(String id, long delayMs, String path) {
        return new SearchProvider() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public Set<String> kinds() {
                return Set.of("x");
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
                Map<String, Object> fields = new LinkedHashMap<>();
                fields.put("path", path);
                return new SearchResult(
                        List.of(new SearchResultItem("x", null, null, path, fields)), false);
            }
        };
    }

    /** 桩 SuggestionProvider:固定返回建议;delayMs>0 时先睡再返回(慢 suggester 夹具)。 */
    private static final class StubSuggester implements SuggestionProvider {
        private final String id;
        private final float order;
        private final List<Suggestion> suggestions;
        private final long delayMs;

        StubSuggester(String id, float order, List<Suggestion> suggestions, long delayMs) {
            this.id = id;
            this.order = order;
            this.suggestions = suggestions;
            this.delayMs = delayMs;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public float order() {
            return order;
        }

        @Override
        public List<Suggestion> suggest(SuggestRequest req) {
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return suggestions;
        }
    }
}