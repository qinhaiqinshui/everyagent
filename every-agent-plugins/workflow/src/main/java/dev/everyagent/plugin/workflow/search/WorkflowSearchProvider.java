package dev.everyagent.plugin.workflow.search;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import dev.everyagent.plugin.api.exception.BadParamsException;
import dev.everyagent.plugin.api.spi.SearchProvider;
import dev.everyagent.plugin.workflow.model.Definition;
import dev.everyagent.plugin.workflow.model.EventLine;
import dev.everyagent.plugin.workflow.model.InstanceMeta;
import dev.everyagent.plugin.workflow.model.InstanceStatus;
import dev.everyagent.plugin.workflow.model.NodeDef;
import dev.everyagent.plugin.workflow.store.WorkflowStore;
import tools.jackson.databind.JsonNode;

/**
 * workflow 插件的统一搜索后端（架构 §8.5 插件 provider 与内置同权）：服务两个 kind——
 * <ul>
 *   <li>{@link #KIND_DEFINITION}（工作流定义）：搜定义的<b>结构化文本字段</b>——名称/描述/
 *       各节点的命令、输入模板、系统提示。定义落盘是结构化 JSON
 *       （definitions/&lt;wf&gt;/definition.json），直接搜 {@link WorkflowStore} 内存索引的
 *       字段值即可拿到干净文本命中（无 JSON 转义噪音），无需 rg（插件模块不依赖 worker
 *       内部引擎）；</li>
 *   <li>{@link #KIND_INSTANCE}（工作流实例）：搜实例<b>过程内容</b>——events.jsonl 事件流的
 *       干净文本抽取（node.output 的 outputPreview / node.started 的 inputPreview /
 *       node.error 的 message / wf.askUser 的 question / supervisor.decision 的 input 与
 *       reason），思路与内置 task 搜索一致（抽干净文本后用同一 pattern 二次匹配）；
 *       另搜元数据「工作流名」（definitionName）。</li>
 * </ul>
 *
 * <p><b>provider 自持职责</b>（核心不解释 pattern/filters）：结果上限
 * {@link #MAX_RESULTS_PER_KIND}（按 kind 分别计数，触顶 truncated）；过滤袋自解释
 * {@code workflow-instance.status}（实例状态过滤，值=状态 wire 串如 running/done）与
 * 两 kind 各自的 {@code .isRegex/.caseSensitive/.wholeWord}；命中条目 fields 自带
 * definitionId/instanceId 等元数据（前端 ResultView 分组/跳转用），positionKey 保证
 * 同 kind 去重键唯一。
 */
public class WorkflowSearchProvider implements SearchProvider {

    /** 本 provider 服务的 kind：工作流定义。 */
    public static final String KIND_DEFINITION = "workflow-definition";

    /** 本 provider 服务的 kind：工作流实例（过程内容）。 */
    public static final String KIND_INSTANCE = "workflow-instance";

    /** 本 provider 来源 id（结果项 providerId 缺省填此值）。 */
    public static final String ID = "plugin.workflow";

    /** 单 kind 结果上限（两 kind 分别计数；触顶置 truncated 并停止枚举）。 */
    static final int MAX_RESULTS_PER_KIND = 60;

    /** 单实例事件读取上限（events.jsonl 完整事实源，搜索不无限读）。 */
    private static final int MAX_EVENTS_PER_INSTANCE = 2000;

    /** 命中文本展示截断（命中位置前后保留窗，防超长 prompt 撑爆结果行）。 */
    private static final int SNIPPET_MAX = 240;

    private final WorkflowStore store;

    /** 测试用结果上限（0/负数=取默认上限）。 */
    private final int maxResultsPerKind;

    public WorkflowSearchProvider(WorkflowStore store) {
        this(store, 0);
    }

    /** 测试用结果上限构造（0/负数=取默认上限）。 */
    public WorkflowSearchProvider(WorkflowStore store, int maxResultsPerKind) {
        this.store = store;
        this.maxResultsPerKind = maxResultsPerKind > 0 ? maxResultsPerKind : MAX_RESULTS_PER_KIND;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Set<String> kinds() {
        return Set.of(KIND_DEFINITION, KIND_INSTANCE);
    }

    @Override
    public SearchResult search(SearchRequest req) {
        String wsId = req.workspaceId();
        if (wsId == null || wsId.isBlank() || req.pattern() == null || req.pattern().isBlank()) {
            return SearchResult.empty(); // 工作区未注册 / 空搜索词：无可搜内容
        }
        boolean wantDef = wantKind(req.kinds(), KIND_DEFINITION);
        boolean wantInst = wantKind(req.kinds(), KIND_INSTANCE);
        if (!wantDef && !wantInst) {
            return SearchResult.empty();
        }

        List<SearchResultItem> items = new ArrayList<>();
        boolean truncated = false;
        if (wantDef) {
            truncated |= searchDefinitions(req, wsId, items);
        }
        if (wantInst) {
            truncated |= searchInstances(req, wsId, items);
        }
        return new SearchResult(items, truncated);
    }

    /** 请求 kinds（缺省/空=全部已注册 provider → 本 provider 全部 kind）是否含指定 kind。 */
    private static boolean wantKind(List<String> kinds, String kind) {
        return kinds == null || kinds.isEmpty() || kinds.contains(kind);
    }

    // ══════════════════════════ 工作流定义 ══════════════════════════

    /**
     * 定义搜索：遍历内存索引（listDefinitions 按 updatedAt 倒序），对「名称/描述/节点命令/
     * 输入模板/系统提示」逐字段干净文本匹配。返回是否触顶。
     */
    private boolean searchDefinitions(SearchRequest req, String wsId, List<SearchResultItem> items) {
        Map<String, Object> f = req.filters();
        Pattern pat = compilePattern(req.pattern(),
                flag(f, KIND_DEFINITION, "isRegex"),
                flag(f, KIND_DEFINITION, "caseSensitive"),
                flag(f, KIND_DEFINITION, "wholeWord"));
        int cap = maxResultsPerKind;
        for (Definition def : store.listDefinitions(wsId)) {
            if (items.size() >= cap) {
                return true;
            }
            String defId = orEmpty(def.id());
            addFieldHit(items, KIND_DEFINITION, cap, defId,
                    defFields(defId, def), "名称", def.name(), pat);
            addFieldHit(items, KIND_DEFINITION, cap, defId,
                    defFields(defId, def), "描述", def.description(), pat);
            for (NodeDef node : def.nodesOrEmpty()) {
                String nodeName = node.name() == null || node.name().isBlank()
                        ? orEmpty(node.id()) : node.name();
                addFieldHit(items, KIND_DEFINITION, cap, defId, defFields(defId, def),
                        "节点「" + nodeName + "」命令", node.command(), pat);
                addFieldHit(items, KIND_DEFINITION, cap, defId, defFields(defId, def),
                        "节点「" + nodeName + "」输入", node.input(), pat);
                addFieldHit(items, KIND_DEFINITION, cap, defId, defFields(defId, def),
                        "节点「" + nodeName + "」系统提示", node.systemPrompt(), pat);
            }
        }
        return items.size() >= cap;
    }

    /** 定义结果项的公共元数据（每个命中条目都带，前端分组/跳转直接取用）。 */
    private Map<String, Object> defFields(String defId, Definition def) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("definitionId", defId);
        fields.put("name", orEmpty(def.name()));
        fields.put("workspaceId", orEmpty(def.workspaceId()));
        fields.put("updatedAt", def.updatedAt());
        return fields;
    }

    // ══════════════════════════ 工作流实例 ══════════════════════════

    /**
     * 实例搜索：可选状态过滤（filters {@code workflow-instance.status}）→ 元数据「工作流名」
     * 匹配 + events.jsonl 干净文本抽取匹配（每实例事件读取有上限）。返回是否触顶。
     */
    private boolean searchInstances(SearchRequest req, String wsId, List<SearchResultItem> items) {
        Map<String, Object> f = req.filters();
        String statusFilter = str(f, KIND_INSTANCE, "status");
        Pattern pat = compilePattern(req.pattern(),
                flag(f, KIND_INSTANCE, "isRegex"),
                flag(f, KIND_INSTANCE, "caseSensitive"),
                flag(f, KIND_INSTANCE, "wholeWord"));
        int cap = maxResultsPerKind;
        for (InstanceMeta meta : store.listInstances(wsId, null)) {
            if (items.size() >= cap) {
                return true;
            }
            if (statusFilter != null && !statusFilter.isBlank()
                    && !statusFilter.equalsIgnoreCase(statusWire(meta.status()))) {
                continue;
            }
            String wiId = orEmpty(meta.id());
            Map<String, Object> fields = instanceFields(meta);
            addFieldHit(items, KIND_INSTANCE, cap, wiId, fields, "工作流名",
                    meta.definitionName(), pat);
            for (EventLine line : store.readEvents(wsId, wiId, 0, MAX_EVENTS_PER_INSTANCE)) {
                if (items.size() >= cap) {
                    return true;
                }
                for (String[] clean : cleanEventTexts(line)) {
                    addFieldHit(items, KIND_INSTANCE, cap, wiId, fields, clean[0], clean[1], pat);
                }
            }
        }
        return items.size() >= cap;
    }

    /** 实例结果项的公共元数据（每个命中条目都带）。 */
    private Map<String, Object> instanceFields(InstanceMeta meta) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("instanceId", orEmpty(meta.id()));
        fields.put("definitionId", orEmpty(meta.definitionId()));
        fields.put("definitionName", orEmpty(meta.definitionName()));
        fields.put("status", statusWire(meta.status()));
        fields.put("workspaceId", orEmpty(meta.workspaceId()));
        fields.put("originTaskId", meta.originTaskId() == null ? "" : meta.originTaskId());
        fields.put("createdAt", meta.createdAt());
        fields.put("endedAt", meta.endedAt());
        return fields;
    }

    /**
     * 事件行 → 可搜索的干净文本对（字段展示名 + 文本），按事件类型抽取（与
     * {@code instance.events} wire 契约的 payload 字段一致）；无正文的行返回空表。
     */
    private static List<String[]> cleanEventTexts(EventLine line) {
        JsonNode p = line.payload();
        if (p == null || p.isMissingNode() || p.isNull()) {
            return List.of();
        }
        List<String[]> out = new ArrayList<>(2);
        switch (line.event() == null ? "" : line.event()) {
            case "node.started" -> addClean(out, "节点输入", p.path("inputPreview"));
            case "node.output" -> addClean(out, "节点输出", p.path("outputPreview"));
            case "node.error" -> addClean(out, "节点错误", p.path("message"));
            case "wf.askUser" -> addClean(out, "向用户提问", p.path("question"));
            case "supervisor.decision" -> {
                addClean(out, "决策输入", p.path("input"));
                addClean(out, "决策理由", p.path("reason"));
            }
            default -> {
                // wf.created/wf.status/node.skipped/wf.cancelled/wf.done 等元事件：无可搜正文
            }
        }
        return out;
    }

    private static void addClean(List<String[]> out, String label, JsonNode node) {
        String text = node == null ? "" : node.asString("");
        if (text != null && !text.isBlank()) {
            out.add(new String[] { label, text });
        }
    }

    // ══════════════════════════ 命中条目装配 ══════════════════════════

    /**
     * 单字段二次匹配：干净文本命中才产出条目（消除结构噪音）；超长文本按命中位置截窗。
     * positionKey = {@code 主体id \0 字段 \0 命中起点}（同 kind 去重键唯一）。
     */
    private static void addFieldHit(List<SearchResultItem> items, String kind, int cap,
            String subjectId, Map<String, Object> meta, String field, String text, Pattern pat) {
        if (text == null || text.isBlank() || items.size() >= cap) {
            return;
        }
        Matcher m = pat.matcher(text);
        if (!m.find()) {
            return;
        }
        int start = m.start();
        String snippet = text;
        int snippetStart = 0;
        if (text.length() > SNIPPET_MAX) {
            snippetStart = Math.max(0, Math.min(start - 60, text.length() - SNIPPET_MAX));
            snippet = (snippetStart > 0 ? "…" : "")
                    + text.substring(snippetStart, Math.min(text.length(), snippetStart + SNIPPET_MAX))
                    + (snippetStart + SNIPPET_MAX < text.length() ? "…" : "");
        }
        Map<String, Object> fields = new LinkedHashMap<>(meta);
        fields.put("field", field);
        fields.put("line", snippet);
        fields.put("matchIndex", start - snippetStart);
        fields.put("matchText", m.group());
        String positionKey = subjectId + "\u0000" + field + "\u0000" + start;
        items.add(new SearchResultItem(kind, null, null, positionKey, fields));
    }

    // ══════════════════════════ 小工具（过滤袋 / pattern） ══════════════════════════

    /** 过滤袋取 {@code kind.field} 布尔值（兼容 JSON 布尔与字符串 "true"；缺省 false）。 */
    private static boolean flag(Map<String, Object> filters, String kind, String field) {
        Object v = filters == null ? null : filters.get(kind + "." + field);
        if (v instanceof Boolean b) {
            return b;
        }
        return v != null && "true".equalsIgnoreCase(String.valueOf(v).trim());
    }

    /** 过滤袋取 {@code kind.field} 字符串值（缺省 null）。 */
    private static String str(Map<String, Object> filters, String kind, String field) {
        Object v = filters == null ? null : filters.get(kind + "." + field);
        return v == null ? null : String.valueOf(v);
    }

    /** null 安全空串。 */
    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    /** 实例状态 → wire 串（与 meta.json 的 @JsonProperty 值一致，如 waiting-user）。 */
    static String statusWire(InstanceStatus status) {
        return status == null ? "" : status.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /**
     * 编译匹配 Pattern（与内置引擎匹配语义同族：固定串转义正则元字符 / 全字包
     * {@code \b(?:...)\b} / 大小写敏感标志）。方言为 Java {@link Pattern}——本 provider
     * 纯 Java 匹配（不跑 rg，见类注释）。
     */
    static Pattern compilePattern(String pattern, boolean isRegex, boolean caseSensitive,
            boolean wholeWord) {
        String source;
        try {
            source = isRegex ? pattern : escapeRegex(pattern);
        } catch (PatternSyntaxException e) {
            throw new BadParamsException("正则表达式非法: " + e.getMessage());
        }
        if (wholeWord) {
            source = "\\b(?:" + source + ")\\b";
        }
        try {
            return Pattern.compile(source, caseSensitive ? 0 : Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException e) {
            throw new BadParamsException("正则表达式非法: " + e.getMessage());
        }
    }

    /**
     * 转义正则元字符（与内置 rg 引擎 escapeRegex 同集：
     * {@code \ { } * + ? | ^ $ . [ ] ( )}——Java Pattern 亦需逐字符转义，兼容且无害）。
     */
    static String escapeRegex(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ("\\{}*+?|^$.[]()".indexOf(c) >= 0) {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
