package dev.everyagent.plugin.imagevision;

import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.plugin.api.spi.FileReadResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ImageReadHandler 单测：enabled=false 放弃接管（回落核心默认文本读取）；
 * 未超限原图直用（dataUrl 前缀 / fileName 尾段 / mimeType 按扩展名推断）；
 * 超限且内容无法解码 → fail-fast 抛异常；mimeType 口径以扩展名而非文件内容为准。
 */
class ImageReadHandlerTest {

    /** 标准 67 字节 1x1 PNG。 */
    private static final byte[] PNG_1X1 = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    @TempDir
    Path dir;

    // read 实现不触碰 ExecContext 任何字段（路径授权解析在核心完成后传入），传 null 即可。
    private static final ExecContext CTX = null;

    @Test
    void disabledReturnsNull() throws Exception {
        Path file = dir.resolve("a.png");
        Files.write(file, PNG_1X1);
        ImageReadHandler handler =
                new ImageReadHandler(new ImageVisionSettings(false, 10 * 1024 * 1024L, Set.of(".png")));

        assertNull(handler.read(CTX, "a.png", file), "enabled=false 放弃接管，回落核心默认文本读取");
    }

    @Test
    void smallPngInjectedAsAttachment() throws Exception {
        Path file = dir.resolve("sub").resolve("b.png");
        Files.createDirectories(file.getParent());
        Files.write(file, PNG_1X1);
        ImageReadHandler handler =
                new ImageReadHandler(new ImageVisionSettings(true, 10 * 1024 * 1024L, Set.of(".png")));

        FileReadResult result = handler.read(CTX, "sub/b.png", file);

        assertTrue(result.text().contains("已读取图片"), "text 前缀告知已读取");
        assertEquals(1, result.attachments().size(), "附件恰好 1 项");
        Map<String, Object> att = result.attachments().get(0);
        assertEquals("image", att.get("type"));
        assertTrue(((String) att.get("dataUrl")).startsWith("data:image/png;base64,"),
                "dataUrl 为 png base64 data URL");
        assertEquals("b.png", att.get("fileName"), "fileName 取 path 尾段");
        assertEquals("image/png", att.get("mimeType"));
    }

    @Test
    void oversizeGarbageFailsFast() throws Exception {
        Path file = dir.resolve("fake.png");
        byte[] garbage = new byte[2048];
        new Random(42).nextBytes(garbage);
        Files.write(file, garbage);
        // 上限压到 100 bytes(base64) → 原图必超限 → 走压缩 → 内容非图片无法解码 → fail-fast
        ImageReadHandler handler =
                new ImageReadHandler(new ImageVisionSettings(true, 100, Set.of(".png")));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> handler.read(CTX, "fake.png", file), "非图片内容且超限应抛异常回灌 AI");
        assertTrue(ex.getMessage().contains("fake.png"), "异常文案带 path");
    }

    @Test
    void mimeTypeInferredByExtension() throws Exception {
        Path file = dir.resolve("c.jpg");
        Files.write(file, PNG_1X1); // 内容是 PNG，扩展名 .jpg → 按扩展名口径推断
        ImageReadHandler handler =
                new ImageReadHandler(new ImageVisionSettings(true, 10 * 1024 * 1024L, Set.of(".jpg")));

        FileReadResult result = handler.read(CTX, "c.jpg", file);

        assertEquals("image/jpeg", result.attachments().get(0).get("mimeType"),
                "mimeType 按扩展名推断（不嗅探内容）");
        assertTrue(((String) result.attachments().get(0).get("dataUrl")).startsWith("data:image/jpeg;base64,"),
                "dataUrl 的 mimeType 同口径");
    }
}
