package dev.everyagent.plugin.workflow.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dev.everyagent.plugin.api.spi.SuggestionProvider;
import dev.everyagent.plugin.workflow.model.Definition;
import dev.everyagent.plugin.workflow.store.WorkflowStore;

/**
 * workflow 插件的「工作流定义名」@ 建议源({@link SuggestionProvider} 能力接口,经
 * {@code ctx.registerSearchProvider} 同口注册):搜索模式下把匹配查询词的工作流定义
 * 作为 {@code kind=workflow-definition} 合成建议增补进 mention.query 应答——定义不是
 * 磁盘文件,path 为合成键 {@code workflows/<definitionId>}(契约允许不落盘),元数据袋
 * 携带 {@code definitionId/definitionName}(前端据此渲染展示名并构造
 * {@code workflow.reference} 引用胶囊)。
 *
 * <p><b>匹配口径</b>:定义名大小写不敏感「包含」命中优先(词首命中再加分)、其次
 * 子序列命中;按命中质量降序,上限 5 条(实现细节——聚合方总条数另有 10 条截断与
 * 保底配额)。不参与统一 search({@code kinds()} 缺省空集)。
 */
public class WorkflowDefinitionSuggestProvider implements SuggestionProvider {

    /** 本 provider 来源 id(建议条目 providerId 缺省填此值)。 */
    public static final String ID = "plugin.workflow.suggest";

    /** 单次建议上限。 */
    static final int MAX_SUGGESTIONS = 5;

    /** 建议 kind(与前端 @ 弹层分派及 workflow.reference token 语义对齐)。 */
    public static final String KIND = "workflow-definition";

    private final WorkflowStore store;

    public WorkflowDefinitionSuggestProvider(WorkflowStore store) {
        this.store = store;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Suggestion> suggest(SuggestRequest req) {
        String wsId = req.workspaceId();
        String query = req.query();
        if (wsId == null || wsId.isBlank() || query == null || query.isBlank()) {
            return List.of();
        }
        String q = query.toLowerCase(Locale.ROOT);
        record Candidate(String definitionId, String name, int quality) {}
        List<Candidate> candidates = new ArrayList<>();
        for (Definition def : store.listDefinitions(wsId)) {
            String name = def.name() == null || def.name().isBlank()
                    ? def.id() : def.name();
            String lower = name.toLowerCase(Locale.ROOT);
            int quality;
            int idx = lower.indexOf(q);
            if (idx >= 0) {
                quality = (idx == 0 || isWordStart(lower, idx)) ? 3 : 2;
            } else if (def.id() != null && def.id().toLowerCase(Locale.ROOT).contains(q)) {
                quality = 1;
            } else {
                quality = subsequenceQuality(q, lower) ? 1 : 0;
            }
            if (quality > 0) {
                candidates.add(new Candidate(def.id(), name, quality));
            }
        }
        candidates.sort((a, b) -> Integer.compare(b.quality(), a.quality()));
        List<Suggestion> out = new ArrayList<>();
        for (Candidate c : candidates) {
            if (out.size() >= MAX_SUGGESTIONS) {
                break;
            }
            out.add(new Suggestion("workflows/" + c.definitionId(), KIND,
                    Map.of("definitionId", c.definitionId(), "definitionName", c.name())));
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
