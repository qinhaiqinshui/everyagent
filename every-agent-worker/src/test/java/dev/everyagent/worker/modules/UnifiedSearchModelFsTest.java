package dev.everyagent.worker.modules;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.spi.FileNameSearchProvider;
import dev.everyagent.plugin.api.spi.SearchProvider;
import dev.everyagent.worker.plugin.registry.SearchProviderRegistry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 统一搜索结果模型(§8.5)在 fs.search / fs.find 增补聚合的行为单测(纯函数,不依赖 rg):
 * <ol>
 *   <li>内置 rg 结果标记 {@code kind=file} + {@code providerId=builtin.rg}(score 不设);</li>
 *   <li>去重键升级为 {@code kind}+位置键:同 kind 同位置去重、跨 kind 不去重
 *       (未声明 kind 的 provider 按 fs.search/fs.find 归一为 file,与现状完全一致);</li>
 *   <li>provider 结果项 {@code providerId=provider.id()}(自带则尊重不覆盖)、
 *       {@code score} 仅 provider 提供时携带。</li>
 * </ol>
 */
class UnifiedSearchModelFsTest {

    /** 不限时 provider 预算(0 = 仅异常护栏,与 worker.search.provider-timeout-ms 默认一致)。 */
    private static final long NO_TIMEOUT = 0;

    // ---- 桩与帮助 ----

    /** 桩 provider:固定返回文件命中列表。 */
    private static final class StubProvider implements SearchProvider {
        private final String id;
        private final List<SearchResult> files;

        StubProvider(String id, List<SearchResult> files) {
            this.id = id;
            this.files = files;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public List<SearchResult> searchFiles(SearchRequest req) {
            return files;
        }

        @Override
        public List<TaskSearchResult> searchTasks(TaskSearchRequest req) {
            return List.of();
        }
    }

    /** 桩文件名 provider(§8.5 能力接口):固定返回路径列表。 */
    private static final class StubNameProvider implements FileNameSearchProvider {
        private final String id;
        private final List<FileResult> files;

        StubNameProvider(String id, List<FileResult> files) {
            this.id = id;
            this.files = files;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public List<SearchResult> searchFiles(SearchRequest req) {
            return List.of();
        }

        @Override
        public List<TaskSearchResult> searchTasks(TaskSearchRequest req) {
            return List.of();
        }

        @Override
        public List<FileResult> findFiles(FindRequest req) {
            return files;
        }
    }

    private static SearchProvider.SearchRequest anyReq() {
        return new SearchProvider.SearchRequest("ws1", Path.of("."), "x", false, false, false,
                List.of(), List.of(), 100);
    }

    private static FileNameSearchProvider.FindRequest anyFindReq() {
        return new FileNameSearchProvider.FindRequest("ws1", Path.of("."), "x", false, false,
                false, List.of(), List.of(), ".", 100);
    }

    /** 经生产路径 {@link FsSearchService#append} 造一条内置 rg 形态的 outcome(含内置标记)。 */
    private static FsSearchService.SearchOutcome builtinOutcomeOf(String path,
            FsSearchService.RawHit... hits) {
        LinkedHashMap<String, ObjectNode> files = new LinkedHashMap<>();
        for (FsSearchService.RawHit h : hits) {
            FsSearchService.append(files, path, h);
        }
        return new FsSearchService.SearchOutcome(files, hits.length, false);
    }

    // ---- ① 内置 rg 标记 ----

    @Test
    void builtinRgMatchesMarkedKindFileAndProviderBuiltinRg() {
        FsSearchService.SearchOutcome builtIn = builtinOutcomeOf("a.txt",
                new FsSearchService.RawHit("a.txt", 1, "l", 0, "x"),
                new FsSearchService.RawHit("a.txt", 3, "l2", 2, "y"));
        for (JsonNode m : builtIn.files().get("a.txt").path("matches")) {
            assertEquals("file", m.path("kind").asString(), "内置命中标记 kind=file");
            assertEquals("builtin.rg", m.path("providerId").asString(), "内置命中标记 providerId=builtin.rg");
            assertFalse(m.has("score"), "内置不设 score");
        }
        // 既有字段不动
        assertEquals(3, builtIn.files().get("a.txt").path("matches").path(1).path("lineNumber").asInt());
    }

    @Test
    void builtinFindEntryMarkedKindFileAndProviderBuiltinRg() {
        JsonNode e = FsSearchService.builtinFileEntry("x.md");
        assertEquals("x.md", e.path("path").asString());
        assertEquals("file", e.path("kind").asString());
        assertEquals("builtin.rg", e.path("providerId").asString());
        assertFalse(e.has("score"), "内置不设 score");
        assertFalse(e.has("matches"), "find 结果项无 matches 字段");
    }

    // ---- ② 去重键升级:kind + 位置键 ----

    @Test
    void sameKindSamePositionDedupedButCrossKindKept() {
        FsSearchService.SearchOutcome builtIn = builtinOutcomeOf("a.txt",
                new FsSearchService.RawHit("a.txt", 1, "l", 0, "x"));
        SearchProviderRegistry reg = new SearchProviderRegistry();
        reg.register(new StubProvider("es", List.of(
                // kind 缺省归一 file → 与内置同 kind 同位置 → 去重
                new SearchProvider.SearchResult("a.txt", 1, "l", 0, "x"),
                // 跨 kind(symbol)同位置 → 不去重(§8.5:跨 kind 不判重)
                new SearchProvider.SearchResult("a.txt", 1, "l", 0, "x", "symbol", null, null))));

        FsSearchService.SearchOutcome merged = FsSearchService.mergeProviderResults(
                builtIn, reg, anyReq(), 100, NO_TIMEOUT);

        assertEquals(2, merged.matchCount(), "file 同位置去重 1,symbol 同位置保留 1");
        JsonNode matches = merged.files().get("a.txt").path("matches");
        assertEquals(2, matches.size());
        assertEquals("file", matches.path(0).path("kind").asString(), "内置项在前且标记不变");
        assertEquals("builtin.rg", matches.path(0).path("providerId").asString());
        assertEquals("symbol", matches.path(1).path("kind").asString(), "跨 kind 命中保留并标记自身 kind");
        assertEquals("es", matches.path(1).path("providerId").asString(), "provider 命中标记 providerId=provider.id()");
    }

    @Test
    void findSameKindSamePathDedupedButCrossKindKept() {
        LinkedHashMap<String, ObjectNode> files = new LinkedHashMap<>();
        files.put("n.txt", FsSearchService.builtinFileEntry("n.txt"));
        FsSearchService.SearchOutcome builtIn = new FsSearchService.SearchOutcome(files, 1, false);
        SearchProviderRegistry reg = new SearchProviderRegistry();
        reg.register(new StubNameProvider("idx", List.of(
                new FileNameSearchProvider.FileResult("n.txt"),                          // 归一 file → 同 kind 同 path 去重
                new FileNameSearchProvider.FileResult("n.txt", "symbol", null, null)))); // 跨 kind → 保留

        FsSearchService.SearchOutcome merged = FsSearchService.mergeFindProviderResults(
                builtIn, reg, anyFindReq(), 100, NO_TIMEOUT);

        assertEquals(2, merged.matchCount(), "file 去重 1,symbol 同 path 保留 1");
        assertEquals(2, merged.files().size());
        JsonNode symbol = merged.files().values().stream()
                .filter(f -> "symbol".equals(f.path("kind").asString()))
                .findFirst().orElseThrow();
        assertEquals("n.txt", symbol.path("path").asString(), "跨 kind 项与同路径内置文件项并存");
        assertEquals("idx", symbol.path("providerId").asString());
        assertEquals("builtin.rg", merged.files().get("n.txt").path("providerId").asString(),
                "内置文件项不被跨 kind 项覆盖");
    }

    // ---- ③ provider 增补字段标记 ----

    @Test
    void providerProviderIdFilledSelfRespectedScorePassedThrough() {
        FsSearchService.SearchOutcome builtIn = new FsSearchService.SearchOutcome(
                new LinkedHashMap<>(), 0, false);
        SearchProviderRegistry reg = new SearchProviderRegistry();
        reg.register(new StubProvider("es", List.of(
                // 自带 providerId → 尊重不覆盖;score 透传
                new SearchProvider.SearchResult("b.md", 5, "l", 0, "x", null, "self.es", 0.75),
                // 全缺省 → providerId 填 provider.id(),kind 归一 file,score 不输出
                new SearchProvider.SearchResult("c.md", 6, "l", 0, "x"))));

        FsSearchService.SearchOutcome merged = FsSearchService.mergeProviderResults(
                builtIn, reg, anyReq(), 100, NO_TIMEOUT);

        JsonNode b = merged.files().get("b.md").path("matches").path(0);
        assertEquals("self.es", b.path("providerId").asString(), "自带 providerId 尊重不覆盖");
        assertEquals("file", b.path("kind").asString(), "kind 缺省归一 file");
        assertEquals(0.75, b.path("score").asDouble(), "score 透传(仅排序提示)");
        JsonNode c = merged.files().get("c.md").path("matches").path(0);
        assertEquals("es", c.path("providerId").asString(), "未自带则填 provider.id()");
        assertEquals("file", c.path("kind").asString());
        assertFalse(c.has("score"), "score 缺省不输出");
        assertTrue(merged.files().get("b.md").path("matches").path(0).has("lineNumber"),
                "既有字段不动");
    }

    @Test
    void findProviderProviderIdFilledAndScorePassedThrough() {
        FsSearchService.SearchOutcome builtIn = new FsSearchService.SearchOutcome(
                new LinkedHashMap<>(), 0, false);
        SearchProviderRegistry reg = new SearchProviderRegistry();
        reg.register(new StubNameProvider("idx", List.of(
                new FileNameSearchProvider.FileResult("g.md", null, "self.idx", 0.6),
                new FileNameSearchProvider.FileResult("h.md"))));

        FsSearchService.SearchOutcome merged = FsSearchService.mergeFindProviderResults(
                builtIn, reg, anyFindReq(), 100, NO_TIMEOUT);

        JsonNode g = merged.files().get("g.md");
        assertEquals("self.idx", g.path("providerId").asString(), "自带 providerId 尊重不覆盖");
        assertEquals("file", g.path("kind").asString());
        assertEquals(0.6, g.path("score").asDouble(), "score 透传");
        JsonNode h = merged.files().get("h.md");
        assertEquals("idx", h.path("providerId").asString(), "未自带则填 provider.id()");
        assertFalse(h.has("score"), "score 缺省不输出");
    }
}
