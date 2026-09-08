package dev.everyagent.worker.slash;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import dev.everyagent.contract.json.Json;
import tools.jackson.databind.JsonNode;

/**
 * opaque token 编码/解析(与老项目前端 {@code composerToken/composerOpaqueToken.ts}
 * 逐字节一致:4 连符号定界、base64url 去填充、payload 为 JSON)。
 *
 * <p>格式:
 * {@code [[[[agent-token::::<kind>||||label====<label>||||summary====<summary>||||payload====<b64url>]]]]}
 *
 * <p>用途:
 * <ul>
 *   <li>编码:后端 {@code slash.list} 直接把完整 opaque 串作为 {@code insertText} 下发,
 *       前端选中即插胶囊,零 token 构造逻辑;</li>
 *   <li>解析:{@link SlashTokenResolveAdvisor} 在用户消息进入模型前,按 kind 解析 payload
 *       并替换为可读文本(与老项目 {@code composerTokenRegistry.resolve} 语义一致)。</li>
 * </ul>
 */
public final class SlashTokenEncoder {

    /** opaque token 边界前缀:`[[[[`(4 个 [)。 */
    public static final String OPAQUE_PREFIX = "[[[[";
    /** opaque token 边界后缀:`]]]]`(4 个 ])。 */
    public static final String OPAQUE_SUFFIX = "]]]]";
    /** 静态标记→kind:`agent-token::::`(4 个 :)。 */
    public static final String KIND_MARKER = "agent-token::::";
    /** 顶层字段间分隔符:`||||`(4 个 |)。 */
    public static final String FIELD_SEP = "||||";
    /** 字段内键=值分隔符:`====`(4 个 =)。 */
    public static final String KEY_SEP = "====";

    /** 解析后的 token 视图(供 advisor 消费)。 */
    public record ParsedToken(String kind, String label, String summary, JsonNode payload) {
    }

    private SlashTokenEncoder() {
    }

    /**
     * 构造单个 opaque token 文本(自包含:label/summary 顶层明文,payload 末尾 base64url)。
     *
     * @param kind    token 类型,如 {@code system.skill}。
     * @param label   胶囊显示文字(必填,trim 后非空)。
     * @param summary 胶囊补充信息(可选)。
     * @param payload 结构化载荷(JSON 序列化后 base64url)。
     */
    public static String buildToken(String kind, String label, String summary, JsonNode payload) {
        String k = kind == null ? "" : kind.trim();
        String l = label == null ? "" : label.trim();
        if (k.isEmpty()) {
            throw new IllegalArgumentException("构造 opaque token 失败:kind 为空");
        }
        if (l.isEmpty()) {
            throw new IllegalArgumentException("构造 opaque token 失败:label 为空");
        }
        JsonNode p = payload == null ? Json.obj() : payload;
        StringBuilder body = new StringBuilder()
                .append(KIND_MARKER).append(k)
                .append(FIELD_SEP).append("label").append(KEY_SEP).append(l);
        if (summary != null && !summary.isEmpty()) {
            body.append(FIELD_SEP).append("summary").append(KEY_SEP).append(summary);
        }
        body.append(FIELD_SEP).append("payload").append(KEY_SEP)
                .append(toBase64Url(Json.write(p)));
        return OPAQUE_PREFIX + body + OPAQUE_SUFFIX;
    }

    /**
     * 解析单个 opaque token 文本;非本格式返回 {@code null}。
     * 与老项目 {@code parseOpaqueTokenText} 行为一致(label 缺失视为非法)。
     */
    public static ParsedToken parseToken(String value) {
        if (value == null
                || !value.startsWith(OPAQUE_PREFIX)
                || !value.endsWith(OPAQUE_SUFFIX)) {
            return null;
        }
        String body = value.substring(OPAQUE_PREFIX.length(), value.length() - OPAQUE_SUFFIX.length());
        if (!body.startsWith(KIND_MARKER)) {
            return null;
        }
        String rest = body.substring(KIND_MARKER.length());
        String[] parts = rest.split(java.util.regex.Pattern.quote(FIELD_SEP), -1);
        String kind = parts.length > 0 ? parts[0].trim() : "";
        if (kind.isEmpty()) {
            return null;
        }
        String label = "";
        String summary = null;
        JsonNode payload = Json.obj();
        for (int i = 1; i < parts.length; i++) {
            int eq = parts[i].indexOf(KEY_SEP);
            if (eq <= 0) {
                continue;
            }
            String key = parts[i].substring(0, eq);
            String val = parts[i].substring(eq + KEY_SEP.length());
            if ("label".equals(key)) {
                label = val;
            } else if ("summary".equals(key)) {
                summary = val;
            } else if ("payload".equals(key)) {
                try {
                    JsonNode decoded = Json.parse(fromBase64Url(val));
                    if (decoded.isObject()) {
                        payload = decoded;
                    }
                } catch (RuntimeException e) {
                    payload = Json.obj();
                }
            }
        }
        if (label.isEmpty()) {
            return null;
        }
        return new ParsedToken(kind, label, summary, payload);
    }

    /** base64url 编码(UTF-8,去 = 填充,URL-safe)。 */
    public static String toBase64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /** base64url 解码(补 = 填充)。 */
    public static String fromBase64Url(String value) {
        String normalized = value.replace('-', '+').replace('_', '/');
        int pad = (4 - (normalized.length() % 4)) % 4;
        StringBuilder sb = new StringBuilder(normalized);
        for (int i = 0; i < pad; i++) {
            sb.append('=');
        }
        return new String(Base64.getUrlDecoder().decode(sb.toString()), StandardCharsets.UTF_8);
    }
}
