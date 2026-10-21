package dev.everyagent.worker.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.plugin.api.spi.FileWriteListener;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.hub.HubPool;
import dev.everyagent.worker.modules.WorkspaceManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 文件工具写监听回调({@code FileWriteListener},架构 §7.15.2)契约测试:
 * <ul>
 *   <li><b>成功写才回调</b>:create_file / update_file 落盘成功后回调,携带<b>文件级全文</b>
 *       (update=写前/写后全文;create=before null + 写入全文),非工具入参片段;</li>
 *   <li><b>失败不回调</b>:update_file 未找到 oldcontent 等失败路径无回调(轮末 diff 不落假变更);</li>
 *   <li><b>监听器异常隔离</b>:监听器抛异常不影响工具结果(FsToolSupport 隔离记 warn)。</li>
 * </ul>
 */
class FileToolsWriteListenerTest {

    /** 回调记录行:tool|path|before|after。 */
    private record Call(String tool, String path, String before, String after) {
    }

    @TempDir
    Path dir;

    private FsToolSupport fs;
    private ExecContext ctx;
    private final List<Call> calls = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        Path root = dir.toRealPath();

        WorkspaceManager ws = mock(WorkspaceManager.class);
        when(ws.resolve(anyString())).thenReturn(new WorkspaceManager.Root(root, root));
        when(ws.externalRootsOf(anyString())).thenReturn(List.of());

        WorkerProperties props = mock(WorkerProperties.class);
        when(props.resolveSkillsDir()).thenReturn(dir.resolve("__no_such_skills__"));

        HubPool pool = mock(HubPool.class);
        PermissionGate gate = mock(PermissionGate.class);
        when(gate.extraRoots(anyString())).thenReturn(List.of());

        fs = new FsToolSupport(ws, props, pool, gate);
        fs.addFileWriteListener((exec, agentId, tool, path, before, after) ->
                calls.add(new Call(tool, path, before, after)));

        ctx = mock(ExecContext.class);
        when(ctx.subjectId()).thenReturn("t1");
        when(ctx.workspaceRoot()).thenReturn("ws");
    }

    private FileTools tools() {
        return new FileTools(fs, ctx, "main");
    }

    @Test
    void createFileNotifiesWithNullBefore() throws Exception {
        String out = tools().create_file("new.md", "第一行\n第二行\n");
        assertEquals("已创建并保存到：new.md", out);
        assertEquals(List.of(new Call("create_file", "new.md", null, "第一行\n第二行\n")), calls,
                "create 回调 before=null + 写入全文");
    }

    @Test
    void updateFileNotifiesWithFullSnapshots() throws Exception {
        Files.writeString(dir.resolve("f.md"), "头\n中\n尾\n", StandardCharsets.UTF_8);
        tools().update_file("f.md", "中", "中(改)");
        assertEquals(1, calls.size());
        Call c = calls.get(0);
        assertEquals("update_file", c.tool());
        assertEquals("f.md", c.path());
        assertEquals("头\n中\n尾\n", c.before(), "before 为写前文件全文(非入参 oldcontent 片段)");
        assertEquals("头\n中(改)\n尾\n", c.after(), "after 为写后文件全文(非入参 content 片段)");
    }

    @Test
    void failedUpdateDoesNotNotify() throws Exception {
        Files.writeString(dir.resolve("f.md"), "aaa\n", StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class,
                () -> tools().update_file("f.md", "不存在的旧内容", "x"));
        assertEquals(List.of(), calls, "失败写不回调(不落假变更)");
    }

    @Test
    void listenerExceptionDoesNotAffectToolResult() throws Exception {
        AtomicInteger boom = new AtomicInteger();
        fs.addFileWriteListener((exec, agentId, tool, path, before, after) -> {
            boom.incrementAndGet();
            throw new IllegalStateException("监听器炸了");
        });
        String out = tools().create_file("ok.md", "内容");
        assertEquals("已创建并保存到：ok.md", out, "监听器异常被隔离,工具照常成功");
        assertEquals("内容", Files.readString(dir.resolve("ok.md"), StandardCharsets.UTF_8));
        assertEquals(1, boom.get(), "异常监听器仍被调用(异常被 FsToolSupport 吞掉)");
        assertEquals(1, calls.size(), "正常监听器仍收到回调");
    }
}
