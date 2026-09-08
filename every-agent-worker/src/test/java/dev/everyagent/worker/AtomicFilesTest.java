package dev.everyagent.worker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * AtomicFiles.replace 单测(@TempDir,无 Spring):
 * 成功覆盖替换(tmp → target)、失败清理残留 tmp 后抛出(Windows 上 AccessDeniedException
 * 等 IOException 不降级为「静默吞掉」,但必须不残留 tmp 垃圾)。
 */
class AtomicFilesTest {

    @TempDir
    Path tmpDir;

    @Test
    void replaceOverwritesTargetAndConsumesTmp() throws IOException {
        Path dir = tmpDir.resolve("ok");
        Files.createDirectories(dir);
        Path tmp = dir.resolve("f.tmp");
        Path target = dir.resolve("f.json");
        Files.writeString(tmp, "new");
        Files.writeString(target, "old");

        AtomicFiles.replace(tmp, target);

        assertEquals("new", Files.readString(target), "目标内容应被替换");
        assertFalse(Files.exists(tmp), "成功后 tmp 已被 move 走");
    }

    @Test
    void replaceFailsAndCleansTmp() throws IOException {
        Path dir = tmpDir.resolve("fail");
        Files.createDirectories(dir);
        Path tmp = dir.resolve("rounds.jsonl.tmp");
        Path target = dir.resolve("rounds.jsonl");
        Files.writeString(tmp, "content");
        // target 是已存在目录 → Files.move 必然失败(IOException),模拟 Windows 短暂锁/占用
        Files.createDirectories(target);

        assertThrows(IOException.class, () -> AtomicFiles.replace(tmp, target));
        assertFalse(Files.exists(tmp), "失败后残留 tmp 应被清理,不堆积垃圾");
    }
}