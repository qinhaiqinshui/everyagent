package dev.everyagent.plugin.api.spi;

import java.nio.file.Path;
import java.util.List;

/**
 * 搜索后端提供者 SPI —— 插件实现此接口提供不同搜索引擎。
 *
 * <p><b>接线语义(增补聚合,架构 §8.5)</b>:插件经 {@code ctx.registerSearchProvider}
 * 注册的后端由 worker 的 {@code fs.search}({@link #searchFiles})与
 * {@code task.search}({@link #searchTasks})在完成内置 ripgrep 搜索后按
 * {@link #order()} 升序(同 order 保持注册先后)<b>增补聚合</b>——内置结果在前、
 * 各 provider 结果追加在后,按位置键去重(文件
 * {@code path+lineNumber+matchIndex} / 任务 {@code taskId+roundIndex+field+matchIndex}),
 * 合并后仍受 {@code maxResults} 触顶约束;单个 provider 抛异常仅 WARN 跳过;
 * 注册表为空时零额外行为。结果形状与两条 RPC 的应答项一致(如
 * {@code SearchResult.path} 为工作区相对 posix 路径)。
 * {@code SearchRequest.workspaceId} 在工作区未注册进注册表时可能为 null。
 *
 * <p>典型场景:search-es(ElasticSearch 后端)、search-vector(向量搜索)等在工作区
 * 外维护索引的引擎,经本 SPI 把索引命中补充进前端搜索结果。
 */
public interface SearchProvider {

    /** 引擎 id。 */
    String id();

    /**
     * 聚合顺序权重:值小者先执行、结果先并入聚合(升序 = 执行/返回序)。
     * 与 {@link dev.everyagent.plugin.api.permission.AuthorizationHandler#order()}
     * 坐标约定一致,float 允许任意插位;同 order 的 provider 保持注册先后(稳定排序)。
     */
    default float order() {
        return 0f;
    }

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

        /**
         * 单条命中:{@code line} 为命中字段的干净文本(与 {@code task.search} 应答项的
         * {@code line} 一致,非行号;行内定位用 {@code matchIndex})。
         */
        public record Match(int roundIndex, String field, String line, int matchIndex, String matchText) {}
    }
}
