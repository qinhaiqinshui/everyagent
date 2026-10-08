package dev.everyagent.plugin.api.spi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.nio.file.Path;
import java.util.List;

/**
 * 文件名搜索能力接口(架构 §8.5「能力接口扩展」)——{@link SearchProvider} 的子接口,
 * 与基接口同住 spi 包、**同样经 {@code ctx.registerSearchProvider} 注册**(registry 按
 * 接口分派能力,一个 provider 对象可同时实现多个能力接口,注册/反注册生命周期零改动)。
 *
 * <p><b>接线语义(增补聚合)</b>:worker 的 {@code fs.find} 在完成内置 ripgrep
 * {@code --files} 枚举 + 本侧 basename 匹配后,把 {@link #findFiles} 结果按
 * {@link #order()} 升序(同 order 保持注册先后)<b>增补聚合</b>进应答——内置结果在前、
 * 各 provider 结果追加在后,按 {@code kind=file}+{@code path} 去重(find 结果恒为文件,
 * 即按 path 去重),合并后仍受 {@code maxResults} 触顶约束(触顶置 {@code truncated});
 * 单个 provider 抛异常/超出超时预算仅 WARN 跳过,不影响内置结果与应答;未注册任何实现
 * 本接口的对象时零额外行为(基接口消费者 fs.search / task.search 亦不受影响)。
 *
 * <p>典型场景:在工作区外维护文件名索引(最近打开、frecency、git 索引等)的引擎,把
 * 索引命中补充进前端文件名搜索结果(如内置 rg 被 .gitignore 剪掉的文件)。
 */
public interface FileNameSearchProvider extends SearchProvider {

    /**
     * 文件名搜索(basename 语义,与 {@code fs.find} 入参同族)。
     *
     * @param req 文件名搜索请求
     * @return 结果列表;null/空列表不加项
     */
    List<FileResult> findFiles(FindRequest req);

    /**
     * 文件名搜索请求(与 {@code fs.find} 入参同族;{@code path} 为工作区相对搜索范围,
     * 缺省 {@code "."} = 整根)。{@code workspaceId} 在工作区未注册进注册表时可能为 null。
     */
    record FindRequest(String workspaceId, Path workspaceRoot, String pattern,
            boolean isRegex, boolean caseSensitive, boolean wholeWord,
            List<String> includeGlobs, List<String> excludeGlobs, String path, int maxResults) {}

    /**
     * 文件名搜索结果项:{@code path} 为工作区相对 posix 路径,形状与 {@code fs.find}
     * 应答项一致(单文件一项,无 matches 字段)。统一搜索结果模型(§8.5)的三个可选
     * 增补字段 {@code kind}(开放集合,本 RPC 缺省 {@code file})/{@code providerId}
     * (缺省视为 {@code builtin.rg},聚合时由 worker 填 {@code provider.id()})/
     * {@code score}(仅排序提示)均可空,null 时序列化省略。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record FileResult(String path, String kind, String providerId, Double score) {

        /** 兼容构造:不带统一搜索结果模型(§8.5)增补字段(kind/providerId/score = null)。 */
        public FileResult(String path) {
            this(path, null, null, null);
        }
    }
}
