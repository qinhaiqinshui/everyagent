package dev.everyagent.plugin.selectionReference;

import dev.everyagent.plugin.api.slash.SlashTokenResolver;
import tools.jackson.databind.JsonNode;

/**
 * 「文件资源管理器节点引用」token 的提交解析器。
 *
 * <p>kind = {@link FileReferenceToken#KIND}（{@code file.reference}）。用户提交时
 * {@code SlashTokenResolveAdvisor} 扫到本 kind 的 opaque token，调用本解析器，把 payload
 * 展开为面向模型的<b>可读引用文本</b>：节点类型 + 节点名称 + 工作区相对路径。
 *
 * <p>输出格式（稳定、可读）：
 * <pre>
 * 【文件引用】
 * 文件：&lt;name&gt;
 * 路径：&lt;path&gt;
 * </pre>
 * 或目录节点：
 * <pre>
 * 【目录引用】
 * 目录：&lt;name&gt;
 * 路径：&lt;path&gt;
 * </pre>
 *
 * <p><b>降级与健壮（硬要求）</b>：payload 为 {@code null} / null 节点 / 空对象 / 字段缺失或
 * 类型不符时一律<b>不抛错</b>——能拼几行就拼几行。具体：
 * <ul>
 *   <li>{@code name} 缺失时由 {@code path} 末段兜底；</li>
 *   <li>{@code path} 缺失或为空白 → <b>返回空串</b>（= 从模型上下文剥离该 token，
 *       与 {@link SlashTokenResolver} 契约一致）；</li>
 *   <li>{@code type} 缺失或非 {@code file}/{@code directory} → 默认按 {@code file} 处理。</li>
 * </ul>
 */
public class FileReferenceSlashResolver implements SlashTokenResolver {

    @Override
    public String kind() {
        return FileReferenceToken.KIND;
    }

    @Override
    public String resolveSubmissionText(JsonNode payload) {
        String path = text(payload, FileReferenceToken.FIELD_PATH);
        // 无路径 → 剥离该 token（不注入模型上下文）。
        if (path == null || path.isBlank()) {
            return "";
        }

        String name = text(payload, FileReferenceToken.FIELD_NAME);
        if (isBlank(name)) {
            // name 缺失时由 path 末段兜底。
            name = lastSegment(path);
        }

        String type = text(payload, FileReferenceToken.FIELD_TYPE);
        boolean isDirectory = FileReferenceToken.TYPE_DIRECTORY.equals(type);

        StringBuilder sb = new StringBuilder();
        sb.append(isDirectory ? "【目录引用】" : "【文件引用】");
        if (!isBlank(name)) {
            sb.append(isDirectory ? "\n目录：" : "\n文件：").append(name);
        }
        sb.append("\n路径：").append(path);
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
