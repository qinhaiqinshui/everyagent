package dev.everyagent.plugin.api.spi;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 统一搜索后端 SPI —— 插件/内置引擎实现此接口提供一个搜索"类型(kind)"的后端。
 *
 * <p><b>统一 search 语义(架构 §8.5)</b>:worker 的统一 {@code search} RPC 核心<b>零类型感知</b>
 * ——它只做「遍历 SearchProvider 注册表(可选按请求 {@code kinds} 数据过滤)→ 逐个
 * {@link #search}({@link SearchRequest}) 触发 → 按 {@code kind}+位置键去重聚合」。核心
 * <b>不 import、不 switch</b> 任何具体类型:它不知道有哪些 kind、也不知道 kind→方法 的映射。
 * 每个 provider 通过 {@link #kinds()} 声明自己服务的 kind 集合,供核心按请求 {@code kinds} 过滤。
 *
 * <p><b>入参(核心四要素)</b>:统一 search RPC 入参只有 {@code workspace} / {@code pattern} /
 * {@code kinds?} / {@code filters?};核心<b>不解释</b> {@code pattern} 与 {@code filters} 的内容,
 * 原样透传给各 provider。{@link SearchRequest#filters()} 是<b>不透明参数袋</b>(key→value 的 Map),
 * key 约定为 {@code ${kind}.${field}} 命名空间(如 {@code file-content.isRegex}、
 * {@code file-content.include}、{@code task.wholeWord});<b>由各 provider 按自身 kind 前缀读取</b>,
 * 核心只透传整个袋、绝不解释其 key/value。过滤字段的解释(正则/大小写/全字/包含/排除/范围)与
 * 各自的结果上限、默认排除目录等职责,一律属 provider。
 *
 * <p><b>结果项</b>:{@link SearchResultItem} 是通用结果项 —— 至少携带 {@link SearchResultItem#kind()}
 * (provider 自持类别)与 {@link SearchResultItem#positionKey()}(该 kind 内的位置键,用于跨 provider
 * 同 kind 同位置去重);展示字段放进 {@link SearchResultItem#fields()} 袋(如 file-content 的
 * {@code path/lineNumber/line/matchIndex/matchText}、task 的 {@code taskId/title/status/roundIndex/field/...}),
 * 核心不解释字段语义、原样透传。可选增补字段 {@code providerId}/{@code score} 语义与旧统一模型一致:
 * {@code providerId} 结果项自带则尊重不覆盖、否则由聚合方填 {@link #id()};{@code score} 仅排序提示,
 * worker 不依它重排。
 *
 * <p><b>护栏</b>:单个 provider 抛异常/超出超时预算仅 WARN 跳过,不影响其余结果与应答;
 * {@link SearchResult#truncated()} 为该 provider 自身触顶(结果上限)标志,聚合方取各 provider 的「或」。
 *
 * <p><b>能力接口</b>:{@link SuggestionProvider}({@code mention.query} 的 {@code @} 建议,独立于搜索面板)
 * 是 {@link SearchProvider} 的子接口,同样经 {@code ctx.registerSearchProvider} 注册;
 * 其 {@code suggest} 与统一 search 无关,故 {@link #search} / {@link #kinds()} 均为默认空实现。
 */
public interface SearchProvider {

    /** 引擎 id(结果项聚合时由 worker 填入 providerId,结果项自带则尊重不覆盖)。 */
    String id();

    /**
     * 聚合顺序权重:值小者先执行、结果先并入聚合(升序 = 执行/返回序)。
     * 内置引擎占很小的值(排在插件 provider 之前)。同 order 保持注册先后(稳定排序)。
     */
    default float order() {
        return 0f;
    }

    /**
     * 本 provider 服务的 kind 集合(如内置 {@code file-content}/{@code file-name}/{@code task};
     * 插件自定义 {@code image} 等)。统一 search 核心按请求 {@code kinds} 与本次集合取交集过滤
     * 要跑的 provider;<b>缺省空集合</b>表示本 provider 不参与统一 search(如仅 {@code suggest} 的
     * {@link SuggestionProvider})。
     */
    default Set<String> kinds() {
        return Set.of();
    }

    /**
     * 统一搜索入口:{@code req} 携带工作区根/工作区 id/搜索词/不透明过滤袋/请求 kinds;
     * 返回本 provider(其自身 {@link #kinds()} 声明的 kind)的扁平结果项 + 自身触顶标志。
     * <b>缺省空实现</b>(仅能力接口 {@link SuggestionProvider} 不参与统一 search)。
     */
    default SearchResult search(SearchRequest req) {
        return SearchResult.empty();
    }

    /**
     * providerId 补齐:{@code providerId} 已自带非空时尊重不覆盖,否则回退聚合方传入的
     * 来源 id(调用方传 {@link #id()})。
     */
    static String providerIdOr(String providerId, String fallback) {
        return providerId == null || providerId.isBlank() ? fallback : providerId;
    }

    /**
     * 统一 search 入参(核心四要素的载体):{@code workspaceRoot}/{@code workspaceId} 由核心经
     * 沙箱 jailed 落定;{@code pattern} 与 {@code filters} 核心不解释;{@code kinds} 为请求过滤
     * (缺省空 = 全部已注册 provider)。{@code workspaceId} 在工作区未注册进注册表时可能为 null。
     */
    record SearchRequest(String workspaceId, Path workspaceRoot, String pattern,
            Map<String, Object> filters, List<String> kinds) {
    }

    /** provider 搜索应答:{@code items} 扁平结果项;{@code truncated} = 本 provider 自身触顶标志。 */
    record SearchResult(List<SearchResultItem> items, boolean truncated) {

        /** 空结果(无项、未触顶)。 */
        public static SearchResult empty() {
            return new SearchResult(List.of(), false);
        }
    }

    /**
     * 通用结果项:
     * <ul>
     *   <li>{@code kind} —— provider 自持类别(file-content / file-name / task / 插件自定义),必填;</li>
     *   <li>{@code providerId}/{@code score} —— 可选增补字段(自带 providerId 则尊重不覆盖;score 仅排序提示);</li>
     *   <li>{@code positionKey} —— 该 kind 内的位置键(同 kind 跨 provider 同位置去重),provider 自持;</li>
     *   <li>{@code fields} —— 展示字段袋(如 path/lineNumber/... 或 taskId/roundIndex/...),核心原样透传。</li>
     * </ul>
     */
    record SearchResultItem(String kind, String providerId, Double score, String positionKey,
            Map<String, Object> fields) {
    }
}