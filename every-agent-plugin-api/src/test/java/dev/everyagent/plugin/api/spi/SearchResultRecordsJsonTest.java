package dev.everyagent.plugin.api.spi;

import dev.everyagent.contract.json.Json;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 统一搜索结果模型(§8.5)可选增补字段 {@code kind}/{@code providerId}/{@code score}
 * 的序列化兼容单测,覆盖四个结果记录(SearchResult / TaskSearchResult / FileResult /
 * Suggestion),每记录钉住三条:
 * <ol>
 *   <li>旧格式 JSON(无新字段)反序列化成功且新字段为 null(既有缺省语义不变);</li>
 *   <li>新字段为 null 时序列化输出与旧版结构一致(不含新键,NON_NULL 省略);</li>
 *   <li>带新字段的 JSON 反序列化取值正确。</li>
 * </ol>
 * 老客户端 must-ignore(§5.6):老字段不动、新键缺省即零影响。
 */
class SearchResultRecordsJsonTest {

    // ---- SearchProvider.SearchResult(fs.search 文件结果项) ----

    private static final String OLD_FILE_JSON =
            "{\"path\":\"a.txt\",\"lineNumber\":3,\"line\":\"l\",\"matchIndex\":2,\"matchText\":\"x\"}";

    @Test
    void fileResultOldJsonDeserializesWithNullOptionals() {
        SearchProvider.SearchResult r = Json.convert(Json.parse(OLD_FILE_JSON),
                SearchProvider.SearchResult.class);
        assertEquals("a.txt", r.path());
        assertEquals(3, r.lineNumber());
        assertEquals("l", r.line());
        assertEquals(2, r.matchIndex());
        assertEquals("x", r.matchText());
        assertNull(r.kind(), "旧格式无 kind → null(缺省语义 = file)");
        assertNull(r.providerId(), "旧格式无 providerId → null(缺省视为 builtin.rg)");
        assertNull(r.score());
    }

    @Test
    void fileResultNullOptionalsSerializeToLegacyShape() {
        String out = Json.write(new SearchProvider.SearchResult("a.txt", 3, "l", 2, "x"));
        assertEquals(OLD_FILE_JSON, out, "增补字段为 null 时序列化与旧版结构一致(不含新键)");
    }

    @Test
    void fileResultNewFieldsRoundTrip() {
        SearchProvider.SearchResult r = Json.convert(Json.parse(
                "{\"path\":\"a.txt\",\"lineNumber\":3,\"line\":\"l\",\"matchIndex\":2,\"matchText\":\"x\","
                        + "\"kind\":\"symbol\",\"providerId\":\"search-es\",\"score\":0.75}"),
                SearchProvider.SearchResult.class);
        assertEquals("symbol", r.kind());
        assertEquals("search-es", r.providerId());
        assertEquals(0.75, r.score());
    }

    // ---- SearchProvider.TaskSearchResult(task.search 任务结果项) ----

    private static final String OLD_TASK_JSON =
            "{\"taskId\":\"t1\",\"title\":\"ti\",\"workspace\":\"/ws\",\"workspaceId\":\"w1\","
                    + "\"status\":\"done\",\"matches\":[{\"roundIndex\":1,\"field\":\"user\","
                    + "\"line\":\"l\",\"matchIndex\":2,\"matchText\":\"x\"}]}";

    @Test
    void taskResultOldJsonDeserializesWithNullOptionals() {
        SearchProvider.TaskSearchResult r = Json.convert(Json.parse(OLD_TASK_JSON),
                SearchProvider.TaskSearchResult.class);
        assertEquals("t1", r.taskId());
        assertEquals(1, r.matches().size());
        assertEquals("user", r.matches().get(0).field());
        assertNull(r.kind(), "旧格式无 kind → null(缺省语义 = task)");
        assertNull(r.providerId());
        assertNull(r.score());
    }

    @Test
    void taskResultNullOptionalsSerializeToLegacyShape() {
        String out = Json.write(new SearchProvider.TaskSearchResult("t1", "ti", "/ws", "w1", "done",
                List.of(new SearchProvider.TaskSearchResult.Match(1, "user", "l", 2, "x"))));
        assertEquals(OLD_TASK_JSON, out, "增补字段为 null 时序列化与旧版结构一致(不含新键)");
    }

    @Test
    void taskResultNewFieldsRoundTrip() {
        SearchProvider.TaskSearchResult r = Json.convert(Json.parse(
                "{\"taskId\":\"t1\",\"title\":\"ti\",\"workspace\":\"/ws\",\"workspaceId\":\"w1\","
                        + "\"status\":\"done\",\"matches\":[],\"kind\":\"semantic\","
                        + "\"providerId\":\"search-vector\",\"score\":0.5}"),
                SearchProvider.TaskSearchResult.class);
        assertEquals("semantic", r.kind());
        assertEquals("search-vector", r.providerId());
        assertEquals(0.5, r.score());
    }

    // ---- FileNameSearchProvider.FileResult(fs.find 文件结果项) ----

    @Test
    void findResultOldJsonDeserializesWithNullOptionals() {
        FileNameSearchProvider.FileResult r = Json.convert(Json.parse("{\"path\":\"doc/x.md\"}"),
                FileNameSearchProvider.FileResult.class);
        assertEquals("doc/x.md", r.path());
        assertNull(r.kind(), "旧格式无 kind → null(缺省语义 = file)");
        assertNull(r.providerId());
        assertNull(r.score());
    }

    @Test
    void findResultNullOptionalsSerializeToLegacyShape() {
        String out = Json.write(new FileNameSearchProvider.FileResult("doc/x.md"));
        assertEquals("{\"path\":\"doc/x.md\"}", out, "增补字段为 null 时序列化与旧版结构一致(不含新键)");
    }

    @Test
    void findResultNewFieldsRoundTrip() {
        FileNameSearchProvider.FileResult r = Json.convert(Json.parse(
                "{\"path\":\"doc/x.md\",\"kind\":\"symbol\",\"providerId\":\"idx\",\"score\":1.5}"),
                FileNameSearchProvider.FileResult.class);
        assertEquals("symbol", r.kind());
        assertEquals("idx", r.providerId());
        assertEquals(1.5, r.score());
    }

    // ---- SuggestionProvider.Suggestion(mention 建议项) ----

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
                "{\"path\":\"a.md\",\"kind\":\"issue\",\"providerId\":\"jira-link\","
                        + "\"score\":0.9}"),
                SuggestionProvider.Suggestion.class);
        assertEquals("issue", s.kind());
        assertEquals("jira-link", s.providerId());
        assertEquals(0.9, s.score());
    }
}
