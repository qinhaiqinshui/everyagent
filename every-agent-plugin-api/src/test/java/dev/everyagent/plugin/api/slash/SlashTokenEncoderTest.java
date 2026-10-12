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
}