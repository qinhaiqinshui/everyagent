package dev.everyagent.worker.tools.permission;

import dev.everyagent.plugin.api.model.ModelConfig;
import dev.everyagent.plugin.api.permission.AuthorizationChain;
import dev.everyagent.plugin.api.permission.AuthorizationHandler;
import dev.everyagent.plugin.api.permission.AuthorizationHandler.AuthorizationDecision;
import dev.everyagent.plugin.api.permission.AuthorizationHandler.AuthorizationRequest;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.modules.WorkspaceManager;
import dev.everyagent.worker.modules.WorkspaceManager.Root;
import dev.everyagent.worker.plugin.registry.AuthorizationHandlerRegistry;
import dev.everyagent.worker.task.TaskEntry;
import dev.everyagent.worker.tools.PermissionGate;
import dev.everyagent.worker.tools.PermissionGate.Op;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 授权单元颗粒度单测(§7.8 O1/O2):文件工具链的授权单元 = <b>目标路径本身</b>,
 * 已存在文件不再提升到父目录——一次「新建/覆写单个文件」的授权不得静默放大成该目录下
 * 任意文件的写权限;弹窗文案与实际判定同源(不得比实际宽)。
 *
 * <p>端到端路径:真实 {@link PermissionGate}(完整责任链)+ 真实 {@link GrantRegistry}
 * (授权决议链落档 RUN),链上挂一枚计数 handler 观察「是否真的又授权了一次」。
 */
class PathGrantUnitTest {

    @TempDir
    Path tempDir;

    // ---- 授权单元推导(授权单元 vs 命令链授权根) ----

    @Test
    void existingFileUnitIsFileItselfNotParentDir(@TempDir Path ws, @TempDir Path out) throws IOException {
        Path a = Files.writeString(out.resolve("a.txt"), "x");
        Path b = Files.writeString(out.resolve("b.txt"), "y");
        Path outReal = out.toRealPath();

        Path unitA = PathSupport.grantUnitOf(a, a);
        assertEquals(a.toRealPath(), unitA, "已存在文件的授权单元 = 该文件本身");
        assertNotEquals(outReal, unitA, "不得提升到父目录(旧行为)");

        // 同目录兄弟文件 = 另一个授权单元 → 另一把 key(越权放大消除)
        Path unitB = PathSupport.grantUnitOf(b, b);
        assertNotEquals(unitA, unitB, "同目录平级文件必须各自成单元");
        assertNotEquals(PathSupport.pathKey(unitA, Op.WRITE), PathSupport.pathKey(unitB, Op.WRITE));

        // 命令 EXEC 链仍是「已存在文件提升到父目录」(归属不同,语义不变)
        assertEquals(outReal, PathSupport.grantRootOf(a), "命令链授权根仍提升到父目录");
    }

    @Test
    void missingTargetUnitIsTheTargetPathNotItsDirectory(@TempDir Path ws, @TempDir Path out)
            throws IOException {
        Path missing = out.resolve("new.txt");
        Path unit = PathSupport.grantUnitOf(missing, PathSupport.deepestExisting(missing));
        assertEquals(out.toRealPath().resolve("new.txt"), unit, "待建目标 = 父目录 realpath + 文件名");
        assertNotEquals(out.toRealPath(), unit, "不得坍缩成整个目录(旧行为:一次新建 ≈ 整目录可写)");

        // 待建的多级目标:已存在前缀取 realpath,缺失段按词法拼接
        Path nested = out.resolve("sub").resolve("deep.txt");
        assertEquals(out.toRealPath().resolve("sub").resolve("deep.txt"),
                PathSupport.grantUnitOf(nested, PathSupport.deepestExisting(nested)));
    }

    @Test
    void directoryTargetUnitIsDirectoryItself(@TempDir Path ws, @TempDir Path out) throws IOException {
        Path dir = Files.createDirectories(out.resolve("d"));
        assertEquals(dir.toRealPath(), PathSupport.grantUnitOf(dir, dir), "目录目标 = 目录本身");
    }

    @Test
    void scopeNoteMatchesUnitSemantics(@TempDir Path ws, @TempDir Path out) throws IOException {
        Path file = Files.writeString(out.resolve("f.txt"), "x");
        Path dir = Files.createDirectories(out.resolve("d"));
        Path missing = out.resolve("m.txt");

        assertTrue(PathSupport.scopeNote(file, file).contains("仅此文件"));
        assertTrue(PathSupport.scopeNote(file, file).contains("不含其所在目录内的其它文件"));
        assertTrue(PathSupport.scopeNote(dir, dir).contains("仅该目录本身"));
        assertTrue(PathSupport.scopeNote(dir, dir).contains("不含其子目录内的文件"));
        assertTrue(PathSupport.scopeNote(missing, out).contains("仅此待建路径"));
        for (String note : List.of(PathSupport.scopeNote(file, file),
                PathSupport.scopeNote(dir, dir), PathSupport.scopeNote(missing, out))) {
            assertFalse(note.contains("及其子目录"), "不得再声明未实现的子树范围: " + note);
        }
    }

    // ---- 端到端:经完整责任链的判定与文案 ----

    @Test
    void siblingFilesEachRequireOwnGrantAndSameFileDoesNot(@TempDir Path ws, @TempDir Path out)
            throws Exception {
        Path a = Files.writeString(out.resolve("a.txt"), "x");
        Path b = Files.writeString(out.resolve("b.txt"), "y");
        CountingHandler counter = new CountingHandler();
        PermissionGate gate = gate(ws, counter);
        TaskEntry t = task(ws);

        gate.requirePath(t, "main-agent", a.toString(), Op.WRITE);
        assertEquals(1, counter.count, "首个文件:一次授权");
        assertEquals("p::write::" + a.toRealPath(), counter.keys.get(0),
                "key 用目标文件本身(非父目录)");

        gate.requirePath(t, "main-agent", a.toString(), Op.WRITE);
        assertEquals(1, counter.count, "同一路径重复访问:复用授权,不再询问");

        gate.requirePath(t, "main-agent", b.toString(), Op.WRITE);
        assertEquals(2, counter.count, "同目录兄弟文件:必须再授权一次(旧行为会静默放行)");
        assertEquals("p::write::" + b.toRealPath(), counter.keys.get(1));

        // 读与写是独立单元:读同一文件仍需单独授权
        gate.requirePath(t, "main-agent", a.toString(), Op.READ);
        assertEquals(3, counter.count, "读/写为独立授权单元");
    }

    @Test
    void missingFilePromptAndKeyUseTargetPathNotDirectory(@TempDir Path ws, @TempDir Path out)
            throws Exception {
        Path missing = out.resolve("created.txt");
        CountingHandler counter = new CountingHandler();
        PermissionGate gate = gate(ws, counter);
        TaskEntry t = task(ws);

        gate.requirePath(t, "main-agent", missing.toString(), Op.WRITE);
        assertEquals("p::write::" + out.toRealPath().resolve("created.txt"), counter.keys.get(0),
                "待建目标按目标路径授权");
        assertTrue(counter.prompts.get(0).contains("仅此待建路径"), counter.prompts.get(0));
        assertFalse(counter.prompts.get(0).contains("及其子目录"), counter.prompts.get(0));

        // 该目录下的另一个待建文件:另一次授权(此前同 key 会静默放行)
        gate.requirePath(t, "main-agent", out.resolve("other.txt").toString(), Op.WRITE);
        assertEquals(2, counter.count, "同目录另一待建文件需再授权");
    }

    @Test
    void directoryTargetPromptStatesDirectoryItself(@TempDir Path ws, @TempDir Path out)
            throws Exception {
        Path dir = Files.createDirectories(out.resolve("d"));
        CountingHandler counter = new CountingHandler();
        PermissionGate gate = gate(ws, counter);

        gate.requirePath(task(ws), "main-agent", dir.toString(), Op.READ);
        assertEquals("p::read::" + dir.toRealPath(), counter.keys.get(0));
        assertTrue(counter.prompts.get(0).contains("仅该目录本身"), counter.prompts.get(0));
    }

    // ---- 脚手架 ----

    /** 计数 handler:记录每次决议请求的 grantKey/prompt(ALLOW → RUN 档,不落盘)。 */
    private static final class CountingHandler implements AuthorizationHandler {
        int count;
        final List<String> keys = new ArrayList<>();
        final List<String> prompts = new ArrayList<>();

        @Override
        public String id() {
            return "test-counting";
        }

        @Override
        public float order() {
            return 100f;
        }

        @Override
        public AuthorizationDecision invoke(AuthorizationRequest req, AuthorizationChain next) {
            count++;
            keys.add(req.grantKey());
            prompts.add(req.prompt());
            return new AuthorizationDecision(AuthorizationDecision.Type.ALLOW, "test");
        }
    }

    /** 完整责任链装配(节点均为生产实现;WorkspaceManager 打桩供给工作区解析)。 */
    private PermissionGate gate(Path ws, CountingHandler counter) throws IOException {
        WorkerProperties props = new WorkerProperties();
        WorkspaceManager wm = mock(WorkspaceManager.class);
        Path wsReal = ws.toRealPath();
        when(wm.resolve(anyString())).thenReturn(new Root(wsReal, wsReal));
        when(wm.externalRootsOf(anyString())).thenReturn(List.of());

        AuthorizationHandlerRegistry registry = new AuthorizationHandlerRegistry();
        registry.register(counter);
        GrantRegistry grants = new GrantRegistry(mock(dev.everyagent.plugin.api.interaction.InteractionService.class),
                props, wm, registry, null);
        return new PermissionGate(wm, grants, new WorkspaceAllowCheck(), new MissingPathCheck(),
                new SkillsReadAllowCheck(props), new ExternalRootAllowCheck(wm),
                new OverBroadRootCheck(), new AuthorizeCheck(grants),
                new CommandCheck(props, wm, grants), new PrivilegeCheck(grants));
    }

    private TaskEntry task(Path ws) throws IOException {
        ModelConfig snap = new ModelConfig("cfg", "openai-compat", "http://localhost:9999/v1", "m", null);
        TaskEntry t = new TaskEntry("t-1", "任务", snap, ws.toString(), "defaultworkspace",
                "main-agent", 10_000);
        t.taskDir(Files.createDirectories(tempDir.resolve("tasks")));
        return t;
    }
}