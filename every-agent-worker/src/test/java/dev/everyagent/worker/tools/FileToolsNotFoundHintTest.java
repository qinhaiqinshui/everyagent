package dev.everyagent.worker.tools;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.hub.HubPool;
import dev.everyagent.worker.modules.WorkspaceManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 「未找到 oldcontent」诊断信息({@code FileTools.describeNotFound})回归测试:
 * 报错须附最相近行号/片段,便于长文档定位。
 */
class FileToolsNotFoundHintTest {

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
        when(props.resolveSkillsDir()).thenReturn(dir.resolve("__no_such_skills__"));
        HubPool pool = mock(HubPool.class);
        PermissionGate gate = mock(PermissionGate.class);
        when(gate.extraRoots(anyString())).thenReturn(List.of());
        fs = new FsToolSupport(ws, props, pool, gate);
        ctx = mock(ExecContext.class);
        when(ctx.subjectId()).thenReturn("t1");
        when(ctx.workspaceRoot()).thenReturn("ws");
    }

    @Test
    void reportsStartLineAndFirstDivergingLine() {
        String hint = FileTools.describeNotFound("l1\nl2\nTARGET\nl4 changed\nl5\n", "TARGET\nl4 original");
        assertTrue(hint.contains("第 3 行"), hint);
        assertTrue(hint.contains("第 4 行"), hint);
        assertTrue(hint.contains("l4 changed"), hint);
    }

    @Test
    void reportsPrematureEnd() {
        String hint = FileTools.describeNotFound("a\nTARGET", "TARGET\nmore\nlines");
        assertTrue(hint.contains("提前结束"), hint);
    }

    @Test
    void reportsMultipleFirstLineHits() {
        String hint = FileTools.describeNotFound("H\nx\nH\ny\n", "H\nzzz");
        assertTrue(hint.contains("多处出现"), hint);
        assertTrue(hint.contains("第 1、3 行"), hint);
    }

    @Test
    void reportsWhitespaceOrIndentMismatch() {
        String hint = FileTools.describeNotFound("aa\n\tTARGET   x\nbb\n", "TARGET x");
        assertTrue(hint.contains("空白") || hint.contains("缩进"), hint);
    }

    @Test
    void reportsNearestLineWhenAbsent() {
        String hint = FileTools.describeNotFound("alpha\nbetamax\ngamma\n", "betamaxX");
        assertTrue(hint.contains("最相近") || hint.contains("无相近内容"), hint);
    }

    @Test
    void updateFileErrorMessageCarriesHint() throws Exception {
        // 第 2 行与 oldcontent 首行一致,但第 3 行不同 → 整体不匹配(注意 oldcontent 不能是文件内容的子串,
        // 否则会匹配成功:如文件含「线二(已改)」时,「线一\n线二」是其前缀子串,仍会命中)
        Files.writeString(dir.resolve("f.md"), "a\n线一\n线三\nd\n", StandardCharsets.UTF_8);
        FileTools tools = new FileTools(fs, ctx, "main");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> tools.update_file("f.md", "线一\n线二", "X"));
        assertTrue(ex.getMessage().contains("未找到旧内容"), ex.getMessage());
        assertTrue(ex.getMessage().contains("第 2 行"), ex.getMessage());
        assertTrue(ex.getMessage().contains("线三"), ex.getMessage());
    }
}