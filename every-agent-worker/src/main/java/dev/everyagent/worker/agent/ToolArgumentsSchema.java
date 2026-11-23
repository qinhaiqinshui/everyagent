package dev.everyagent.worker.agent;

import dev.everyagent.contract.json.Json;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 工具入参 vs 声明 schema 的「必填缺失」校验（{@link ToolArgumentsGuardInterceptor} 的纯逻辑核，
 * 无 Spring 上下文可测）。
 *
 * <p>schema 来源 = {@link ToolDefinition#inputSchema()}（{@code MethodToolCallbackProvider} 经
 * JsonSchemaGenerator 按 {@code @ToolParam} 生成：{@code properties} + {@code required}；
 * {@code FunctionToolCallback} 同样有 inputSchema）。口径：
 * <ul>
 *   <li>只判顶层键缺失 / 值为 JSON null；嵌套结构不校验（不重造 JSON Schema 校验器）；</li>
 *   <li>入参空白按 {@code "{}"}（框架同款归一）；非 JSON 对象 / 不可解析 → null（放行，框架原生
 *       转换失败报错兜底）；</li>
 *   <li>schema 缺失 / 非对象 / 无 required → null（无可校验之物）；</li>
 *   <li>多余键（不在 properties；schema 无 properties 时退化为「不在 required」）只进「疑似改名」
 *       提示，不改变判定；「未定义清单」仅 schema 有 properties 时给出。</li>
 * </ul>
 */
final class ToolArgumentsSchema {

    private ToolArgumentsSchema() {
    }

    /** 起始记录（非空白）的上限。 */
    private static final int MAX_EDIT_DISTANCE = 2;

    /** 「改名」后缀差的最大长度（newcontent→content 差 new，3 字符）。 */
    private static final int MAX_AFFIX_DIFF = 3;

    /**
     * @return 缺失必填参数时的可读错误文案（含疑似改名提示）；无需拦截返回 null
     */
    static String missingRequired(ToolCallback callback, String argumentsJson) {
        if (callback == null) {
            return null;
        }
        ToolDefinition definition = callback.getToolDefinition();
        String schema = definition == null ? null : definition.inputSchema();
        if (schema == null || schema.isBlank()) {
            return null;
        }
        JsonNode schemaNode = parseQuietly(schema);
        if (schemaNode == null || !schemaNode.isObject()) {
            return null;
        }
        List<String> required = textValues(schemaNode.get("required"));
        if (required.isEmpty()) {
            return null;
        }
        String args = argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson;
        JsonNode argsNode = parseQuietly(args);
        if (argsNode == null || !argsNode.isObject()) {
            return null;
        }
        List<String> missing = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        // 「已知键」集合:有 properties 以其为准;无 properties(function 型 schema 常见)退化为 required 本身
        JsonNode properties = schemaNode.get("properties");
        boolean propertiesPresent = properties != null && properties.isObject();
        Set<String> known = propertiesPresent ? propertyNames(properties) : new java.util.HashSet<>(required);
        for (String key : required) {
            JsonNode v = argsNode.get(key);
            if (v == null || v.isNull()) {
                missing.add(key);
            }
        }
        for (String key : propertyNames(argsNode)) {
            if (!known.contains(key)) {
                unknown.add(key);
            }
        }
        if (missing.isEmpty()) {
            return null;
        }
        return describe(definition.name(), missing, unknown, propertiesPresent);
    }

    /** 错误文案：逐个缺失键 + 疑似改名提示 + 未定义键清单（仅 schema 有 properties 时才谈「未定义」）。 */
    static String describe(String toolName, List<String> missing, List<String> unknown, boolean propertiesPresent) {
        StringBuilder sb = new StringBuilder("工具参数校验失败:");
        for (String key : missing) {
            sb.append(" 缺少必填参数 ").append(key);
            String guess = closest(key, unknown);
            if (guess != null) {
                sb.append("（是否想说 ").append(key).append("？本次收到的参数名是 ").append(guess).append("）");
            }
            sb.append(";");
        }
        sb.append(" 工具 ").append(toolName == null ? "?" : toolName)
                .append(" 的参数名必须与工具定义完全一致");
        if (propertiesPresent && !unknown.isEmpty()) {
            sb.append("（本次收到但未定义的参数名:").append(String.join("、", unknown)).append("）");
        }
        sb.append("。请修正参数名后重新调用。");
        return sb.toString();
    }

    /**
     * 候选里最像 {@code target}「改名」的一个：忽略大小写相等 / 后缀差 ≤{@value #MAX_AFFIX_DIFF}
     * （newcontent→content、contnt→...）/ 编辑距离 ≤{@value #MAX_EDIT_DISTANCE}；无近似返回 null。
     */
    static String closest(String target, List<String> candidates) {
        String t = target.toLowerCase(java.util.Locale.ROOT);
        String best = null;
        int bestScore = Integer.MAX_VALUE;
        for (String c : candidates) {
            String l = c.toLowerCase(java.util.Locale.ROOT);
            int score;
            if (l.equals(t)) {
                score = 0;
            } else if (l.endsWith(t) && l.length() - t.length() <= MAX_AFFIX_DIFF) {
                score = l.length() - t.length();
            } else if (t.endsWith(l) && t.length() - l.length() <= MAX_AFFIX_DIFF) {
                score = t.length() - l.length();
            } else {
                int d = editDistance(t, l);
                score = d <= MAX_EDIT_DISTANCE ? d + MAX_AFFIX_DIFF : Integer.MAX_VALUE;
            }
            if (score < bestScore) {
                bestScore = score;
                best = c;
            }
        }
        return best;
    }

    /** 标准编辑距离（两串都短，O(n·m) 足够）。 */
    static int editDistance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[b.length()];
    }

    private static List<String> textValues(JsonNode array) {
        List<String> out = new ArrayList<>();
        if (array != null && array.isArray()) {
            for (JsonNode n : array) {
                if (n.isTextual()) {
                    out.add(n.asText());
                }
            }
        }
        return out;
    }

    private static Set<String> propertyNames(JsonNode object) {
        Set<String> out = new java.util.LinkedHashSet<>();
        if (object != null && object.isObject()) {
            for (var e : object.properties()) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    private static JsonNode parseQuietly(String text) {
        try {
            JsonNode node = Json.parse(text);
            return node == null || node.isMissingNode() ? null : node;
        }
        catch (RuntimeException e) {
            return null;
        }
    }
}
