package dev.everyagent.worker.slash;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.spi.SearchProvider;
import dev.everyagent.plugin.api.spi.SuggestionProvider;
import dev.everyagent.worker.plugin.registry.SearchProviderRegistry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 统一搜索结果模型(§8.5)在 mention.query 增补聚合的行为单测(纯函数):
 * provider 建议条目携带可选增补字段 {@code providerId}(建议项自带则尊重不覆盖,否则填
 * {@code provider.id()})与 {@code score}(仅 provider 提供时携带);kind 归一逻辑不变
 * (缺省 file);内置四档打分条目形态不变(不带增补字段)。
 */
class UnifiedSearchModelMentionTest {

    // ---- 桩与帮助 ----

    /** 桩建议 provider:固定返回建议列表。 */
    private static final class StubSuggester implements SuggestionProvider {
        private final String id;
        private final List<Suggestion> suggestions;

        StubSuggester(String id, List<Suggestion> suggestions) {
            this.id = id;
            this.suggestions = suggestions;
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
        public List<Suggestion> suggest(SuggestRequest req) {
            return suggestions;
        }
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
        return new SuggestionProvider.SuggestRequest("ws1", java.nio.file.Path.of("."), "q", ".");
    }

    // ---- 用例 ----

    @Test
    void suggestionProviderIdFilledSelfRespectedScorePassedThrough() {
        ArrayNode entries = Json.arr();
        entries.add(builtInEntry("readme.md", "file"));
        SearchProviderRegistry reg = new SearchProviderRegistry();
        reg.register(new StubSuggester("recent", List.of(
                new SuggestionProvider.Suggestion("a.md"),                              // 全缺省
                new SuggestionProvider.Suggestion("b.md", "file", "self.recent", 0.8),  // 自带 providerId + score
                new SuggestionProvider.Suggestion("docs", "directory"))));              // 其他 kind

        SlashMethods.appendProviderSuggestions(entries, reg, anySuggestReq(), 10, 0);

        assertEquals(4, entries.size(), entries.toString());
        // 内置条目形态不变(不带增补字段)
        assertFalse(entries.path(0).has("providerId"), "内置条目不带 providerId");
        assertFalse(entries.path(0).has("score"), "内置条目不带 score");
        // 全缺省:providerId 填 provider.id(),score 不输出
        JsonNode a = entries.path(1);
        assertEquals("recent", a.path("providerId").asString(), "未自带 providerId 则填 provider.id()");
        assertFalse(a.has("score"), "score 缺省不输出");
        // 自带:尊重不覆盖;score 透传
        JsonNode b = entries.path(2);
        assertEquals("self.recent", b.path("providerId").asString(), "自带 providerId 尊重不覆盖");
        assertEquals(0.8, b.path("score").asDouble(), "score 透传(仅排序提示)");
        // 非 file kind:归一逻辑不变(保留自带 kind),同样填 providerId
        JsonNode d = entries.path(3);
        assertEquals("directory", d.path("kind").asString());
        assertEquals("recent", d.path("providerId").asString());
        assertFalse(d.has("score"));
    }

    @Test
    void suggestionKindDefaultStillNormalizedToFile() {
        ArrayNode entries = Json.arr();
        SearchProviderRegistry reg = new SearchProviderRegistry();
        reg.register(new StubSuggester("recent", List.of(
                new SuggestionProvider.Suggestion("z.md"))));

        SlashMethods.appendProviderSuggestions(entries, reg, anySuggestReq(), 10, 0);

        assertEquals("file", entries.path(0).path("kind").asString(),
                "kind 缺省归一 file(Suggestion 记录内归一,逻辑不变)");
        assertEquals("recent", entries.path(0).path("providerId").asString());
    }
}
