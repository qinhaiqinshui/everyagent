package dev.everyagent.worker.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.hub.HubPool;
import dev.everyagent.worker.modules.WorkspaceManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 文件写入的「原子性 / 不丢更新」回归测试(对应真实故障:长中文 Markdown 经多次
 * update_file 后被截断——写侧为 truncate 后原地重写,写入中断或并发写入者交错即留下
 * 截断文件,且调用方仍视作成功)。
 *
 * <ul>
 *   <li>{@code atomicWriteNeverExposesPartialContent}:写期间持续读取,任何时刻读到的
 *       都必须是「某次完整写入的内容」,绝不出现半截文件;</li>
 *   <li>{@code writeLeavesNoTempFile}:原子替换后不残留 {@code *.tmp};</li>
 *   <li>{@code concurrentUpdateFileDoesNotLoseUpdates}:并发的 read-modify-write
 *       必须整体串行,所有更新都要落到最终文件里(锁的护栏)。</li>
 * </ul>
 */
class FsWriteAtomicityTest {

    @TempDir
    Path dir;

    private FsToolSupport fs;
    private ExecContext ctx;

    @BeforeEach
    void setUp() throws Exception {
        Path root = dir.toRealPath();

        WorkspaceManager ws = mock(WorkspaceManager.class);
        when(ws.resolve(anyString())).thenReturn(new WorkspaceManager.Root(root, root));
        when(ws.externalRootsOf(anyString())).thenReturn(List.of());

        WorkerProperties props = mock(WorkerProperties.class);
        // 指向不存在的技能目录 → SkillsReadonlyRoots 返回空根(不阻断)
        when(props.resolveSkillsDir()).thenReturn(dir.resolve("__no_such_skills__"));

        HubPool pool = mock(HubPool.class);
        PermissionGate gate = mock(PermissionGate.class);
        when(gate.extraRoots(anyString())).thenReturn(List.of());

        fs = new FsToolSupport(ws, props, pool, gate);

        ctx = mock(ExecContext.class);
        when(ctx.subjectId()).thenReturn("t1");
        when(ctx.workspaceRoot()).thenReturn("ws");
    }

    /** 写入期间并发读取:任意时刻都不得读到「半截文件」。 */
    @Test
    void atomicWriteNeverExposesPartialContent() throws Exception {
        int writers = 6;
        String[] full = new String[writers];
        for (int i = 0; i < writers; i++) {
            // 头尾各带唯一标记,中间大块内容——半截写会暴露「有头无尾」
            full[i] = "HEAD_" + i + "\n" + ("内容行" + i + "\n").repeat(2000) + "TAIL_" + i + "\n";
        }

        AtomicReference<String> bad = new AtomicReference<>();
        AtomicBoolean stop = new AtomicBoolean(false);
        CountDownLatch start = new CountDownLatch(1);

        Thread reader = new Thread(() -> {
            while (!stop.get()) {
                try {
                    String seen = Files.readString(dir.resolve("f.txt"), StandardCharsets.UTF_8);
                    boolean ok = false;
                    for (String c : full) {
                        if (c.equals(seen)) {
                            ok = true;
                            break;
                        }
                    }
                    if (!ok) {
                        bad.compareAndSet(null, "读到非完整内容: " + seen.length() + " 字符,尾部="
                                + seen.substring(Math.max(0, seen.length() - 20)));
                        return;
                    }
                } catch (IOException notYetWritten) {
                    // 首次写入前文件不存在:合法
                }
            }
        });
        reader.start();

        ExecutorService ex = Executors.newFixedThreadPool(writers);
        try {
            for (int i = 0; i < writers; i++) {
                final String content = full[i];
                ex.submit(() -> {
                    start.await();
                    for (int k = 0; k < 60; k++) {
                        fs.writeText(ctx, "main", "f.txt", content, false);
                    }
                    return null;
                });
            }
            start.countDown();
            ex.shutdown();
            assertTrue(ex.awaitTermination(60, TimeUnit.SECONDS), "写入线程未按时结束");
        } finally {
            stop.set(true);
            reader.join(5000);
        }

        assertEquals(null, bad.get(), "原子写被破坏:观察到半截文件");
        String finalContent = Files.readString(dir.resolve("f.txt"), StandardCharsets.UTF_8);
        boolean isFull = false;
        for (String c : full) {
            isFull |= c.equals(finalContent);
        }
        assertTrue(isFull, "最终文件必须是某次完整写入的内容");
    }

    @Test
    void writeLeavesNoTempFile() throws Exception {
        fs.writeText(ctx, "main", "a.txt", "行一\n行二\n", false);
        assertEquals("行一\n行二\n", Files.readString(dir.resolve("a.txt"), StandardCharsets.UTF_8));
        try (Stream<Path> s = Files.list(dir)) {
            List<String> leftovers = s.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".tmp"))
                    .toList();
            assertEquals(List.of(), leftovers, "原子替换后不应残留 .tmp 文件");
        }
    }

    /** 并发 update_file:read-modify-write 整体串行,所有更新都必须保留。 */
    @Test
    void concurrentUpdateFileDoesNotLoseUpdates() throws Exception {
        Files.writeString(dir.resolve("f.txt"), "ROOT\n", StandardCharsets.UTF_8);
        FileTools tools = new FileTools(fs, ctx, "main");

        int n = 8;
        ExecutorService ex = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        try {
            for (int i = 0; i < n; i++) {
                final int id = i;
                ex.submit(() -> {
                    start.await();
                    // 每次都在唯一锚点 ROOT\n 之后追加自己的一行:读-改-写若不串行,会互相覆盖
                    tools.update_file("f.txt", "ROOT\n", "ROOT\nline_" + id + "\n");
                    return null;
                });
            }
            start.countDown();
            ex.shutdown();
            assertTrue(ex.awaitTermination(30, TimeUnit.SECONDS), "更新线程未按时结束");
        } finally {
            ex.shutdownNow();
        }

        String result = Files.readString(dir.resolve("f.txt"), StandardCharsets.UTF_8);
        assertNotNull(result);
        for (int i = 0; i < n; i++) {
            assertTrue(result.contains("line_" + i), "并发更新丢失: line_" + i + " 不在最终文件中\n" + result);
        }
    }
}