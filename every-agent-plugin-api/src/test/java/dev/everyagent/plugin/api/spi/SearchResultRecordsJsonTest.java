package dev.everyagent.plugin.api.spi;

import dev.everyagent.contract.json.Json;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 统一搜索 SPI 记录(§8.5)的序列化兼容单测:
 * <ol>
 *   <li>{@link SearchProvider.SearchResultItem} 可选增补字段 {@code providerId}/{@code score}
 *       可空(缺省 null),{@code kind}/{@code positionKey}/{@code fields} 为必填通用字段;</li>
 *   <li>{@link SearchProvider.SearchResult} 承载 items + 自身截断标志;</li>
 *   <li>{@link SuggestionProvider.Suggestion}(mention 建议)kind 缺省归一 file,providerId/score 可空。</li>
 * </ol>
 */
class SearchResultRecordsJsonTest {

    // ---- SearchProvider.SearchResultItem(file-content / file-name / task / 插件自定义) ----

    @Test
    void searchResultItemOldShapeDeserializesWithNullOptionals() {
        SearchProvider.SearchResultItem item = Json.convert(Json.parse(
                "{\"kind\":\"file-content\",\"providerId\":null,\"score\":null,"
                        + "\"positionKey\":\"a.txt\\u00003\\u00002\","
                        + "\"fields\":{\"path\":\"a.txt\",\"lineNumber\":3,\"line\":\"l\","
                        + "\"matchIndex\":2,\"matchText\":\"x\"}}"),
                SearchProvider.SearchResultItem.class);
        assertEquals("file-content", item.kind());
        assertEquals("a.txt\u00003\u00002", item.positionKey());
        assertNull(item.providerId(), "providerId 缺省 null(聚合方填 provider.id())");
        assertNull(item.score());
        assertEquals("a.txt", item.fields().get("path"));
        assertEquals("x", item.fields().get("matchText"));
    }

    @Test
    void searchResultItemNewFieldsRoundTrip() {
        SearchProvider.SearchResultItem item = new SearchProvider.SearchResultItem(
                "symbol", "search-es", 0.75, "pos-1", Map.of("path", "a.txt", "lineNumber", 1));
        SearchProvider.SearchResultItem back = Json.convert(Json.toJson(item),
                SearchProvider.SearchResultItem.class);
        assertEquals("symbol", back.kind());
        assertEquals("search-es", back.providerId());
        assertEquals(0.75, back.score());
        assertEquals("pos-1", back.positionKey());
        assertEquals("a.txt", back.fields().get("path"));
    }

    // ---- SearchProvider.SearchResult(provider 应答信封) ----

    @Test
    void searchResultCarriesItemsAndTruncatedFlag() {
        SearchProvider.SearchResult r = new SearchProvider.SearchResult(
                List.of(new SearchProvider.SearchResultItem("task", null, null, "k", Map.of("taskId", "t1"))),
                true);
        SearchProvider.SearchResult back = Json.convert(Json.toJson(r), SearchProvider.SearchResult.class);
        assertEquals(1, back.items().size());
        assertEquals("task", back.items().get(0).kind());
        assertEquals("t1", back.items().get(0).fields().get("taskId"));
        assertEquals(true, back.truncated());
    }

    @Test
    void emptySearchResult() {
        SearchProvider.SearchResult empty = SearchProvider.SearchResult.empty();
        assertEquals(List.of(), empty.items());
        assertEquals(false, empty.truncated());
    }

    // ---- SuggestionProvider.Suggestion(mention 建议项,保留不变) ----

    @Test
    void suggestionOldJsonKindNormalizedOptionalsNull() {
        SuggestionProvider.Suggestion s = Json.convert(Json.parse("{\"path\":\"a.md\"}"),
                SuggestionProvider.Suggestion.class);
        assertEquals("a.md", s.path());
        assertEquals("file", s.kind(), "kind 缺省归一 file(既有归一逻辑不变)");
        assertNull(s.providerId());
        assertNull(s.score());
    }

    @Test
    void suggestionNullOptionalsSerializeToLegacyShape() {
        String out = Json.write(new SuggestionProvider.Suggestion("a.md"));
        assertEquals("{\"path\":\"a.md\",\"kind\":\"file\"}", out,
                "providerId/score 为 null 时序列化与旧版结构一致(不含新键)");
    }

    @Test
    void suggestionNewFieldsRoundTrip() {
        SuggestionProvider.Suggestion s = Json.convert(Json.parse(
                "{\"path\":\"a.md\",\"kind\":\"issue\",\"providerId\":\"jira-link\",\"score\":0.9}"),
                SuggestionProvider.Suggestion.class);
        assertEquals("issue", s.kind());
        assertEquals("jira-link", s.providerId());
        assertEquals(0.9, s.score());
    }
}