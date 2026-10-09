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
 * 内置「文本文件内容」搜索引擎(kind {@code file-content}):复用 {@link RgSearchEngine}
 * 的 {@code rg --json} 逐行解析(架构 §5.10),把命中行做成扁平结果项。
 *
 * <p><b>provider 自持职责三件套</b>(核心不传):
 * <ol>
 *   <li>结果上限 = {@code worker.search.file-max-results}(缺省 1000,触顶即 kill rg 置 truncated);</li>
 *   <li>默认排除 {@code .git}(文件类 provider 职责,核心不追加);</li>
 *   <li>自解释过滤袋中自己声明的字段:{@code file-content.isRegex}/{@code .caseSensitive}/
 *       {@code .wholeWord}/{@code .include}/{@code .exclude}/{@code .scope};其中范围类字段
 *       {@code scope} 经 {@link RgSearchEngine#resolveScope} 用沙箱 jailed(realpath + 前缀校验)后
 *       作为 rg 的搜索路径参数下推。</li>
 * </ol>
 */
@Component
public class FileContentSearchProvider implements SearchProvider {

    /** 本 provider 服务的 kind。 */
    public static final String KIND = "file-content";
    /** 本 provider 来源 id(结果项 providerId 缺省填此值)。 */
    public static final String ID = "builtin.file-content";
    /** 内置引擎排序权重(排在插件 provider 之前)。 */
    static final float ORDER = -1000f;

    private final RgSearchEngine rg;
    private final WorkerProperties props;

    public FileContentSearchProvider(RgSearchEngine rg, WorkerProperties props) {
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
        // 正则模式先本地试编译:非法正则在入口即拦成可读的 rpc.err(BAD_PARAMS)。
        if (isRegex) {
            try {
                Pattern.compile(req.pattern());
            } catch (PatternSyntaxException e) {
                throw new BadParamsException("正则表达式非法: " + e.getMessage());
            }
        }
        Path root = req.workspaceRoot();
        String scope = RgSearchEngine.resolveScope(root, FilterBag.str(f, KIND, "scope"));
        List<String> args = RgSearchEngine.buildArgs(req.pattern(), isRegex, caseSensitive, wholeWord,
                include, exclude, scope);

        List<SearchResultItem> items = new ArrayList<>();
        int[] count = {0};
        RgSearchEngine.StreamOutcome r;
        try {
            r = rg.exec(root, args, line -> {
                RgSearchEngine.RawHit hit = RgSearchEngine.parseMatchLine(line);
                if (hit == null) {
                    return true; // begin/end/summary 等非 match 记录
                }
                String path = RgSearchEngine.relPath(root, hit.rawPath());
                items.add(matchItem(path, hit));
                return ++count[0] < maxResults; // 计数触顶即停止消费(不用 --max-count)
            });
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new SearchEngineException("工作区内容搜索执行失败: " + e.getMessage(), e);
        }
        boolean truncated = rg.finishTruncated(r, count[0], KIND);
        return new SearchResult(items, truncated);
    }

    /**
     * 文件内容命中项:{@code {kind, path, lineNumber, line, matchIndex, matchText}},
     * 位置键 = {@code path+lineNumber+matchIndex}(同 kind 跨 provider 同位置去重)。
     */
    private static SearchResultItem matchItem(String path, RgSearchEngine.RawHit hit) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("path", path);
        fields.put("lineNumber", hit.lineNumber());
        fields.put("line", hit.line());
        fields.put("matchIndex", hit.matchIndex());
        fields.put("matchText", hit.matchText());
        String positionKey = path + "\u0000" + hit.lineNumber() + "\u0000" + hit.matchIndex();
        return new SearchResultItem(KIND, null, null, positionKey, fields);
    }
}