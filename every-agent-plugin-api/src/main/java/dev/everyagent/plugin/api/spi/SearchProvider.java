package dev.everyagent.plugin.api.spi;

import java.nio.file.Path;
import java.util.List;

/**
 * 搜索后端提供者 SPI —— 插件实现此接口提供不同搜索引擎。
 *
 * <p>现有 ripgrep 后端变为默认插件（search-ripgrep）。
 * 可新增 search-es（ElasticSearch 后端）、search-vector（向量搜索）。
 */
public interface SearchProvider {

    /** 引擎 id。 */
    String id();

    /**
     * 文件内容搜索。
     *
     * @param req 搜索请求
     * @return 搜索结果列表
     */
    List<SearchResult> searchFiles(SearchRequest req);

    /**
     * 任务内容搜索。
     *
     * @param req 任务搜索请求
     * @return 任务搜索结果列表
     */
    List<TaskSearchResult> searchTasks(TaskSearchRequest req);

    /** 文件搜索请求。 */
    record SearchRequest(String workspaceId, Path workspaceRoot, String pattern,
            boolean isRegex, boolean caseSensitive, boolean wholeWord,
            List<String> includeGlobs, List<String> excludeGlobs, int maxResults) {}

    /** 文件搜索结果项。 */
    record SearchResult(String path, int lineNumber, String line, int matchIndex, String matchText) {}

    /** 任务搜索请求。 */
    record TaskSearchRequest(String workspaceId, String pattern,
            boolean isRegex, boolean caseSensitive, boolean wholeWord, int maxResults) {}

    /** 任务搜索结果项。 */
    record TaskSearchResult(String taskId, String title, String workspace, String workspaceId,
            String status, List<Match> matches) {
        public record Match(int roundIndex, String field, int line, int matchIndex, String matchText) {}
    }
}
