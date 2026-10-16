package dev.everyagent.plugin.selectionReference;

import dev.everyagent.plugin.api.slash.SlashTokenResolver;
import tools.jackson.databind.JsonNode;

/**
 * 「文件选区引用」token 的提交解析器。
 *
 * <p>kind = {@link SelectionReferenceToken#KIND}（{@code file.selection}）。用户提交时
 * {@code SlashTokenResolveAdvisor} 扫到本 kind 的 opaque token，调用本解析器，把 payload
 * 展开为面向模型的<b>可读引用文本</b>：文件名 + 工作区相对路径 + 选中行号区间 + 完整选中原文。
 *
 * <p>输出格式（稳定、可读）：
 * <pre>
 * 【文件选区引用】
 * 文件：&lt;fileName&gt;
 * 路径：&lt;relativePath&gt;
 * 行号：&lt;startLine&gt;-&lt;endLine&gt;
 * 选中内容：
 * &lt;text&gt;
 * </pre>
 *
 * <p><b>降级与健壮（硬要求）</b>：payload 为 {@code null} / null 节点 / 空对象 / 字段缺失或
 * 类型不符时一律<b>不抛错</b>——能拼几行就拼几行。具体：
 * <ul>
 *   <li>{@code fileName} 缺失时由 {@code relativePath} 末段兜底；</li>
 *   <li>行号任一缺失（或非数字）→ 整条「行号：」行省略；</li>
 *   <li>{@code text} 缺失或为空白 → <b>返回空串</b>（= 从模型上下文剥离该 token，
 *       与 {@link SlashTokenResolver} 契约一致）。</li>
 * </ul>
 */
public class SelectionSlashResolver implements SlashTokenResolver {

    @Override
    public String kind() {
        return SelectionReferenceToken.KIND;
    }

    @Override
    public String resolveSubmissionText(JsonNode payload) {
        String text = text(payload, SelectionReferenceToken.FIELD_TEXT);
        // 无正文 → 剥离该 token（不注入模型上下文）。
        if (text == null || text.isBlank()) {
            return "";
        }

        String relativePath = text(payload, SelectionReferenceToken.FIELD_RELATIVE_PATH);
        String fileName = text(payload, SelectionReferenceToken.FIELD_FILE_NAME);
        if (isBlank(fileName) && !isBlank(relativePath)) {
            // fileName 缺失时由相对路径末段兜底。
            fileName = lastSegment(relativePath);
        }

        Integer startLine = intOrNull(payload, SelectionReferenceToken.FIELD_START_LINE);
        Integer endLine = intOrNull(payload, SelectionReferenceToken.FIELD_END_LINE);

        StringBuilder sb = new StringBuilder();
        sb.append("【文件选区引用】");
        if (!isBlank(fileName)) {
            sb.append("\n文件：").append(fileName);
        }
        if (!isBlank(relativePath)) {
            sb.append("\n路径：").append(relativePath);
        }
        // 行号任一缺失则整条省略。
        if (startLine != null && endLine != null) {
            sb.append("\n行号：").append(startLine).append('-').append(endLine);
        }
        sb.append("\n选中内容：\n").append(text);
        return sb.toString();
    }

    /** 读取字符串字段；payload 非对象 / 字段缺失 / 类型不符 → {@code null}（不抛错）。 */
    private static String text(JsonNode payload, String field) {
        if (payload == null || !payload.isObject()) {
            return null;
        }
        JsonNode node = payload.get(field);
        if (node == null || node.isNull() || !node.isString()) {
            return null;
        }
        return node.asString();
    }

    /** 读取整数行号；缺失 / null / 类型不符（含无法解析的字符串）→ {@code null}（不抛错）。 */
    private static Integer intOrNull(JsonNode payload, String field) {
        if (payload == null || !payload.isObject()) {
            return null;
        }
        JsonNode node = payload.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isIntegralNumber()) {
            return node.asInt();
        }
        if (node.isString()) {
            try {
                return Integer.valueOf(node.asString().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** 取路径末段（兼容 {@code /} 与 {@code \} 分隔）。 */
    private static String lastSegment(String path) {
        String normalized = path.replace('\\', '/');
        int idx = normalized.lastIndexOf('/');
        return idx < 0 ? normalized : normalized.substring(idx + 1);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}