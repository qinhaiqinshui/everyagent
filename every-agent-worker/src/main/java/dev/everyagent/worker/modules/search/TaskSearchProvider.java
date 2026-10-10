package dev.everyagent.worker.modules.search;

import dev.everyagent.plugin.api.exception.BadParamsException;
import dev.everyagent.plugin.api.spi.SearchProvider;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.task.RoundIndex;
import dev.everyagent.worker.task.TaskStore;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 内置「任务内容」搜索引擎(kind {@code task}):在任务数据目录内复用 {@link RgSearchEngine}
 * 搜索轮次索引文件 {@code rounds.jsonl},再由本侧做「JSON 转义消除」后处理(架构 §5.9)。
 *
 * <p><b>数据源</b>:任务按工作区归类落盘 {@code workspaces/<workspaceId>/tasks/<taskId>/};
 * 本 provider 从 {@link SearchProvider.SearchRequest#workspaceId()} 取稳定 id,经
 * {@link TaskStore#scanWorkspace} 定位该工作区任务根(工作区未注册时 workspaceId 为 null →
 * 无可搜任务,返回空)。只搜 rounds 轮文件(含 user/finalReply 正文),不搜事件日志。
 *
 * <p><b>后处理</b>:{@code rg} 命中的是 JSON 原始行(字段名/引号/转义符都会被搜到),故对每条命中行
 * 经 {@link TaskStore#parseRoundLine} 解析 → 抽取干净文本 user/finalReply → 用同一 pattern 二次匹配
 * 得准确 matchIndex/matchText;干净文本不命中(纯命中原 JSON 字段名/语法)则丢弃。
 *
 * <p><b>provider 自持职责</b>:结果上限 = {@code worker.search.task-max-results};自解释
 * {@code task.isRegex}/{@code .caseSensitive}/{@code .wholeWord}。匹配语义与文件内容搜索共用
 * {@link RgSearchEngine#buildMatchArgs},保证 pattern 行为一致。
 */
@Component
public class TaskSearchProvider implements SearchProvider {

    /** 本 provider 服务的 kind。 */
    public static final String KIND = "task";
    /** 本 provider 来源 id(结果项 providerId 缺省填此值)。 */
    public static final String ID = "builtin.task";
    /** 内置引擎排序权重(排在插件 provider 之前)。 */
    static final float ORDER = -998f;

    private final TaskStore store;
    private final RgSearchEngine rg;
    private final WorkerProperties props;

    public TaskSearchProvider(TaskStore store, RgSearchEngine rg, WorkerProperties props) {
        this.store = store;
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
        String workspaceId = req.workspaceId();
        if (workspaceId == null || workspaceId.isBlank()) {
            return SearchResult.empty(); // 工作区未注册:无可搜任务
        }
        if (!rg.available()) {
            throw new SearchEngineException("rg 不可用: 未找到内置 ripgrep(<程序根>/runtime/bin/ 或"
                    + " worker.tools.rg-path),无法执行任务搜索");
        }
        int maxResults = props.getSearch().getTaskMaxResults();
        Map<String, Object> f = req.filters();
        boolean isRegex = FilterBag.bool(f, KIND, "isRegex");
        boolean caseSensitive = FilterBag.bool(f, KIND, "caseSensitive");
        boolean wholeWord = FilterBag.bool(f, KIND, "wholeWord");
        Pattern pat;
        try {
            pat = compileSearchPattern(req.pattern(), isRegex, caseSensitive, wholeWord);
        } catch (PatternSyntaxException e) {
            throw new BadParamsException("正则表达式非法: " + e.getMessage());
        }
        List<String> matchArgs = RgSearchEngine.buildMatchArgs(req.pattern(), isRegex, caseSensitive, wholeWord);
        List<TaskStore.StoredTask> tasks = store.scanWorkspace(workspaceId);

        List<SearchResultItem> items = new ArrayList<>();
        int count = 0;
        boolean truncated = false;
        for (TaskStore.StoredTask st : tasks) {
            if (count >= maxResults) {
                truncated = true;
                break;
            }
            if (!Files.isRegularFile(st.dir().resolve("rounds.jsonl"))) {
                continue; // 极老任务无轮次索引:不参与搜索
            }
            int before = items.size();
            boolean taskTruncated = runTask(st, matchArgs, pat, maxResults - count, items);
            count += items.size() - before;
            if (taskTruncated) {
                truncated = true;
                break; // 本任务已触顶,不再枚举后续任务
            }
        }
        return new SearchResult(items, truncated);
    }

    /**
     * 对单个任务目录跑 rg(搜索路径恒 rounds.jsonl,cwd = 任务目录,jailed 于任务数据目录):
     * 与文件内容引擎同款进程管理(stderr 抽干 / 超时 watchdog 强杀 / 触顶即 kill / 退出码校验);
     * 行处理为「解析 JSON 行 → 抽取 user/finalReply 干净文本二次匹配」。返回是否触顶。
     */
    private boolean runTask(TaskStore.StoredTask st, List<String> matchArgs, Pattern pat,
            int remaining, List<SearchResultItem> items) {
        List<String> args = new ArrayList<>(matchArgs);
        args.add("rounds.jsonl");
        int before = items.size();
        RgSearchEngine.StreamOutcome r;
        try {
            r = rg.exec(st.dir(), args, line -> {
                RgSearchEngine.RawHit hit = RgSearchEngine.parseMatchLine(line);
                if (hit == null) {
                    return true; // begin/end/summary 等非 match 记录
                }
                RoundIndex.Round round = TaskStore.parseRoundLine(hit.line());
                if (round == null) {
                    return true; // 撕行/坏行:静默跳过
                }
                appendFieldHits(items, st, round, pat, hit.lineNumber());
                int added = items.size() - before;
                if (added >= remaining) {
                    // 一行 user+finalReply 可能加 2 条:截断到 remaining,保证单任务命中数不越界
                    if (added > remaining) {
                        items.subList(before + remaining, items.size()).clear();
                    }
                    return false; // 触顶:不再消费输出
                }
                return true;
            });
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new SearchEngineException("任务搜索执行失败: " + e.getMessage(), e);
        }
        return rg.finishTruncated(r, items.size() - before, KIND);
    }

    /**
     * 对一轮的 user / finalReply 分别做干净文本二次匹配;roundIndex 优先取轮内 index 字段,
     * 缺失/异常时回退 rg 物理行号(rounds 每行一轮,物理行号 ≈ 轮序号)。
     */
    private static void appendFieldHits(List<SearchResultItem> items, TaskStore.StoredTask st,
            RoundIndex.Round round, Pattern pat, long fallbackRoundIndex) {
        appendFieldHit(items, st, round, pat, "user", round.user(), fallbackRoundIndex);
        appendFieldHit(items, st, round, pat, "finalReply", round.finalReply(), fallbackRoundIndex);
    }

    /** 单字段二次匹配:干净文本命中才产出命中条目(消除 JSON 字段名/转义噪音)。 */
    private static void appendFieldHit(List<SearchResultItem> items, TaskStore.StoredTask st,
            RoundIndex.Round round, Pattern pat, String field, String text, long fallbackRoundIndex) {
        if (text == null || text.isEmpty()) {
            return;
        }
        Matcher m = pat.matcher(text);
        if (!m.find()) {
            return;
        }
        int roundIndex = round.index() > 0 ? (int) round.index() : (int) fallbackRoundIndex;
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("taskId", st.taskId());
        fields.put("title", st.summary().path("title").asString("任务 " + st.taskId()));
        fields.put("status", st.summary().path("status").asString(""));
        fields.put("roundIndex", roundIndex);
        fields.put("field", field);
        fields.put("line", text);
        fields.put("matchIndex", m.start());
        fields.put("matchText", m.group());
        String positionKey = st.taskId() + "\u0000" + roundIndex + "\u0000" + field + "\u0000" + m.start();
        items.add(new SearchResultItem(KIND, null, null, positionKey, fields));
    }

    /**
     * 编译二次匹配用的 Java Pattern(与 rg 的 pattern 语义对齐:固定串转义 / 全字包裹 /
     * 大小写敏感标志)。不加 DOTALL:rg 单行搜索不跨行,Java Pattern 默认 '.' 也不匹配换行。
     */
    static Pattern compileSearchPattern(String pattern, boolean isRegex, boolean caseSensitive,
            boolean wholeWord) {
        String source = isRegex ? pattern : RgSearchEngine.escapeRegex(pattern);
        if (wholeWord) {
            source = "\\b(?:" + source + ")\\b";
        }
        return Pattern.compile(source, (caseSensitive ? 0 : Pattern.CASE_INSENSITIVE));
    }
}