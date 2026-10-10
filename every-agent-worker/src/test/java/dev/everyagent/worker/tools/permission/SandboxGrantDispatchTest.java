package dev.everyagent.worker.tools.permission;

import dev.everyagent.plugin.api.interaction.InteractionService;
import dev.everyagent.plugin.api.model.ModelConfig;
import dev.everyagent.plugin.api.permission.AuthorizationChain;
import dev.everyagent.plugin.api.permission.AuthorizationHandler;
import dev.everyagent.plugin.api.permission.AuthorizationHandler.AuthorizationDecision;
import dev.everyagent.plugin.api.permission.AuthorizationHandler.AuthorizationRequest;
import dev.everyagent.plugin.api.spi.SandboxBackend;
import dev.everyagent.plugin.api.spi.SandboxBackend.Access;
import dev.everyagent.plugin.api.spi.SandboxBackend.PathGrant;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.modules.WorkspaceManager;
import dev.everyagent.worker.modules.WorkspaceManager.Root;
import dev.everyagent.worker.os.SandboxPathRegistry;
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
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 授权决议 → 沙箱下发 的端到端契约测试(§7.8 P5 + 回收)。
 *
 * <p>验证:
 * <ol>
 *   <li>授权<b>确实存在</b>的路径 → 按请求粒度下发(文件即文件,目录即目录),
 *       <b>不放大到父目录</b>;访问语义由 op 决定(读 → 只读,写 → 读写);</li>
 *   <li>授权的目标是<b>待建路径</b> → 不下发(创建需父目录写权限,放大不可接受);</li>
 *   <li>回收:run 档清空(新用户输入)/ 主体驱逐 → 沙箱侧收到 {@code revoke};
 *       多主体共享同一根时,其一撤销<b>不</b>回收。</li>
 * </ol>
 */
class SandboxGrantDispatchTest {

    @TempDir
    Path tempDir;

    /** 记录 grant/revoke 的假后端(恒等翻译,id=direct 以外以便观察)。 */
    private static final class RecordingBackend implements SandboxBackend {
        final List<List<PathGrant>> grants = new CopyOnWriteArrayList<>();
        final List<List<Path>> revokes = new CopyOnWriteArrayList<>();

        @Override
        public String id() {
            return "test-recording";
        }

        @Override
        public void grant(List<PathGrant> g) {
            grants.add(List.copyOf(g));
        }

        @Override
        public void revoke(List<Path> p) {
            revokes.add(List.copyOf(p));
        }

        List<PathGrant> allGranted() {
            List<PathGrant> out = new ArrayList<>();
            grants.forEach(out::addAll);
            return out;
        }

        List<Path> allRevoked() {
            List<Path> out = new ArrayList<>();
            revokes.forEach(out::addAll);
            return out;
        }
    }

    /** 一次装齐 gate / grants,便于断言两侧。 */
    private record Rig(PermissionGate gate, GrantRegistry grants, RecordingBackend backend,
            TaskEntry task, Path workspace) {
    }

    private Rig rig(Path ws) throws IOException {
        RecordingBackend backend = new RecordingBackend();
        SandboxPathRegistry sandbox = new SandboxPathRegistry(backend);
        WorkerProperties props = new WorkerProperties();
        WorkspaceManager wm = mock(WorkspaceManager.class);
        Path wsReal = ws.toRealPath();
        when(wm.resolve(anyString())).thenReturn(new Root(wsReal, wsReal));
        when(wm.externalRootsOf(anyString())).thenReturn(List.of());
        AuthorizationHandlerRegistry handlers = new AuthorizationHandlerRegistry();
        handlers.register(new AllowAllHandler());
        GrantRegistry grants = new GrantRegistry(
                mock(InteractionService.class), props, wm, handlers, sandbox);
        PermissionGate gate = new PermissionGate(wm, grants, new WorkspaceAllowCheck(),
                new MissingPathCheck(), new SkillsReadAllowCheck(props), new ExternalRootAllowCheck(wm),
                new OverBroadRootCheck(), new AuthorizeCheck(grants),
                new CommandCheck(props, wm, grants), new PrivilegeCheck(grants));
        return new Rig(gate, grants, backend, task(ws), ws);
    }

    @Test
    void existingFileIsGrantedAtFileGranularityNotParentDir() throws Exception {
        Rig r = rig(Files.createDirectories(tempDir.resolve("ws")));
        Path target = Files.writeString(Files.createDirectories(
                tempDir.resolve("out")).resolve("a.txt"), "x");

        r.gate().requirePath(r.task(), "main-agent", target.toString(), Op.READ);

        List<PathGrant> granted = r.backend().allGranted();
        assertEquals(1, granted.size(), "已存在文件:按单文件粒度下发一次");
        assertEquals(target.toRealPath(), granted.get(0).hostPath(), "下发的是文件本身");
        assertEquals(Access.READ_ONLY, granted.get(0).access(), "读授权 → 只读");
        assertTrue(r.backend().revokes.isEmpty(), "仅授权无回收");
    }

    @Test
    void writeGrantOnExistingFileIsReadWrite() throws Exception {
        Rig r = rig(Files.createDirectories(tempDir.resolve("ws")));
        Path target = Files.writeString(Files.createDirectories(
                tempDir.resolve("out")).resolve("b.txt"), "y");

        r.gate().requirePath(r.task(), "main-agent", target.toString(), Op.WRITE);

        List<PathGrant> granted = r.backend().allGranted();
        assertEquals(1, granted.size());
        assertEquals(target.toRealPath(), granted.get(0).hostPath());
        assertEquals(Access.READ_WRITE, granted.get(0).access(), "写授权 → 读写");
    }

    @Test
    void directoryGrantCoversThatDirectory() throws Exception {
        Rig r = rig(Files.createDirectories(tempDir.resolve("ws")));
        Path dir = Files.createDirectories(tempDir.resolve("tree").resolve("sub"));

        r.gate().requirePath(r.task(), "main-agent", dir.toString(), Op.WRITE);

        List<PathGrant> granted = r.backend().allGranted();
        assertEquals(1, granted.size(), "目录授权:下发该目录(不额外放大)");
        assertEquals(dir.toRealPath(), granted.get(0).hostPath());
    }

    @Test
    void missingTargetIsNotPushedToSandbox() throws Exception {
        Rig r = rig(Files.createDirectories(tempDir.resolve("ws")));
        Path missing = tempDir.resolve("out").resolve("created.txt");

        r.gate().requirePath(r.task(), "main-agent", missing.toString(), Op.WRITE);

        assertTrue(r.backend().allGranted().isEmpty(),
                "待建目标无法在请求粒度落地(P5):不下发,且绝不放大到父目录");
        assertTrue(r.backend().revokes.isEmpty());
    }

    @Test
    void beginRunRevokesRunScopedSandboxRoots() throws Exception {
        Rig r = rig(Files.createDirectories(tempDir.resolve("ws")));
        Path target = Files.writeString(Files.createDirectories(
                tempDir.resolve("out")).resolve("c.txt"), "z");
        r.gate().requirePath(r.task(), "main-agent", target.toString(), Op.READ);
        assertEquals(1, r.backend().allGranted().size());

        r.grants().beginRun("t-1"); // 新用户输入 → 本轮授权失效

        assertEquals(List.of(target.toRealPath()), r.backend().allRevoked(),
                "run 档失效后沙箱侧必须收到路径级回收");
    }

    @Test
    void untrackRevokesSubjectRoots() throws Exception {
        Rig r = rig(Files.createDirectories(tempDir.resolve("ws")));
        Path target = Files.writeString(Files.createDirectories(
                tempDir.resolve("out")).resolve("d.txt"), "z");
        r.gate().requirePath(r.task(), "main-agent", target.toString(), Op.READ);

        r.grants().untrack("t-1"); // 主体终态(任务收口)

        assertEquals(List.of(target.toRealPath()), r.backend().allRevoked(),
                "主体驱逐 → 沙箱授权账本整体撤销");
    }

    @Test
    void sharedRootSurvivesWhileAnotherSubjectStillWantsIt() throws Exception {
        Rig r = rig(Files.createDirectories(tempDir.resolve("ws")));
        Path target = Files.writeString(Files.createDirectories(
                tempDir.resolve("out")).resolve("e.txt"), "z");

        r.grants().authorize(req(r.task(), target.toString(), Op.READ), List.of(), List.of(),
                List.of(target.toRealPath()));
        r.grants().authorize(new AuthorizationRequest(otherTask(), "main-agent",
                PathSupport.pathKey(target.toRealPath(), Op.READ), "另一主体"), List.of(), List.of(),
                List.of(target.toRealPath()));
        assertEquals(1, r.backend().allGranted().size(), "同根跨主体去重:只下发一次");

        r.grants().untrack("t-1");
        assertTrue(r.backend().revokes.isEmpty(), "另一主体仍期望 → 不得回收");

        r.grants().untrack("t-2");
        assertEquals(List.of(target.toRealPath()), r.backend().allRevoked(), "最后一个主体撤销后才回收");
    }

    // ---- 脚手架 ----

    /**
     * 进程重启重放(§7.8 P4):任务级授权随 grants.json 回来时,沙箱侧必须<b>重放</b>
     * ——worker 是唯一真相源,沙箱侧只是缓存。这里直接构造磁盘态(含 sandboxRoots),
     * 首次过 gate 触发载入即应下发。
     */
    @Test
    void taskScopedGrantIsReplayedFromDiskOnFirstUse() throws Exception {
        Rig r = rig(Files.createDirectories(tempDir.resolve("ws")));
        Path target = Files.writeString(Files.createDirectories(
                tempDir.resolve("out")).resolve("f.txt"), "z");
        String key = PathSupport.pathKey(target.toRealPath(), Op.WRITE);

        // 模拟上一轮进程落盘的 grants.json(v2:含沙箱下发根)
        Path dir = r.task().dataDir();
        Files.createDirectories(dir);
        var root = dev.everyagent.contract.json.Json.obj().put("version", 2);
        root.putArray("taskGrants").add(key);
        root.putArray("extraRoots");
        root.putArray("execRoots");
        root.putArray("sandboxRoots").addObject()
                .put("path", target.toRealPath().toString())
                .put("rw", true);
        Files.writeString(dir.resolve("grants.json"), dev.everyagent.contract.json.Json.write(root));

        // 首次过 gate:载入磁盘授权 → 重放到沙箱
        r.grants().authorize(req(r.task(), target.toString(), Op.WRITE), List.of(), List.of(), List.of());

        assertEquals(1, r.backend().allGranted().size(), "磁盘授权载入后必须重放给沙箱");
        assertEquals(target.toRealPath(), r.backend().allGranted().get(0).hostPath());
        assertEquals(Access.READ_WRITE, r.backend().allGranted().get(0).access());
    }

    private static AuthorizationRequest req(TaskEntry t, String path, Op op) {
        return new AuthorizationRequest(t, "main-agent", PathSupport.pathKey(Path.of(path), op), "请求");
    }

    private final List<TaskEntry> otherTasks = new ArrayList<>();

    private TaskEntry otherTask() throws IOException {
        if (otherTasks.isEmpty()) {
            ModelConfig snap = new ModelConfig("cfg", "openai-compat",
                    "http://localhost:9999/v1", "m", null);
            TaskEntry t = new TaskEntry("t-2", "任务2", snap, tempDir.toString(), "defaultworkspace",
                    "main-agent", 10_000);
            t.taskDir(Files.createDirectories(tempDir.resolve("tasks2")));
            otherTasks.add(t);
        }
        return otherTasks.get(0);
    }

    private TaskEntry task(Path ws) throws IOException {
        ModelConfig snap = new ModelConfig("cfg", "openai-compat",
                "http://localhost:9999/v1", "m", null);
        TaskEntry t = new TaskEntry("t-1", "任务", snap, ws.toString(), "defaultworkspace",
                "main-agent", 10_000);
        t.taskDir(Files.createDirectories(tempDir.resolve("tasks")));
        return t;
    }

    /** 决议链打桩:一律放行(RUN 档)。 */
    private static final class AllowAllHandler implements AuthorizationHandler {
        @Override
        public String id() {
            return "test-allow-all";
        }

        @Override
        public float order() {
            return 100f;
        }

        @Override
        public AuthorizationDecision invoke(AuthorizationRequest req, AuthorizationChain next) {
            return new AuthorizationDecision(AuthorizationDecision.Type.ALLOW, "test");
        }
    }
}