package dev.everyagent.worker.slash;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.event.Channels;
import dev.everyagent.plugin.api.spi.SearchProvider;
import dev.everyagent.plugin.api.spi.SuggestionProvider;
import dev.everyagent.worker.hub.HubLink;
import dev.everyagent.worker.modules.WorkspaceManager;
import dev.everyagent.worker.plugin.registry.SearchProviderRegistry;
import dev.everyagent.worker.proto.RpcMethods;
import dev.everyagent.worker.rpc.RpcDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * mention.query 的 SuggestionProvider 增补聚合测试(架构 §8.5「能力接口扩展」),
 * 不依赖 Spring/真实 hub:真实 RpcDispatcher 分发 + mock HubLink 捕获出站帧
 * (SearchServiceTest 同款)。锁死四条语义:
 * <ol>
 *   <li>纯函数聚合({@link SlashMethods#appendProviderSuggestions}):内置在前、provider
 *       按 order() 升序追加(乱序注册同样得到升序)、按 {@code kind}+{@code path} 去重
 *       (kind 缺省归一 file、kind 不同不去重)、总条数截断 {@code limit}、坏 provider
 *       仅跳过、条目形状派生 name/fullPath;</li>
 *   <li>RPC 路径:搜索模式在内置四档打分结果之后并入 provider 建议,重复项去重,
 *       请求上下文(query/path)正确传给 provider;</li>
 *   <li>未注册能力 provider 时输出与现状完全一致(内置结果独占,provider 零调用);
 *       浏览模式(空 query)不经 provider;</li>
 *   <li>内置已达 10 条上限时跳过 provider;provider 抛异常不影响应答。</li>
 * </ol>
 */
class SlashMethodsMentionProviderTest {

    /** 与 SlashMethods.MENTION_LIMIT 同值(用户约束:最多 10 条)。 */
    private static final int MENTION_LIMIT = 10;

    @TempDir
    Path tempDir;

    private RpcDispatcher dispatcher;
    /** 共享注册表:RPC 用例在此注册桩建议 provider。 */
    private SearchProviderRegistry registry;
    private Path ws;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(ws = tempDir.resolve("ws"));
        dispatcher = new RpcDispatcher(null, new dev.everyagent.worker.config.WorkerProperties());
        // WorkspaceManager 打桩(同 GrantRegistryExecRootsTest 手法):resolve 恒落定临时 ws,
        // 免走包级 init()(modules 包外不可见);idOfRoot 默认 null —— 契约允许。
        WorkspaceManager workspaces = mock(WorkspaceManager.class);
        when(workspaces.resolve(any())).thenReturn(
                new WorkspaceManager.Root(ws, ws.toRealPath()));
        registry = new SearchProviderRegistry();
        new SlashMethods(dispatcher, new SlashCommandRegistry(), workspaces,
                mock(SlashTaskScopeStore.class), registry,
                new dev.everyagent.worker.config.WorkerProperties());
    }

    // ---- 帮助:桩 / 收发 ----

    /** 桩建议 provider:固定返回建议列表,或构造时给 error 则每次调用抛出;记录最近一次请求。 */
    private static final class StubSuggester implements SuggestionProvider {
        private final String id;
        private final float order;
        private final List<Suggestion> suggestions;
        private final RuntimeException error;
        private SuggestRequest lastReq;

        StubSuggester(String id, float order, List<Suggestion> suggestions) {
            this(id, order, suggestions, null);
        }

        StubSuggester(String id, float order, List<Suggestion> suggestions, RuntimeException error) {
            this.id = id;
            this.order = order;
            this.suggestions = suggestions;
            this.error = error;
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
            lastReq = req;
            if (error != null) {
                throw error;
            }
            return suggestions;
        }
    }

    /** 发起一次 mention.query(经真实分发器),返回末帧 {event, payload}。 */
    private JsonNode mention(String query) throws Exception {
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
            replied.countDown();
            return null;
        }).when(link).pub(any(), any(), any(), any(), any());
        ObjectNode payload = Json.obj()
                .put("reqId", "req-" + System.nanoTime())
                .put("method", RpcMethods.MENTION_QUERY);
        payload.set("params", Json.obj()
                .put("workspace", ws.toAbsolutePath().normalize().toString())
                .put("path", ".")
                .put("query", query));
        ObjectNode frame = Json.obj()
                .put("channel", Channels.workerCmd("k", "w"))
                .put("event", "rpc");
        frame.set("payload", payload);
        dispatcher.onHubMessage(link, frame);
        assertTrue(replied.await(30, TimeUnit.SECONDS), "mention.query 未在 30s 内应答");
        Object[] last;
        synchronized (frames) {
            last = frames.get(frames.size() - 1);
        }
        return Json.obj().put("event", (String) last[1]).set("payload", (JsonNode) last[3]);
    }

    /** 造一条内置形态的 entries 条目(kind 缺省 file)。 */
    private static ObjectNode builtInEntry(String path, String kind) {
        return Json.obj()
                .put("path", path)
                .put("name", path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path)
                .put("kind", kind)
                .put("fullPath", "/" + path);
    }

    private static SuggestionProvider.SuggestRequest anySuggestReq() {
        return new SuggestionProvider.SuggestRequest("ws1", Path.of("."), "q", ".");
    }

    // ---- ① 纯函数聚合 ----

    @Test
    void appendSuggestionsBuiltinFirstProvidersByOrderWithDedup() {
        ArrayNode entries = Json.arr();
        entries.add(builtInEntry("readme.md", "file"));
        SearchProviderRegistry reg = new SearchProviderRegistry();
        // 乱序注册:能力查询按 order 升序 → a(10f) 结果先于 b(20f)
        reg.register(new StubSuggester("b", 20f, List.of(
                new SuggestionProvider.Suggestion("z20.md"))));
        reg.register(new StubSuggester("a", 10f, List.of(
                new SuggestionProvider.Suggestion("readme.md"),           // kind+path 均同 → 去重
                new SuggestionProvider.Suggestion("docs", "directory"),   // kind 不同 → 保留
                new SuggestionProvider.Suggestion("docs"),                // 与上一条 path 同 kind 异 → 保留
                new SuggestionProvider.Suggestion("a10.md"))));

        SlashMethods.appendProviderSuggestions(entries, reg, anySuggestReq(), MENTION_LIMIT, 0);

        assertEquals(5, entries.size(), entries.toString());
        // 内置在前;provider 按 order 升序追加;a 内部保持返回序
        assertEquals("readme.md", entries.path(0).path("path").asString());
        assertEquals("docs", entries.path(1).path("path").asString());
        assertEquals("directory", entries.path(1).path("kind").asString());
        assertEquals("docs", entries.path(2).path("path").asString());
        assertEquals("file", entries.path(2).path("kind").asString(), "kind 缺省归一 file");
        assertEquals("a10.md", entries.path(3).path("path").asString());
        assertEquals("z20.md", entries.path(4).path("path").asString());
        // 条目形状:派生 name/fullPath(与内置 entry 一致)
        assertEquals("a10.md", entries.path(3).path("name").asString());
        assertEquals("/a10.md", entries.path(3).path("fullPath").asString());
        assertEquals("z20.md", entries.path(4).path("name").asString());
        assertEquals("/z20.md", entries.path(4).path("fullPath").asString());
    }

    @Test
    void appendSuggestionsCapsTotalAtLimit() {
        ArrayNode entries = Json.arr();
        for (int i = 0; i < 8; i++) {
            entries.add(builtInEntry("f" + i + ".md", "file"));
        }
        SearchProviderRegistry reg = new SearchProviderRegistry();
        reg.register(new StubSuggester("p", 0f, List.of(
                new SuggestionProvider.Suggestion("s1.md"),
                new SuggestionProvider.Suggestion("s2.md"),
                new SuggestionProvider.Suggestion("s3.md"),   // 超 10 条 → 截断
                new SuggestionProvider.Suggestion("s4.md"))));

        SlashMethods.appendProviderSuggestions(entries, reg, anySuggestReq(), MENTION_LIMIT, 0);

        assertEquals(MENTION_LIMIT, entries.size(), "总条数仍截断 10: " + entries.size());
        assertEquals("s1.md", entries.path(8).path("path").asString());
        assertEquals("s2.md", entries.path(9).path("path").asString());
    }

    @Test
    void appendSuggestionsSkipsWhenBuiltinAtLimitOrRegistryEmpty() {
        // 内置已达上限:零调用、零追加
        ArrayNode full = Json.arr();
        for (int i = 0; i < MENTION_LIMIT; i++) {
            full.add(builtInEntry("f" + i + ".md", "file"));
        }
        StubSuggester provider = new StubSuggester("p", 0f,
                List.of(new SuggestionProvider.Suggestion("x.md")));
        SearchProviderRegistry reg = new SearchProviderRegistry();
        reg.register(provider);
        SlashMethods.appendProviderSuggestions(full, reg, anySuggestReq(), MENTION_LIMIT, 0);
        assertEquals(MENTION_LIMIT, full.size());
        assertNull(provider.lastReq, "内置已触顶时 provider 不被调用");

        // 空注册表:零行为变化
        ArrayNode entries = Json.arr();
        entries.add(builtInEntry("a.md", "file"));
        SlashMethods.appendProviderSuggestions(entries, new SearchProviderRegistry(),
                anySuggestReq(), MENTION_LIMIT, 0);
        assertEquals(1, entries.size());
    }

    @Test
    void appendSuggestionsFailureSkippedOthersStillMerged() {
        ArrayNode entries = Json.arr();
        entries.add(builtInEntry("a.md", "file"));
        SearchProviderRegistry reg = new SearchProviderRegistry();
        reg.register(new StubSuggester("bad", 0f, List.of(),
                new RuntimeException("boom")));
        reg.register(new StubSuggester("good", 10f, List.of(
                new SuggestionProvider.Suggestion("g.md"))));

        SlashMethods.appendProviderSuggestions(entries, reg, anySuggestReq(), MENTION_LIMIT, 0);

        assertEquals(2, entries.size(), "坏 provider 跳过,好 provider 照常并入");
        assertEquals("g.md", entries.path(1).path("path").asString());
    }

    @Test
    void appendSuggestionsNullAndEmptyResultsNoop() {
        ArrayNode entries = Json.arr();
        entries.add(builtInEntry("a.md", "file"));
        SearchProviderRegistry reg = new SearchProviderRegistry();
        reg.register(new StubSuggester("nullish", 0f, null));
        reg.register(new StubSuggester("empty", 10f, List.of()));

        SlashMethods.appendProviderSuggestions(entries, reg, anySuggestReq(), MENTION_LIMIT, 0);

        assertEquals(1, entries.size(), "null/空结果不加项");
    }

    // ---- ②③④ RPC 路径 ----

    @Test
    void searchModeAppendsProviderSuggestionsAfterBuiltinWithDedup() throws Exception {
        Files.createDirectories(ws.resolve("docs"));
        Files.writeString(ws.resolve("readme.md"), "x");
        Files.writeString(ws.resolve("docs/readme-guide.md"), "x");
        StubSuggester provider = new StubSuggester("recent", 0f, List.of(
                new SuggestionProvider.Suggestion("readme.md"),          // 与内置重复 → 去重
                new SuggestionProvider.Suggestion("recent/untitled.md")));
        registry.register(provider);

        JsonNode reply = mention("read");
        assertEquals("rpc.ok", reply.path("event").asString(), reply.toString());
        JsonNode entries = reply.path("payload").path("result").path("entries");
        assertEquals(3, entries.size(), entries.toString());
        // 内置在前(磁盘上的 readme 系文件),provider 建议追加在后
        assertTrue(entries.path(0).path("path").asString().endsWith(".md"), entries.toString());
        assertFalse("recent/untitled.md".equals(entries.path(0).path("path").asString()));
        assertEquals("recent/untitled.md", entries.path(2).path("path").asString(),
                "provider 建议追加在内置之后: " + entries);
        // 全量 path+kind 唯一(去重生效)
        Set<String> keys = new HashSet<>();
        for (JsonNode e : entries) {
            assertTrue(keys.add(e.path("kind").asString() + "\u0000" + e.path("path").asString()),
                    "重复条目: " + e);
        }
        // 请求上下文正确传递
        assertEquals("read", provider.lastReq.query());
        assertEquals(".", provider.lastReq.path());
    }

    @Test
    void noProviderRegisteredOutputUnchanged() throws Exception {
        Files.createDirectories(ws.resolve("docs"));
        Files.writeString(ws.resolve("readme.md"), "x");
        Files.writeString(ws.resolve("docs/readme-guide.md"), "x");

        JsonNode reply = mention("read");
        assertEquals("rpc.ok", reply.path("event").asString(), reply.toString());
        JsonNode entries = reply.path("payload").path("result").path("entries");
        assertEquals(2, entries.size(), "未注册能力 provider 时输出与现状一致: " + entries);
        Set<String> paths = new HashSet<>();
        for (JsonNode e : entries) {
            paths.add(e.path("path").asString());
        }
        assertTrue(paths.contains("readme.md") && paths.contains("docs/readme-guide.md"),
                paths.toString());
    }

    @Test
    void browseModeDoesNotInvokeProviders() throws Exception {
        Files.createDirectories(ws.resolve("docs"));
        Files.writeString(ws.resolve("readme.md"), "x");
        StubSuggester provider = new StubSuggester("recent", 0f, List.of(
                new SuggestionProvider.Suggestion("recent/untitled.md")));
        registry.register(provider);

        JsonNode reply = mention(""); // 浏览模式:列当前目录顶层,不经 provider
        assertEquals("rpc.ok", reply.path("event").asString(), reply.toString());
        JsonNode entries = reply.path("payload").path("result").path("entries");
        assertEquals(2, entries.size(), entries.toString());
        assertEquals("docs", entries.path(0).path("path").asString(), "目录优先: " + entries);
        assertEquals("readme.md", entries.path(1).path("path").asString());
        assertNull(provider.lastReq, "浏览模式不调用 suggest");
    }

    @Test
    void builtinAtLimitSkipsProvidersOnRpcPath() throws Exception {
        for (int i = 0; i < 12; i++) {
            Files.writeString(ws.resolve("r" + i + ".md"), "x"); // 12 个命中 → 内置截断 10
        }
        StubSuggester provider = new StubSuggester("recent", 0f, List.of(
                new SuggestionProvider.Suggestion("recent/untitled.md")));
        registry.register(provider);

        JsonNode reply = mention("r");
        assertEquals("rpc.ok", reply.path("event").asString(), reply.toString());
        JsonNode entries = reply.path("payload").path("result").path("entries");
        assertEquals(MENTION_LIMIT, entries.size(), "总条数仍截断 10: " + entries.size());
        assertNull(provider.lastReq, "内置已达上限时 provider 不被调用");
        for (JsonNode e : entries) {
            assertFalse("recent/untitled.md".equals(e.path("path").asString()), entries.toString());
        }
    }

    @Test
    void providerFailureDoesNotAffectReply() throws Exception {
        Files.writeString(ws.resolve("readme.md"), "x");
        registry.register(new StubSuggester("bad", 0f, List.of(),
                new RuntimeException("boom")));

        JsonNode reply = mention("read");
        assertEquals("rpc.ok", reply.path("event").asString(), "坏 provider 仅 WARN 跳过");
        JsonNode entries = reply.path("payload").path("result").path("entries");
        assertEquals(1, entries.size(), entries.toString());
        assertEquals("readme.md", entries.path(0).path("path").asString());
    }
}
