package dev.everyagent.worker;

import org.junit.jupiter.api.Test;
import dev.everyagent.plugin.api.util.AtomicFiles;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AtomicFiles 单测(@TempDir,无 Spring):
 * {@code replace}(成功覆盖替换 tmp → target;失败清理残留 tmp 后抛出——Windows 上
 * AccessDeniedException 等 IOException 不降级为「静默吞掉」)与
 * {@code writeText}(同目录唯一名 tmp + 写入 + 替换,绝不残留 tmp;并发写同一目标不互相踩踏)。
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

    @Test
    void writeTextCreatesAndReplaces() throws IOException {
        Path dir = tmpDir.resolve("wt");
        Files.createDirectories(dir);
        Path target = dir.resolve("a.json");
        Files.writeString(target, "old");

        AtomicFiles.writeText(target, "新内容\n第二行\n");

        assertEquals("新内容\n第二行\n", Files.readString(target, StandardCharsets.UTF_8));
        assertEquals(List.of(), tempFiles(dir), "不应残留任何 .tmp");
    }

    @Test
    void writeTextConcurrentSameTargetKeepsTargetWholeAndNoTmp() throws Exception {
        Path dir = tmpDir.resolve("cc");
        Files.createDirectories(dir);
        Path target = dir.resolve("c.json");
        String[] contents = {"A".repeat(50_000), "B".repeat(50_000), "C".repeat(50_000)};

        int n = 6;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(n);
        try {
            for (int i = 0; i < n; i++) {
                String c = contents[i % contents.length];
                pool.submit(() -> {
                    for (int k = 0; k < 40; k++) {
                        AtomicFiles.writeText(target, c);
                    }
                    return null;
                });
            }
            pool.shutdown();
            assertTrue(pool.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        // 唯一名 tmp 的关键收益:并发写同一目标时,最终文件必须是某次完整内容,绝不半截
        String finalContent = Files.readString(target, StandardCharsets.UTF_8);
        assertTrue(List.of(contents).contains(finalContent), "最终文件必须是某次完整写入的内容");
        assertEquals(List.of(), tempFiles(dir), "并发写后不应残留任何 .tmp");
    }

    /** 列出目录内所有 .tmp 文件(相对文件名)。 */
    private static List<String> tempFiles(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".tmp")).sorted().toList();
        }
    }
}