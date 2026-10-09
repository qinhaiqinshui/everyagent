package dev.everyagent.worker.modules.search;

import dev.everyagent.plugin.api.exception.BadParamsException;
import dev.everyagent.plugin.api.spi.SearchProvider;
import dev.everyagent.worker.config.WorkerProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 内置「文件名」搜索引擎(kind {@code file-name}):复用 {@link RgSearchEngine} 的
 * {@code rg --files} 枚举 + 本侧 basename 正则匹配(架构 §5.10)。替代前端逐目录
 * {@code fs.list} 递归 walk;rg 遵循 .gitignore、原生跳过不可读条目。
 *
 * <p><b>provider 自持职责三件套</b>:① 结果上限 = {@code worker.search.file-max-results};
 * ② 默认排除 {@code .git};③ 自解释 {@code file-name.isRegex}/{@code .caseSensitive}/
 * {@code .wholeWord}/{@code .include}/{@code .exclude}/{@code .scope}(scope 经沙箱 jailed 下推)。
 */
@Component
public class FileNameSearchProvider implements SearchProvider {

    /** 本 provider 服务的 kind。 */
    public static final String KIND = "file-name";
    /** 本 provider 来源 id(结果项 providerId 缺省填此值)。 */
    public static final String ID = "builtin.file-name";
    /** 内置引擎排序权重(排在插件 provider 之前)。 */
    static final float ORDER = -999f;

    private final RgSearchEngine rg;
    private final WorkerProperties props;

    public FileNameSearchProvider(RgSearchEngine rg, WorkerProperties props) {
        this.rg = rg;
        this.props = props;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Set<String> kinds() {
        return Set.of(KIND);
    }

    @Override
    public float order() {
        return ORDER;
    }

    @Override
    public SearchResult search(SearchRequest req) {
        if (!rg.available()) {
            throw new SearchEngineException("rg 不可用: 未找到内置 ripgrep(<程序根>/runtime/bin/ 或"
                    + " worker.tools.rg-path),无法执行工作区搜索");
        }
        int maxResults = props.getSearch().getFileMaxResults();
        Map<String, Object> f = req.filters();
        boolean isRegex = FilterBag.bool(f, KIND, "isRegex");
        boolean caseSensitive = FilterBag.bool(f, KIND, "caseSensitive");
        boolean wholeWord = FilterBag.bool(f, KIND, "wholeWord");
        List<String> include = RgSearchEngine.splitGlobs(FilterBag.str(f, KIND, "include"));
        List<String> exclude = new ArrayList<>(RgSearchEngine.splitGlobs(FilterBag.str(f, KIND, "exclude")));
        exclude.add(".git/**"); // 文件类 provider 自持「默认排除 .git」(核心不追加)
        Pattern nameRegex;
        try {
            nameRegex = RgSearchEngine.compileNamePattern(req.pattern(), isRegex, caseSensitive, wholeWord);
        } catch (PatternSyntaxException e) {
            throw new BadParamsException("正则表达式非法: " + e.getMessage());
        }
        Path root = req.workspaceRoot();
        String scope = RgSearchEngine.resolveScope(root, FilterBag.str(f, KIND, "scope"));
        List<String> args = RgSearchEngine.buildFileArgs(include, exclude, scope);

        List<SearchResultItem> items = new ArrayList<>();
        RgSearchEngine.StreamOutcome r;
        try {
            r = rg.exec(root, args, line -> {
                String rel = RgSearchEngine.relPath(root, line);
                String base = rel.substring(rel.lastIndexOf('/') + 1);
                if (!nameRegex.matcher(base).find()) {
                    return true;
                }
                items.add(fileItem(rel));
                return items.size() < maxResults;
            });
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new SearchEngineException("工作区文件名搜索执行失败: " + e.getMessage(), e);
        }
        boolean truncated = rg.finishTruncated(r, items.size(), KIND);
        return new SearchResult(items, truncated);
    }

    /**
     * 文件名命中项:{@code {kind, path}}(无 matches 字段);位置键 = {@code path}
     * (同 kind 跨 provider 同路径去重)。
     */
    private static SearchResultItem fileItem(String path) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("path", path);
        return new SearchResultItem(KIND, null, null, path, fields);
    }
}