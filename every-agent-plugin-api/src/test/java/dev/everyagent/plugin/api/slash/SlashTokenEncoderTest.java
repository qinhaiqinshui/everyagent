package dev.everyagent.plugin.api.slash;

import dev.everyagent.contract.json.Json;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * opaque token 编解码回归：payload 非 ASCII（如中文文件名）时 base64 必然命中 URL-safe 的
 * 62/63 位（`-`/`_`）——解码器必须两种字母表都能吃下，否则 payload 静默变空对象。
 *
 * <p>2026-10 缺陷：{@code fromBase64Url} 把 `-`/`_` 归一回 `+`/`/` 后仍用 {@code getUrlDecoder()}
 * 解码（该解码器不认 `+`/`/`）→ 抛 {@code IllegalArgumentException} 被 {@link SlashTokenEncoder#parseToken}
 * 兜底 catch 吞成空 payload → `@` 引用中文名图片解析不出路径/扩展名，附件不注入、正文路径不去除
 * （纯 ASCII payload 的粘贴路径不含 `-`/`_`，恒好——表现为「粘贴能识别、@ 引用失效」）。
 */
class SlashTokenEncoderTest {

    /** 中文文件名的 `@` 文件引用 token：loads 必须完整解出（历史缺陷下 payload 为空对象）。 */
    @Test
    void parseTokenKeepsPayloadForNonAsciiLabelAndPath() {
        String opaque = SlashTokenEncoder.buildToken("system.workspace_file", "图.png", "/图.png",
                Json.obj()
                        .put("path", "图.png")
                        .put("fileName", "图.png")
                        .put("fullPath", "/图.png"));

        // 前置断言：该 payload 的 base64 确实命中 URL-safe 的 62/63 位（即本用例的触发条件）
        assertTrue(opaque.contains("-") || opaque.contains("_"),
                "用例前置：非 ASCII payload 的 base64 应含 -/_: " + opaque);

        SlashTokenEncoder.ParsedToken parsed = SlashTokenEncoder.parseToken(opaque);
        assertNotNull(parsed, "非 ASCII payload 的 token 必须可解析: " + opaque);
        assertEquals("system.workspace_file", parsed.kind());
        assertEquals("图.png", parsed.label());
        JsonNode payload = parsed.payload();
        assertEquals("图.png", payload.path("path").asString(), "payload 不得为空: " + payload);
        assertEquals("图.png", payload.path("fileName").asString(), "payload 不得为空: " + payload);
        assertEquals("/图.png", payload.path("fullPath").asString(), "payload 不得为空: " + payload);
    }

    /** 解码同时接受 URL-safe（`-`/`_`）与标准（`+`/`/`）两种字母表，往返逐字一致。 */
    @Test
    void decodeAcceptsUrlSafeAndStandardAlphabet() {
        String json = "{\"path\":\"图.png\",\"fileName\":\"图.png\"}";
        String urlSafe = SlashTokenEncoder.toBase64Url(json);
        assertEquals(json, SlashTokenEncoder.fromBase64Url(urlSafe), "URL-safe 字母表往返");

        String standard = Base64.getEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(json, SlashTokenEncoder.fromBase64Url(standard), "标准字母表也应可解: " + standard);
    }

    /** 纯 ASCII payload（粘贴附件名等）回归：仍须可解（不得因修复而回归）。 */
    @Test
    void parseTokenKeepsAsciiPayload() {
        String opaque = SlashTokenEncoder.buildToken("system.workspace_file", "paste-1.png",
                "/.everyagent/attachments/paste-1.png",
                Json.obj()
                        .put("path", ".everyagent/attachments/paste-1.png")
                        .put("fileName", "paste-1.png")
                        .put("fullPath", "/.everyagent/attachments/paste-1.png"));
        SlashTokenEncoder.ParsedToken parsed = SlashTokenEncoder.parseToken(opaque);
        assertNotNull(parsed, "ASCII payload token 必须可解析: " + opaque);
        assertEquals(".everyagent/attachments/paste-1.png", parsed.payload().path("path").asString());
    }

    /** token 胶囊在用户消息前面:stripTokensToLabels 应剥离外壳,保留 label 明文。 */
    @Test
    void stripTokensToLabelsReplacesTokenWithLabel() {
        String token = SlashTokenEncoder.buildToken("system.workspace_file", "📄 main.py",
                null, Json.obj().put("path", "main.py"));
        String input = token + " 帮我修改这个文件";
        String result = SlashTokenEncoder.stripTokensToLabels(input);
        assertEquals("📄 main.py 帮我修改这个文件", result);
    }

    /** 输入全是 token:stripTokensToLabels 应保留 label,不留原串。 */
    @Test
    void stripTokensToLabelsWithOnlyTokens() {
        String token = SlashTokenEncoder.buildToken("system.workspace_file", "📄 main.py",
                null, Json.obj().put("path", "main.py"));
        String result = SlashTokenEncoder.stripTokensToLabels(token);
        assertEquals("📄 main.py", result);
    }

    /** 无 token 的纯文本:stripTokensToLabels 应原样返回。 */
    @Test
    void stripTokensToLabelsWithPlainText() {
        String input = "帮我修改这个文件";
        String result = SlashTokenEncoder.stripTokensToLabels(input);
        assertEquals("帮我修改这个文件", result);
    }

    /** 多个 token:stripTokensToLabels 应逐个替换为 label。 */
    @Test
    void stripTokensToLabelsWithMultipleTokens() {
        String t1 = SlashTokenEncoder.buildToken("system.workspace_file", "📄 a.py",
                null, Json.obj().put("path", "a.py"));
        String t2 = SlashTokenEncoder.buildToken("system.workspace_file", "📄 b.py",
                null, Json.obj().put("path", "b.py"));
        String input = t1 + "和" + t2 + "都要改";
        String result = SlashTokenEncoder.stripTokensToLabels(input);
        assertEquals("📄 a.py和📄 b.py都要改", result);
    }

    /** null/空输入:stripTokensToLabels 应原样返回。 */
    @Test
    void stripTokensToLabelsWithNullOrEmpty() {
        assertEquals(null, SlashTokenEncoder.stripTokensToLabels(null));
        assertEquals("", SlashTokenEncoder.stripTokensToLabels(""));
    }

    // ── 嵌套 token(2026-11:任务标题原样存 rawContent 后,胶囊 label 可内嵌完整 token 串) ──

    /**
     * 嵌套 token 解析:外层 label 值里内嵌完整内层 token(含内层的 `||||`/`====`/base64),
     * parseToken 必须完整解出外层 kind/label/payload——顶层字段切分跳过嵌套层。
     */
    @Test
    void parseTokenWithNestedInnerTokenInLabel() {
        String inner = SlashTokenEncoder.buildToken("system.workspace_file", "图.png",
                null, Json.obj().put("path", "图.png").put("fileName", "图.png"));
        // 模拟「等待任务」胶囊:label 嵌目标任务标题(=rawContent,含内层 token)
        String outer = SlashTokenEncoder.buildToken("task.wait",
                "等待「" + inner + " 帮我分析」",
                "等待该任务完成后继续（本次输入有效）",
                Json.obj().put("targetTaskId", "7512345678901234567")
                        .put("title", inner + " 帮我分析"));

        SlashTokenEncoder.ParsedToken parsed = SlashTokenEncoder.parseToken(outer);
        assertNotNull(parsed, "嵌套 token 必须可解析: " + outer);
        assertEquals("task.wait", parsed.kind());
        assertEquals("等待「" + inner + " 帮我分析」", parsed.label(), "label 含内层原串不被切碎");
        assertEquals("7512345678901234567", parsed.payload().path("targetTaskId").asString(),
                "payload 不被内层分隔符干扰: " + parsed.payload());
        assertEquals(inner + " 帮我分析", parsed.payload().path("title").asString());

        // 内层 token 本身在嵌套体内仍可独立解析(拍平场景用)
        SlashTokenEncoder.ParsedToken innerParsed = SlashTokenEncoder.parseToken(inner);
        assertNotNull(innerParsed);
        assertEquals("图.png", innerParsed.label());
    }

    /** scanTokens 平衡配对:嵌套外层整体成段、内层不单独成段。 */
    @Test
    void scanTokensTreatsNestedTokenAsSingleSegment() {
        String inner = SlashTokenEncoder.buildToken("system.skill", "代码审查", null, Json.obj());
        String outer = SlashTokenEncoder.buildToken("task.wait", "等待「" + inner + "」", null,
                Json.obj().put("targetTaskId", "42"));
        String plain = SlashTokenEncoder.buildToken("system.workspace_file", "a.py", null,
                Json.obj().put("path", "a.py"));

        java.util.List<String> tokens = SlashTokenEncoder.scanTokens(
                "前置文本 " + outer + " 中间 " + plain + " 尾部");
        assertEquals(2, tokens.size(), "嵌套外层整体 1 段 + 普通胶囊 1 段: " + tokens);
        assertEquals(outer, tokens.get(0));
        assertEquals(plain, tokens.get(1));
    }

    /** scanTokens 残缺段容错:手打半截 `[[[[` 不阻塞其后合法 token 的识别。 */
    @Test
    void scanTokensSkipsUnbalancedPrefix() {
        String plain = SlashTokenEncoder.buildToken("system.skill", "技能", null, Json.obj());
        java.util.List<String> tokens = SlashTokenEncoder.scanTokens(
                "[[[[agent-token::::坏掉的无收尾 " + plain);
        assertEquals(1, tokens.size(), "残缺段跳过后仍应识别后面的合法胶囊");
        assertEquals(plain, tokens.get(0));
    }

    /** scanTokens 空输入/无 token 回归。 */
    @Test
    void scanTokensWithNullOrEmptyOrPlainText() {
        assertTrue(SlashTokenEncoder.scanTokens(null).isEmpty());
        assertTrue(SlashTokenEncoder.scanTokens("").isEmpty());
        assertTrue(SlashTokenEncoder.scanTokens("普通文本,无胶囊").isEmpty());
    }

    /** stripTokensToLabels 嵌套递归拍平:外层 label 里再嵌 token 时,替换文本里内层也被拍平为 label 明文。 */
    @Test
    void stripTokensToLabelsFlattensNestedTokens() {
        String inner = SlashTokenEncoder.buildToken("system.workspace_file", "图.png",
                null, Json.obj().put("path", "图.png"));
        String outer = SlashTokenEncoder.buildToken("task.wait", "等待「" + inner + " 分析」",
                null, Json.obj().put("targetTaskId", "42").put("title", inner + " 分析"));
        String result = SlashTokenEncoder.stripTokensToLabels(outer);
        assertEquals("等待「图.png 分析」", result, "嵌套拍平为内层 label 明文");
    }
}