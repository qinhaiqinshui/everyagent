package dev.everyagent.plugin.selectionReference;

import dev.everyagent.contract.json.Json;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SelectionSlashResolver} 单测：结构输出 + 降级健壮（缺字段 / 空 payload 一律不抛错）。
 *
 * <p>§14.9 红线：只依赖 every-agent-plugin-api（及其传递的 contract / Jackson 3），不依赖 worker。
 */
class SelectionSlashResolverTest {

    private final SelectionSlashResolver resolver = new SelectionSlashResolver();

    @Test
    void kindIsFileSelection() {
        assertEquals("file.selection", resolver.kind());
        assertEquals(SelectionReferenceToken.KIND, resolver.kind());
    }

    @Test
    void fullPayloadRendersFileNamePathLineRangeAndFullText() {
        String text = "line1\nline2\nline3";
        JsonNode payload = Json.obj()
                .put(SelectionReferenceToken.FIELD_WORKSPACE_ROOT, "/workspaces/demo")
                .put(SelectionReferenceToken.FIELD_FILE_PATH, "src/Foo.java")
                .put(SelectionReferenceToken.FIELD_RELATIVE_PATH, "src/Foo.java")
                .put(SelectionReferenceToken.FIELD_FILE_NAME, "Foo.java")
                .put(SelectionReferenceToken.FIELD_START_LINE, 3)
                .put(SelectionReferenceToken.FIELD_END_LINE, 5)
                .put(SelectionReferenceToken.FIELD_TEXT, text);

        String out = resolver.resolveSubmissionText(payload);

        assertTrue(out.contains("Foo.java"), out);
        assertTrue(out.contains("路径：src/Foo.java"), out);
        assertTrue(out.contains("行号：3-5"), out);
        // 完整原文（含多行）逐字保留
        assertTrue(out.contains("选中内容：\nline1\nline2\nline3"), out);
    }

    @Test
    void missingLineNumbersOmitsWholeLineRowAndDoesNotThrow() {
        JsonNode payload = Json.obj()
                .put(SelectionReferenceToken.FIELD_RELATIVE_PATH, "src/Foo.java")
                .put(SelectionReferenceToken.FIELD_TEXT, "abc");

        String out = resolver.resolveSubmissionText(payload);

        assertFalse(out.contains("行号："), out);
        // fileName 缺失 → 由 relativePath 末段兜底
        assertTrue(out.contains("文件：Foo.java"), out);
        assertTrue(out.contains("路径：src/Foo.java"), out);
    }

    @Test
    void onlyStartLineOmitsWholeLineRow() {
        JsonNode payload = Json.obj()
                .put(SelectionReferenceToken.FIELD_FILE_NAME, "Foo.java")
                .put(SelectionReferenceToken.FIELD_START_LINE, 3)
                .put(SelectionReferenceToken.FIELD_TEXT, "abc");

        String out = resolver.resolveSubmissionText(payload);

        assertFalse(out.contains("行号："), out);
    }

    @Test
    void nonNumericLineNumberTreatedAsMissing() {
        JsonNode payload = Json.obj()
                .put(SelectionReferenceToken.FIELD_FILE_NAME, "Foo.java")
                .put(SelectionReferenceToken.FIELD_START_LINE, "not-a-number")
                .put(SelectionReferenceToken.FIELD_END_LINE, 5)
                .put(SelectionReferenceToken.FIELD_TEXT, "abc");

        assertDoesNotThrow(() -> resolver.resolveSubmissionText(payload));
        assertFalse(resolver.resolveSubmissionText(payload).contains("行号："));
    }

    @Test
    void blankOrMissingTextReturnsEmptyString() {
        assertEquals("", resolver.resolveSubmissionText(
                Json.obj().put(SelectionReferenceToken.FIELD_FILE_NAME, "Foo.java")
                        .put(SelectionReferenceToken.FIELD_TEXT, "")));
        assertEquals("", resolver.resolveSubmissionText(
                Json.obj().put(SelectionReferenceToken.FIELD_FILE_NAME, "Foo.java")
                        .put(SelectionReferenceToken.FIELD_TEXT, "   ")));
        // text 字段缺失
        assertEquals("", resolver.resolveSubmissionText(
                Json.obj().put(SelectionReferenceToken.FIELD_FILE_NAME, "Foo.java")));
    }

    @Test
    void nullPayloadAndEmptyObjectDoNotThrowAndReturnEmpty() {
        assertDoesNotThrow(() -> resolver.resolveSubmissionText(null));
        assertDoesNotThrow(() -> resolver.resolveSubmissionText((JsonNode) null));
        assertEquals("", resolver.resolveSubmissionText(null));
        assertEquals("", resolver.resolveSubmissionText(Json.obj()));
    }

    @Test
    void nullNodeAndWrongTypeDoNotThrow() {
        JsonNode nullNode = Json.parse("null");
        assertDoesNotThrow(() -> resolver.resolveSubmissionText(nullNode));
        assertEquals("", resolver.resolveSubmissionText(nullNode));

        // 顶层非对象（数组）也不抛错
        JsonNode arrayNode = Json.parse("[1,2,3]");
        assertDoesNotThrow(() -> resolver.resolveSubmissionText(arrayNode));
        assertEquals("", resolver.resolveSubmissionText(arrayNode));
    }
}