package dev.everyagent.plugin.api.spi;

import java.nio.file.Path;
import java.util.List;

/**
 * {@code @} 建议(mention)能力接口(架构 §8.5「能力接口扩展」)——{@link SearchProvider}
 * 的子接口,与基接口同住 spi 包、**同样经 {@code ctx.registerSearchProvider} 注册**
 * (registry 按接口分派能力,一个 provider 对象可同时实现多个能力接口,注册/反注册
 * 生命周期零改动)。
 *
 * <p><b>接线语义(增补聚合)</b>:worker 的 {@code mention.query} 在搜索模式(非空
 * query)完成内置四档打分排序后,把 {@link #suggest} 结果按 {@link #order()} 升序
 * (同 order 保持注册先后)<b>增补聚合</b>进应答——内置结果在前、各 provider 结果追加
 * 在后,按 {@code kind=file}+{@code path} 去重,总条数仍受截断约束(10 条);单个
 * provider 抛异常/超出超时预算仅 WARN 跳过,不影响内置结果与应答;未注册任何实现本
 * 接口的对象时零额外行为(浏览模式列目录顶层,不经本接口)。建议项同样适用统一可选
 * 增补字段口径(§8.5,must-ignore)。
 *
 * <p>典型场景:为 {@code @} 文件引用补充工作区外的候选源(最近打开文件、任务附件索引、
 * issue/文档引用等)。
 */
public interface SuggestionProvider extends SearchProvider {

    /**
     * {@code @} 建议查询。
     *
     * @param req 建议请求
     * @return 建议列表(调用方按返回序并入聚合,provider 自行排好序);null/空列表不加项
     */
    List<Suggestion> suggest(SuggestRequest req);

    /**
     * 建议请求:{@code query} 为用户已输入的 {@code @} 搜索词(非空,已 trim);
     * {@code path} 为浏览基准目录的工作区相对 posix 路径(缺省 {@code "."} = 根)。
     * {@code workspaceId} 在工作区未注册进注册表时可能为 null。
     */
    record SuggestRequest(String workspaceId, Path workspaceRoot, String query, String path) {}

    /**
     * 建议项:{@code path} 为工作区相对 posix 路径(应答派生 {@code name}/{@code fullPath},
     * 不要求文件当前在磁盘上存在——索引型 provider 可建议近期路径);{@code kind} 缺省
     * 归一为 {@code "file"}(去重键 = {@code kind}+{@code path})。
     */
    record Suggestion(String path, String kind) {

        public Suggestion {
            if (kind == null || kind.isBlank()) {
                kind = "file";
            }
        }

        /** 便捷构造:kind 取缺省 {@code file}。 */
        public Suggestion(String path) {
            this(path, "file");
        }
    }
}
