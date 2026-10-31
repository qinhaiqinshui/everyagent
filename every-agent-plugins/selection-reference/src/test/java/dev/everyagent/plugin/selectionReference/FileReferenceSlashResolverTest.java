package dev.everyagent.plugin.selectionReference;

import dev.everyagent.contract.json.Json;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FileReferenceSlashResolver} 单测：结构输出 + 降级健壮（缺字段 / 空 payload 一律不抛错）。
 *
 * <p>§14.9 红线：只依赖 every-agent-plugin-api（及其传递的 contract / Jackson 3），不依赖 worker。
 */
class FileReferenceSlashResolverTest {

    private final FileReferenceSlashResolver resolver = new FileReferenceSlashResolver();

    @Test
    void kindIsFileReference() {
        assertEquals("file.reference", resolver.kind());
        assertEquals(FileReferenceToken.KIND, resolver.kind());
    }

    @Test
    void fileNodeRendersFileLabelNameAndPath() {
        JsonNode payload = Json.obj()
                .put(FileReferenceToken.FIELD_WORKSPACE_ROOT, "/workspaces/demo")
                .put(FileReferenceToken.FIELD_PATH, "src/Foo.java")
                .put(FileReferenceToken.FIELD_NAME, "Foo.java")
                .put(FileReferenceToken.FIELD_TYPE, "file");

        String out = resolver.resolveSubmissionText(payload);

        assertTrue(out.contains("【文件引用】"), out);
        assertTrue(out.contains("文件：Foo.java"), out);
        assertTrue(out.contains("路径：src/Foo.java"), out);
        assertFalse(out.contains("目录"), out);
    }

    @Test
    void directoryNodeRendersDirectoryLabel() {
        JsonNode payload = Json.obj()
                .put(FileReferenceToken.FIELD_WORKSPACE_ROOT, "/workspaces/demo")
                .put(FileReferenceToken.FIELD_PATH, "src/main")
                .put(FileReferenceToken.FIELD_NAME, "main")
                .put(FileReferenceToken.FIELD_TYPE, "directory");

        String out = resolver.resolveSubmissionText(payload);

        assertTrue(out.contains("【目录引用】"), out);
        assertTrue(out.contains("目录：main"), out);
        assertTrue(out.contains("路径：src/main"), out);
        assertFalse(out.contains("文件："), out);
    }

    @Test
    void missingNameFallsBackToLastPathSegment() {
        JsonNode payload = Json.obj()
                .put(FileReferenceToken.FIELD_PATH, "src/Bar.java")
                .put(FileReferenceToken.FIELD_TYPE, "file");

        String out = resolver.resolveSubmissionText(payload);

        assertTrue(out.contains("文件：Bar.java"), out);
        assertTrue(out.contains("路径：src/Bar.java"), out);
    }

    @Test
    void missingTypeDefaultsToFile() {
        JsonNode payload = Json.obj()
                .put(FileReferenceToken.FIELD_PATH, "src/Baz.java")
                .put(FileReferenceToken.FIELD_NAME, "Baz.java");

        String out = resolver.resolveSubmissionText(payload);

        assertTrue(out.contains("【文件引用】"), out);
        assertTrue(out.contains("文件：Baz.java"), out);
    }

    @Test
    void invalidTypeDefaultsToFile() {
        JsonNode payload = Json.obj()
                .put(FileReferenceToken.FIELD_PATH, "src/Qux.java")
                .put(FileReferenceToken.FIELD_NAME, "Qux.java")
                .put(FileReferenceToken.FIELD_TYPE, "unknown");

        String out = resolver.resolveSubmissionText(payload);

        assertTrue(out.contains("【文件引用】"), out);
    }

    @Test
    void blankOrMissingPathReturnsEmptyString() {
        assertEquals("", resolver.resolveSubmissionText(
                Json.obj().put(FileReferenceToken.FIELD_PATH, "")));
        assertEquals("", resolver.resolveSubmissionText(
                Json.obj().put(FileReferenceToken.FIELD_PATH, "   ")));
        // path 字段缺失
        assertEquals("", resolver.resolveSubmissionText(
                Json.obj().put(FileReferenceToken.FIELD_NAME, "Foo.java")));
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
