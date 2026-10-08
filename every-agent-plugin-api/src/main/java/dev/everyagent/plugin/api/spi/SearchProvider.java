package dev.everyagent.plugin.api.spi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.nio.file.Path;
import java.util.List;

/**
 * 搜索后端提供者 SPI —— 插件实现此接口提供不同搜索引擎。
 *
 * <p><b>接线语义(增补聚合,架构 §8.5)</b>:插件经 {@code ctx.registerSearchProvider}
 * 注册的后端由 worker 的 {@code fs.search}({@link #searchFiles})与
 * {@code task.search}({@link #searchTasks})在完成内置 ripgrep 搜索后按
 * {@link #order()} 升序(同 order 保持注册先后)<b>增补聚合</b>——内置结果在前、
 * 各 provider 结果追加在后,按 {@code kind}+该 kind 位置键去重(file 沿用
 * {@code path+lineNumber+matchIndex} / task 沿用 {@code taskId+roundIndex+field+matchIndex},
 * 未声明 kind 的结果按所在 RPC 归一,跨 kind 不判重),合并后仍受 {@code maxResults}
 * 触顶约束;单个 provider 抛异常仅 WARN 跳过;注册表为空时零额外行为。结果形状与
 * 两条 RPC 的应答项一致(如 {@code SearchResult.path} 为工作区相对 posix 路径),
 * 且适用统一搜索结果模型(§8.5)的三个可选增补字段 {@code kind}/{@code providerId}/
 * {@code score}(见 {@link SearchResult});聚合时 provider 结果项由 worker 填
 * {@code providerId=provider.id()}(自带则尊重不覆盖),内置 rg 结果项标记
 * {@link #BUILTIN_PROVIDER_ID}。{@code SearchRequest.workspaceId} 在工作区未注册进
 * 注册表时可能为 null。
 *
 * <p>典型场景:search-es(ElasticSearch 后端)、search-vector(向量搜索)等在工作区
 * 外维护索引的引擎,经本 SPI 把索引命中补充进前端搜索结果。
 */
public interface SearchProvider {

    /**
     * 统一搜索结果模型(§8.5)的内置 rg 来源 id:worker 内置 ripgrep 引擎产出的结果项
     * 固定标记此值;providerId 缺省亦视为来源 builtin.rg。
     */
    String BUILTIN_PROVIDER_ID = "builtin.rg";

    /** 统一搜索结果模型(§8.5)kind 开放集合的内置类别:fs.search / fs.find 结果项。 */
    String KIND_FILE = "file";

    /** 统一搜索结果模型(§8.5)kind 开放集合的内置类别:task.search 结果项。 */
    String KIND_TASK = "task";

    /** 引擎 id(provider 结果项聚合时由 worker 填入 providerId,自带则尊重不覆盖)。 */
    String id();

    /**
     * kind 归一(§8.5):null/空白回退到所在 RPC 的缺省类别(fs.search / fs.find →
     * {@link #KIND_FILE},task.search → {@link #KIND_TASK})。仅用于聚合去重键与应答
     * 标记——记录字段本身保持可空(null = 缺省语义,序列化省略),保证未声明 kind 的
     * provider 与现状完全一致。
     */
    static String normalizeKind(String kind, String defaultKind) {
        return kind == null || kind.isBlank() ? defaultKind : kind;
    }

    /**
     * providerId 补齐(§8.5):结果项已自带非空 providerId 时尊重不覆盖,否则回退
     * 聚合方传入的来源 id(调用方传 {@link #id()})。
     */
    static String providerIdOr(String providerId, String fallback) {
        return providerId == null || providerId.isBlank() ? fallback : providerId;
    }

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

    /**
     * 文件搜索结果项。统一搜索结果模型(§8.5)的三个可选增补字段 {@code kind}(开放集合,
     * 本 RPC 缺省 {@code file})/{@code providerId}(缺省视为 {@code builtin.rg},聚合时由
     * worker 填 {@code provider.id()})/{@code score}(仅排序提示,worker 不依它重排)均可空,
     * null 时序列化省略。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SearchResult(String path, int lineNumber, String line, int matchIndex, String matchText,
            String kind, String providerId, Double score) {

        /**
         * 兼容构造:不带统一搜索结果模型(§8.5)的三个可选增补字段
         * (kind/providerId/score = null,序列化省略,与旧版结构一致)。
         */
        public SearchResult(String path, int lineNumber, String line, int matchIndex, String matchText) {
            this(path, lineNumber, line, matchIndex, matchText, null, null, null);
        }
    }

    /** 任务搜索请求。 */
    record TaskSearchRequest(String workspaceId, String pattern,
            boolean isRegex, boolean caseSensitive, boolean wholeWord, int maxResults) {}

    /**
     * 任务搜索结果项。统一搜索结果模型(§8.5)的三个可选增补字段 {@code kind}(开放集合,
     * 本 RPC 缺省 {@code task})/{@code providerId}(缺省视为 {@code builtin.rg},聚合时由
     * worker 填 {@code provider.id()})/{@code score}(仅排序提示,worker 不依它重排)均可空,
     * null 时序列化省略。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record TaskSearchResult(String taskId, String title, String workspace, String workspaceId,
            String status, List<Match> matches, String kind, String providerId, Double score) {

        /**
         * 兼容构造:不带统一搜索结果模型(§8.5)的三个可选增补字段
         * (kind/providerId/score = null,序列化省略,与旧版结构一致)。
         */
        public TaskSearchResult(String taskId, String title, String workspace, String workspaceId,
                String status, List<Match> matches) {
            this(taskId, title, workspace, workspaceId, status, matches, null, null, null);
        }

        /**
         * 单条命中:{@code line} 为命中字段的干净文本(与 {@code task.search} 应答项的
         * {@code line} 一致,非行号;行内定位用 {@code matchIndex})。
         */
        public record Match(int roundIndex, String field, String line, int matchIndex, String matchText) {}
    }
}
