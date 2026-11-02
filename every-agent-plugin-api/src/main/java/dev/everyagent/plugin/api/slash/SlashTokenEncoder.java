package dev.everyagent.plugin.api.slash;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

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
 *   <li>解析:{@code SlashTokenResolveAdvisor} 在用户消息进入模型前,按 kind 解析 payload
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
     *
     * <p><b>嵌套支持(2026-11)</b>:{@code label} 值里可能内嵌另一个完整 token 串
     * (如「等待任务」胶囊的 label 嵌目标任务标题的 rawContent)。顶层字段切分
     * ({@code ||||})改为<b>深度感知</b>——只在嵌套深度 0 处切分,内层 token 里的
     * {@code ||||}/{@code ====} 属于内层值、不参与外层切分,故嵌套 token 也能完整
     * 解析出 kind/label/summary/payload。
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
        List<String> parts = splitTopLevelFields(rest);
        String kind = parts.isEmpty() ? "" : parts.get(0).trim();
        if (kind.isEmpty()) {
            return null;
        }
        String label = "";
        String summary = null;
        JsonNode payload = Json.obj();
        for (int i = 1; i < parts.size(); i++) {
            int eq = parts.get(i).indexOf(KEY_SEP);
            if (eq <= 0) {
                continue;
            }
            String key = parts.get(i).substring(0, eq);
            String val = parts.get(i).substring(eq + KEY_SEP.length());
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

    /**
     * 深度感知的顶层字段切分:只在 {@code [[[[}/{@code ]]]]} 嵌套深度 0 处按
     * {@code ||||} 切分;内层 token(嵌在 label/summary 值里)中的分隔符不被切开。
     */
    private static List<String> splitTopLevelFields(String rest) {
        List<String> parts = new ArrayList<>();
        final int n = rest.length();
        int depth = 0;
        int segStart = 0;
        int i = 0;
        while (i < n) {
            if (rest.startsWith(OPAQUE_PREFIX, i)) {
                depth++;
                i += OPAQUE_PREFIX.length();
            } else if (rest.startsWith(OPAQUE_SUFFIX, i)) {
                depth = Math.max(0, depth - 1);
                i += OPAQUE_SUFFIX.length();
            } else if (depth == 0 && rest.startsWith(FIELD_SEP, i)) {
                parts.add(rest.substring(segStart, i));
                i += FIELD_SEP.length();
                segStart = i;
            } else {
                i++;
            }
        }
        parts.add(rest.substring(segStart));
        return parts;
    }

    /**
     * 从 {@code prefixStart}(必须指向一个 {@code [[[[})起按深度配对找本段 token 的
     * 结束位置(排他下标,含收尾 {@code }]]]})。
     *
     * <p>深度配对:遇到 {@code [[[[} 深度+1,遇到 {@code }]]]} 深度-1,深度归零处即
     * 本段(可能嵌套)的边界——嵌套 token(label 里又含完整 token 串)由此正确配对。
     * 到串尾深度仍不归零 → 返回 -1(截断/残缺段)。
     */
    public static int balancedTokenEnd(String input, int prefixStart) {
        final int n = input.length();
        int depth = 0;
        int i = prefixStart;
        while (i + 4 <= n) {
            if (input.startsWith(OPAQUE_PREFIX, i)) {
                depth++;
                i += 4;
            } else if (input.startsWith(OPAQUE_SUFFIX, i)) {
                depth--;
                i += 4;
                if (depth == 0) {
                    return i;
                }
            } else {
                i++;
            }
        }
        return -1;
    }

    /**
     * 扫描输入中全部<b>平衡</b>的 opaque token 段(按出现顺序返回原串,含边界)。
     *
     * <p>嵌套 token 整体作为一段返回(其内层 token 不单独成段——内层是外层
     * label 值的一部分);解析与否由调用方 {@link #parseToken} 决定,非本格式/
     * 非目标 kind 自行跳过。残缺 {@code [[[[}(无配对收尾)按普通文本跳过,不阻塞
     * 其后合法段的识别。
     *
     * <p><b>全仓唯一合法的段扫描口</b>:worker 与插件的 token 命中扫描一律复用
     * 本方法(禁自写 indexOf 配对/非贪婪正则——两者都会在嵌套场景提前截断外层)。
     */
    public static List<String> scanTokens(String input) {
        List<String> tokens = new ArrayList<>();
        if (input == null || input.isEmpty()) {
            return tokens;
        }
        int cursor = 0;
        while (true) {
            int start = input.indexOf(OPAQUE_PREFIX, cursor);
            if (start < 0) {
                return tokens;
            }
            int end = balancedTokenEnd(input, start);
            if (end < 0) {
                // 本 [[[[(无配对收尾:作为普通文本跳过这 4 个字符,继续找后续合法段
                cursor = start + OPAQUE_PREFIX.length();
                continue;
            }
            tokens.add(input.substring(start, end));
            cursor = end;
        }
    }

    /**
     * 从原始输入中剥离 opaque token 外壳,替换为各自的 label 明文。
     * 用于任务标题派生等场景:避免 {@code [[[[agent-token::::...]]]]} 原串出现在标题中。
     *
     * <p>遍历输入中所有<b>平衡</b>的 {@code [[[[...]]]]} 段(嵌套整体成段,见
     * {@link #scanTokens}),逐段尝试 {@link #parseToken};解析成功则用 {@code label}
     * 替换(label 里若再嵌 token,递归拍平),解析失败则丢弃该 token 段。最终 trim 返回。
     */
    public static String stripTokensToLabels(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        StringBuilder sb = new StringBuilder();
        int cursor = 0;
        while (true) {
            int start = input.indexOf(OPAQUE_PREFIX, cursor);
            if (start < 0) {
                sb.append(input, cursor, input.length());
                break;
            }
            int end = balancedTokenEnd(input, start);
            if (end < 0) {
                // 残缺段:保留为普通文本,跳过这 4 个字符继续找后续合法段
                sb.append(input, cursor, start + OPAQUE_PREFIX.length());
                cursor = start + OPAQUE_PREFIX.length();
                continue;
            }
            sb.append(input, cursor, start);
            String token = input.substring(start, end);
            ParsedToken parsed = parseToken(token);
            if (parsed != null) {
                sb.append(parsed.label().contains(OPAQUE_PREFIX)
                        ? stripTokensToLabels(parsed.label())
                        : parsed.label());
            }
            // 解析失败时不追加原串(已由 cursor/start 截断跳过),直接丢弃 token 段
            cursor = end;
        }
        return sb.toString().trim();
    }

    /** base64url 编码(UTF-8,去 = 填充,URL-safe)。 */
    public static String toBase64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * base64url 解码(补 = 填充)。
     *
     * <p><b>两种字母表都要能吃下</b>:URL-safe(`-`/`_`,与前端 {@code composerOpaqueToken.toBase64Url}
     * 及本类 {@link #toBase64Url} 产出一致)与标准(`+`/`/`,手工构造/第三方来源)。
     * 实现先把 URL-safe 字符归一到标准字符,再用<b>标准解码器</b>解码——不能用
     * {@code getUrlDecoder()}:它只认 `-`/`_`,归一后的 `+`/`/` 会被判非法。
     *
     * <p>历史缺陷(2026-10 修复):原实现「归一化为标准字符 + {@code getUrlDecoder()}」,
     * payload 的 base64 一旦含 `-`/`_`(即字母表 62/63 号字符;非 ASCII 内容必现——如
     * 中文文件名 `图.png` 的 UTF-8 字节)就抛 {@code IllegalArgumentException},被
     * {@link #parseToken} 的兜底 catch 吞成空 payload:token 解析出 kind/label 却拿不到
     * path/扩展名,`@` 引用中文名图片因此不入附件、正文里的路径也不被改写;而纯 ASCII
     * payload(如粘贴生成的 `paste-*.png`)不含 `-`/`_`,恒好——故表现为「粘贴能识别、
     * `@` 引用失效」。
     */
    public static String fromBase64Url(String value) {
        String normalized = value.replace('-', '+').replace('_', '/');
        int pad = (4 - (normalized.length() % 4)) % 4;
        StringBuilder sb = new StringBuilder(normalized);
        for (int i = 0; i < pad; i++) {
            sb.append('=');
        }
        return new String(Base64.getDecoder().decode(sb.toString()), StandardCharsets.UTF_8);
    }
}
