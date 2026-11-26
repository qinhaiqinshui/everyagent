package dev.everyagent.worker.modules.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import dev.everyagent.plugin.api.spi.SuggestionProvider;
import dev.everyagent.worker.task.TaskStore;

/**
 * 内置「任务名」@ 建议源(§8.5 能力接口, {@link SuggestionProvider}):搜索模式下把
 * 匹配查询词的任务作为 {@code kind=task-reference} 合成建议增补进 mention.query 应答
 * ——任务不是磁盘文件,path 为合成键 {@code tasks/<taskId>}(契约允许不落盘),元数据袋
 * 携带 {@code taskId/title}(前端据此渲染展示名并构造 {@code task.reference} 引用胶囊)。
 *
 * <p><b>匹配口径</b>:任务标题(summary.title,缺省「任务 &lt;taskId&gt;」)大小写不敏感
 * 「包含」命中优先(词首命中再加分)、其次子序列命中;按命中质量降序,上限 5 条(实现
 * 细节——聚合方总条数另有 10 条截断与保底配额)。不参与统一 search({@code kinds()}
 * 缺省空集)。
 */
public class TaskNameSuggestProvider implements SuggestionProvider {

    /** 本 provider 来源 id(建议条目 providerId 缺省填此值)。 */
    public static final String ID = "builtin.task-suggest";

    /** 单次建议上限。 */
    static final int MAX_SUGGESTIONS = 5;

    /** 建议 kind(与前端 @ 弹层分派及 task.reference token 语义对齐)。 */
    public static final String KIND = "task-reference";

    private final TaskStore store;

    public TaskNameSuggestProvider(TaskStore store) {
        this.store = store;
    }

    @Override
    public String id() {
        return ID;
    }

    /** 内置建议源排序权重(先于插件建议源;仅影响建议聚合序)。 */
    @Override
    public float order() {
        return -990f;
    }

    @Override
    public List<Suggestion> suggest(SuggestRequest req) {
        String wsId = req.workspaceId();
        String query = req.query();
        if (wsId == null || wsId.isBlank() || query == null || query.isBlank()) {
            return List.of();
        }
        String q = query.toLowerCase(Locale.ROOT);
        record Candidate(String taskId, String title, int quality) {}
        List<Candidate> candidates = new ArrayList<>();
        for (TaskStore.StoredTask st : store.scanWorkspace(wsId)) {
            String title = st.summary() == null
                    ? null
                    : st.summary().path("title").asString("");
            if (title == null || title.isBlank()) {
                title = "任务 " + st.taskId();
            }
            String lower = title.toLowerCase(Locale.ROOT);
            int quality;
            int idx = lower.indexOf(q);
            if (idx >= 0) {
                // 包含命中:词首命中加分(与内置文件打分的词首优先口径同族)
                quality = (idx == 0 || isWordStart(lower, idx)) ? 3 : 2;
            } else {
                quality = subsequenceQuality(q, lower) ? 1 : 0;
            }
            if (quality > 0) {
                candidates.add(new Candidate(st.taskId(), title, quality));
            }
        }
        candidates.sort((a, b) -> Integer.compare(b.quality(), a.quality()));
        List<Suggestion> out = new ArrayList<>();
        for (Candidate c : candidates) {
            if (out.size() >= MAX_SUGGESTIONS) {
                break;
            }
            out.add(new Suggestion("tasks/" + c.taskId(), KIND,
                    java.util.Map.of("taskId", c.taskId(), "title", c.title())));
        }
        return out;
    }

    /** 词首判定(下划线/非字母数字后为首字符,与内置文件打分 isWordStart 同族)。 */
    private static boolean isWordStart(String s, int idx) {
        if (idx <= 0) {
            return true;
        }
        char prev = s.charAt(idx - 1);
        return !Character.isLetterOrDigit(prev);
    }

    /** 子序列命中判定(q 的字符按序出现在 s 中)。 */
    private static boolean subsequenceQuality(String q, String s) {
        int i = 0;
        for (int j = 0; j < s.length() && i < q.length(); j++) {
            if (s.charAt(j) == q.charAt(i)) {
                i++;
            }
        }
        return i == q.length();
    }
}
