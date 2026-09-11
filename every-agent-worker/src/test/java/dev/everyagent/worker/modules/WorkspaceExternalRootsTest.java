package dev.everyagent.worker.modules;

import dev.everyagent.contract.json.Json;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.hub.HubLink;
import dev.everyagent.worker.hub.HubPool;
import dev.everyagent.worker.os.wsl.WslUmounter;
import dev.everyagent.worker.rpc.BadParamsException;
import dev.everyagent.worker.rpc.RpcContext;
import dev.everyagent.worker.rpc.RpcDispatcher;
import dev.everyagent.worker.task.TaskManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工作区外部授权根数据层:注册规则(目录→自身/文件→父目录/幂等/包含吸收双向/过宽拒收/
 * 不存在抛错)、持久化 roundtrip(旧格式无字段兼容)、删除级联 umount 的引用判定
 * (共享根与被后代引用的祖先根不卸载、独有根卸载、umount 失败不阻塞删除)。
 * wsl.exe 调用经 {@link WslUmounter.CommandRunner} 注入伪造,零真实进程。
 */
class WorkspaceExternalRootsTest {

    @TempDir
    Path tempDir;

    /** 记录 argv 的伪造 wsl.exe runner:rc 可编程,默认 0;boom 非空时抛异常。 */
    private static final class RecordingRunner implements WslUmounter.CommandRunner {
        final List<List<String>> calls = new ArrayList<>();
        int rc = 0;
        RuntimeException boom;

        @Override
        public int run(List<String> argv, long timeoutMs) {
            if (boom != null) {
                throw boom;
            }
            calls.add(List.copyOf(argv));
            return rc;
        }

        /** 已发起卸载的挂载点(argv 末位)。 */
        List<String> mounts() {
            return calls.stream().map(c -> c.get(c.size() - 1)).toList();
        }
    }

    private WorkerProperties props(Path home, Path data, Path defaultWs) {
        WorkerProperties p = new WorkerProperties();
        p.setHomeDir(home.toString());
        p.setDataDir(data.toString());
        p.setWorkspaceRoot(defaultWs.toString());
        p.getSandbox().getWsl().setDistro("eagent"); // 测试钉住 -d eagent 命令形态
        return p;
    }

    private RpcContext ctx(String method, ObjectNode params) {
        HubLink link = mock(HubLink.class);
        when(link.k()).thenReturn("k");
        when(link.workerId()).thenReturn("w");
        return new RpcContext(link, "req-" + System.nanoTime(), method, params);
    }

    /** 真实 WorkspaceManager + 临时目录;HubPool/RpcDispatcher/TaskManager 打桩,umount 可记录。 */
    private WorkspaceManager newManager(WorkerProperties p, RecordingRunner runner, TaskManager tm) {
        ObjectProvider<TaskManager> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(tm);
        return new WorkspaceManager(p, mock(RpcDispatcher.class), mock(HubPool.class), provider,
                new WslUmounter(p, runner == null ? (argv, timeoutMs) -> 0 : runner));
    }

    /** 反射调私有 RPC(workspaces.remove / workspaces.addExternalRoot),异常解包。 */
    private void invokeRpc(WorkspaceManager wm, String name, ObjectNode params) throws Exception {
        Method m = WorkspaceManager.class.getDeclaredMethod(name, RpcContext.class);
        m.setAccessible(true);
        try {
            m.invoke(wm, ctx(name, params));
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw e;
        }
    }

    /** 规范键(realpath;宿主临时目录含 symlink 时与 normalize 不同,统一按 realpath 断言)。 */
    private String norm(Path p) {
        try {
            return p.toAbsolutePath().normalize().toRealPath().toString();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- 注册规则 ----

    @Test
    void registerDirectoryUsesItselfAndFileUsesParent() throws Exception {
        Path dataDir = tempDir.resolve("data");
        Path ws = tempDir.resolve("ws");
        Files.createDirectories(ws);
        WorkerProperties p = props(tempDir.resolve("home"), dataDir, ws);
        WorkspaceManager wm = newManager(p, null, mock(TaskManager.class));
        wm.init();

        Path dir = tempDir.resolve("ext").resolve("dir1");
        Files.createDirectories(dir);
        WorkspaceManager.ExternalRootsUpdate u1 = wm.addExternalRoot(ws.toString(), dir.toString());
        assertEquals("registered", u1.action());
        assertEquals(List.of(norm(dir)), u1.roots().stream().map(Path::toString).toList());
        assertEquals(List.of(norm(dir)),
                wm.externalRootsOf(ws.toString()).stream().map(Path::toString).toList());

        // 文件 → 父目录作为授权根(与 dir1 不相交,不触发吸收)。
        Path file = tempDir.resolve("other").resolve("f.txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "x");
        WorkspaceManager.ExternalRootsUpdate u2 = wm.addExternalRoot(ws.toString(), file.toString());
        assertEquals("registered", u2.action());
        assertEquals(List.of(norm(dir), norm(file.getParent())),
                u2.roots().stream().map(Path::toString).toList());

        // 未注册工作区 / 不存在的外部路径 / 相对路径。
        assertThrows(BadParamsException.class,
                () -> wm.addExternalRoot(tempDir.resolve("nowhere").toString(), dir.toString()));
        assertThrows(BadParamsException.class,
                () -> wm.addExternalRoot(ws.toString(), tempDir.resolve("nope").toString()));
        assertThrows(BadParamsException.class, () -> wm.addExternalRoot(ws.toString(), "relative/x"));
    }

    @Test
    void sameOrNestedRootIsIdempotentSkip() throws Exception {
        Path dataDir = tempDir.resolve("data");
        Path ws = tempDir.resolve("ws");
        Files.createDirectories(ws);
        WorkspaceManager wm = newManager(props(tempDir.resolve("home"), dataDir, ws), null,
                mock(TaskManager.class));
        wm.init();

        Path a = tempDir.resolve("ext").resolve("a");
        Files.createDirectories(a);
        wm.addExternalRoot(ws.toString(), a.toString());
        // 同一根重复注册:幂等跳过,无副作用。
        assertEquals("skipped", wm.addExternalRoot(ws.toString(), a.toString()).action());
        // 已有根的后代:被包含,跳过。
        Path sub = a.resolve("sub");
        Files.createDirectories(sub);
        assertEquals("skipped", wm.addExternalRoot(ws.toString(), sub.toString()).action());
        assertEquals(List.of(norm(a)),
                wm.externalRootsOf(ws.toString()).stream().map(Path::toString).toList());
    }

    @Test
    void widerRootAbsorbsNestedExisting() throws Exception {
        Path dataDir = tempDir.resolve("data");
        Path ws = tempDir.resolve("ws");
        Files.createDirectories(ws);
        WorkspaceManager wm = newManager(props(tempDir.resolve("home"), dataDir, ws), null,
                mock(TaskManager.class));
        wm.init();

        Path deep = tempDir.resolve("abs").resolve("x").resolve("y");
        Files.createDirectories(deep);
        wm.addExternalRoot(ws.toString(), deep.toString());
        Path wide = tempDir.resolve("abs").resolve("x");
        WorkspaceManager.ExternalRootsUpdate u = wm.addExternalRoot(ws.toString(), wide.toString());
        // 宽根吸收被包含的旧根:注册表只剩宽根。
        assertEquals("absorbed", u.action());
        assertEquals(List.of(norm(wide)), u.roots().stream().map(Path::toString).toList());
        assertEquals(List.of(norm(wide)),
                wm.externalRootsOf(ws.toString()).stream().map(Path::toString).toList());
    }

    @Test
    void overBroadRootsRejected() throws Exception {
        Path dataDir = tempDir.resolve("data");
        Path ws = tempDir.resolve("ws");
        Files.createDirectories(ws);
        WorkspaceManager wm = newManager(props(tempDir.resolve("home"), dataDir, ws), null,
                mock(TaskManager.class));
        wm.init();

        // 工作区自身 / 工作区祖先 / 文件系统根一律拒收(OverBroadRootCheck 单点判定)。
        assertThrows(BadParamsException.class, () -> wm.addExternalRoot(ws.toString(), ws.toString()));
        assertThrows(BadParamsException.class,
                () -> wm.addExternalRoot(ws.toString(), tempDir.toString()));
        assertThrows(BadParamsException.class,
                () -> wm.addExternalRoot(ws.toString(), ws.getRoot().toString()));
        assertTrue(wm.externalRootsOf(ws.toString()).isEmpty());
    }

    // ---- 持久化 ----

    @Test
    void persistenceRoundtripAndOldFormatCompat() throws Exception {
        Path dataDir = tempDir.resolve("data");
        Path wsA = tempDir.resolve("wsA");
        Path old = tempDir.resolve("old-ws");
        Files.createDirectories(wsA);
        Files.createDirectories(old);
        // 旧格式:无 externalRoots 字段,读入为空列表且不报错。
        Files.createDirectories(dataDir);
        ArrayNode arr = Json.arr();
        arr.add(Json.obj().put("root", norm(old)).put("addedTs", 1000L));
        Files.writeString(dataDir.resolve("workspaces.json"), Json.write(arr));

        WorkerProperties p = props(tempDir.resolve("home"), dataDir, wsA);
        WorkspaceManager wm1 = newManager(p, null, mock(TaskManager.class));
        wm1.init();
        assertTrue(wm1.externalRootsOf(old.toString()).isEmpty());

        Path ext = tempDir.resolve("ext").resolve("keep");
        Files.createDirectories(ext);
        wm1.addExternalRoot(old.toString(), ext.toString());
        assertTrue(Files.readString(dataDir.resolve("workspaces.json")).contains("externalRoots"));

        // 重启(新实例同一 data 目录):已注册根按 realpath 原样恢复。
        WorkspaceManager wm2 = newManager(p, null, mock(TaskManager.class));
        wm2.init();
        assertEquals(List.of(norm(ext)),
                wm2.externalRootsOf(old.toString()).stream().map(Path::toString).toList());
    }

    // ---- 删除级联 umount ----

    /** 预置注册表:三个在册工作区,B/C 各挂外部授权根(Windows 原生形态,跨平台字符串判定)。 */
    private WorkspaceManager seeded(RecordingRunner runner, TaskManager tm, Path wsB, Path wsC)
            throws Exception {
        Path dataDir = tempDir.resolve("data");
        Path def = tempDir.resolve("def-ws");
        Files.createDirectories(dataDir);
        Files.createDirectories(def);
        Files.createDirectories(wsB);
        Files.createDirectories(wsC);
        ArrayNode arr = Json.arr();
        arr.add(Json.obj().put("root", norm(def)).put("addedTs", 1000L));
        arr.add(Json.obj().put("root", norm(wsB)).put("addedTs", 2000L)
                .set("externalRoots", Json.arr()
                        .add("C:\\ext\\shared").add("C:\\ext\\bOnly").add("C:\\ext\\nest\\inner")));
        arr.add(Json.obj().put("root", norm(wsC)).put("addedTs", 3000L)
                .set("externalRoots", Json.arr()
                        .add("C:\\ext\\shared").add("C:\\ext\\cOnly").add("C:\\ext\\nest")));
        Files.writeString(dataDir.resolve("workspaces.json"), Json.write(arr));
        WorkspaceManager wm = newManager(props(tempDir.resolve("home"), dataDir, def), runner, tm);
        wm.init();
        return wm;
    }

    @Test
    void removeUmountsExclusiveRootsOnly() throws Exception {
        Path wsB = tempDir.resolve("wsB");
        Path wsC = tempDir.resolve("wsC");
        RecordingRunner runner = new RecordingRunner();
        TaskManager tm = mock(TaskManager.class);
        WorkspaceManager wm = seeded(runner, tm, wsB, wsC);

        invokeRpc(wm, "rpcRemove", Json.obj().put("root", norm(wsC)));

        // 独有根 cOnly 卸载;shared 与 B 共享不卸;nest 之下有 B 的 nest\inner 引用不卸。
        assertEquals(List.of("/c/ext/cOnly"), runner.mounts());
        assertEquals(List.of("wsl.exe", "-d", "eagent", "-u", "root", "-e", "umount", "/c/ext/cOnly"),
                runner.calls.get(0));
        // 删除流程不受 umount 步骤影响:注册表与任务级联照常。
        assertTrue(wm.list().stream().noneMatch(r -> r.root().equals(norm(wsC))));
        verify(tm).deleteByWorkspace(norm(wsC));
        assertFalse(Files.readString(tempDir.resolve("data").resolve("workspaces.json"))
                .contains("cOnly"));
    }

    @Test
    void umountFailureOrExceptionNeverBlocksRemoval() throws Exception {
        Path wsB = tempDir.resolve("wsB");
        Path wsC = tempDir.resolve("wsC");
        RecordingRunner failing = new RecordingRunner();
        failing.rc = 1; // umount 与 lazy 重试全失败
        TaskManager tm = mock(TaskManager.class);
        WorkspaceManager wm = seeded(failing, tm, wsB, wsC);

        invokeRpc(wm, "rpcRemove", Json.obj().put("root", norm(wsC)));
        // cOnly:普通 umount 失败 → lazy umount 重试 → 仍失败仅 WARN;shared/nest 不发起。
        assertEquals(2, failing.calls.size());
        assertTrue(failing.calls.get(1).contains("umount") && failing.calls.get(1).contains("-l"));
        assertTrue(wm.list().stream().noneMatch(r -> r.root().equals(norm(wsC))));
        verify(tm).deleteByWorkspace(norm(wsC));

        // runner 抛异常同样不外泄(删除流程绝不阻塞)。
        RecordingRunner boom = new RecordingRunner();
        boom.boom = new RuntimeException("wsl.exe 起不来");
        WorkspaceManager wm2 = seeded(boom, mock(TaskManager.class), wsB, wsC);
        invokeRpc(wm2, "rpcRemove", Json.obj().put("root", norm(wsC)));
        assertTrue(wm2.list().stream().noneMatch(r -> r.root().equals(norm(wsC))));
    }

    @Test
    void rpcAddExternalRootReturnsActionAndRoots() throws Exception {
        Path dataDir = tempDir.resolve("data");
        Path ws = tempDir.resolve("ws");
        Files.createDirectories(ws);
        WorkspaceManager wm = newManager(props(tempDir.resolve("home"), dataDir, ws), null,
                mock(TaskManager.class));
        wm.init();
        Path ext = tempDir.resolve("ext").resolve("r");
        Files.createDirectories(ext);

        invokeRpc(wm, "rpcAddExternalRoot",
                Json.obj().put("workspace", ws.toString()).put("path", ext.toString()));
        invokeRpc(wm, "rpcAddExternalRoot",
                Json.obj().put("workspace", ws.toString()).put("path", ext.toString()));
        assertEquals(List.of(norm(ext)),
                wm.externalRootsOf(ws.toString()).stream().map(Path::toString).toList());
    }

    // ---- 全部工作区汇总(wsl-direct 挂载列表数据源,§7.17) ----

    @Test
    void allExternalRootsUnionsAcrossWorkspacesDeduped() throws Exception {
        Path dataDir = tempDir.resolve("data");
        Path def = tempDir.resolve("def-ws");
        Path wsB = tempDir.resolve("wsB");
        Path wsC = tempDir.resolve("wsC");
        Files.createDirectories(dataDir);
        Files.createDirectories(def);
        Files.createDirectories(wsB);
        Files.createDirectories(wsC);
        // 按注册序(addedTs)seed:B 先注册(shared + bOnly),C 后注册(shared + cOnly)
        ArrayNode arr = Json.arr();
        arr.add(Json.obj().put("root", norm(def)).put("addedTs", 1000L));
        arr.add(Json.obj().put("root", norm(wsB)).put("addedTs", 2000L)
                .set("externalRoots", Json.arr().add("C:\\ext\\shared").add("C:\\ext\\bOnly")));
        arr.add(Json.obj().put("root", norm(wsC)).put("addedTs", 3000L)
                .set("externalRoots", Json.arr().add("C:\\ext\\shared").add("C:\\ext\\cOnly")));
        Files.writeString(dataDir.resolve("workspaces.json"), Json.write(arr));
        WorkspaceManager wm = newManager(props(tempDir.resolve("home"), dataDir, def), null,
                mock(TaskManager.class));
        wm.init();

        // 跨工作区去重(shared 出现一次),按注册序串联;无根工作区(def)不贡献条目
        assertEquals(List.of("C:\\ext\\shared", "C:\\ext\\bOnly", "C:\\ext\\cOnly"),
                wm.allExternalRoots().stream().map(Path::toString).toList());
    }

    @Test
    void allExternalRootsEmptyWhenNoneRegistered() throws Exception {
        Path dataDir = tempDir.resolve("data");
        Path ws = tempDir.resolve("ws");
        Files.createDirectories(ws);
        WorkspaceManager wm = newManager(props(tempDir.resolve("home"), dataDir, ws), null,
                mock(TaskManager.class));
        wm.init();
        assertTrue(wm.allExternalRoots().isEmpty());
    }
}
