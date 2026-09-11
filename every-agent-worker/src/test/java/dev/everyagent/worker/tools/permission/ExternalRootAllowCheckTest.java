package dev.everyagent.worker.tools.permission;

import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.hub.HubLink;
import dev.everyagent.worker.hub.HubPool;
import dev.everyagent.worker.modules.WorkspaceManager;
import dev.everyagent.worker.os.wsl.WslUmounter;
import dev.everyagent.worker.proto.TaskDtos.ModelSnapshot;
import dev.everyagent.worker.rpc.RpcDispatcher;
import dev.everyagent.worker.task.PendingAsks;
import dev.everyagent.worker.task.TaskEntry;
import dev.everyagent.worker.task.TaskManager;
import dev.everyagent.worker.task.TaskStore;
import dev.everyagent.worker.tools.PermissionDeniedException;
import dev.everyagent.worker.tools.PermissionGate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ExternalRootAllowCheck} 单测(§7.8/§7.17 放行环):命中该工作区外部授权根内
 * 路径(读/写意图均)ALLOW、根外 SKIP、符号链接逃逸(链接在根内、realpath 落根外)不误放;
 * 并经真实 WorkspaceManager + 完整 gate 链验证装配位置——外部根内访问不弹授权 ask、
 * 未注册根照常走授权决议链。
 */
class ExternalRootAllowCheckTest {

    @TempDir
    Path tempDir;

    // ---- 节点级判定(WorkspaceManager 打桩;realPath 均为 realpath 形态,与 gate 上游一致) ----

    private PermissionContext pathCtx(TaskEntry t, PermissionGate.Op op, Path real) {
        return PermissionContext.builder()
                .kind(PermissionContext.Kind.PATH)
                .task(t)
                .op(op)
                .realPath(real)
                .build();
    }

    @Test
    void insideExternalRootAllowsReadAndWrite(@TempDir Path ws, @TempDir Path ext) throws Exception {
        Path f = Files.writeString(ext.resolve("a.txt"), "x").toRealPath();
        Path extReal = ext.toRealPath();
        WorkspaceManager wm = mock(WorkspaceManager.class);
        when(wm.externalRootsOf(anyString())).thenReturn(List.of(extReal));
        ExternalRootAllowCheck check = new ExternalRootAllowCheck(wm);
        TaskEntry t = task(ws);

        assertTrue(check.check(pathCtx(t, PermissionGate.Op.READ, f)).isAllow(), "根内读 ALLOW");
        assertTrue(check.check(pathCtx(t, PermissionGate.Op.WRITE, f)).isAllow(), "根内写 ALLOW(完全读写)");
        assertTrue(check.check(pathCtx(t, PermissionGate.Op.READ, extReal)).isAllow(), "根自身 ALLOW");
        assertTrue(check.check(pathCtx(t, PermissionGate.Op.WRITE, extReal.resolve("new.txt"))).isAllow(),
                "根内不存在目标同样 ALLOW(write 目标由沙箱 resolveTarget 落地)");
    }

    @Test
    void outsideExternalRootSkips(@TempDir Path ws, @TempDir Path ext, @TempDir Path outside)
            throws Exception {
        Path f = Files.writeString(outside.resolve("b.txt"), "x").toRealPath();
        WorkspaceManager wm = mock(WorkspaceManager.class);
        when(wm.externalRootsOf(anyString())).thenReturn(List.of(ext.toRealPath()));
        ExternalRootAllowCheck check = new ExternalRootAllowCheck(wm);

        assertTrue(check.check(pathCtx(task(ws), PermissionGate.Op.READ, f)).isSkip(), "根外 SKIP");
        // 兄弟目录(仅前缀字符串相似)不得误放:startsWith 是路径元素级判定
        Path sibling = ext.toRealPath().resolveSibling(ext.getFileName() + "-x");
        Files.createDirectories(sibling);
        assertTrue(check.check(pathCtx(task(ws), PermissionGate.Op.READ, sibling)).isSkip(),
                "根的兄弟目录 SKIP");
    }

    @Test
    void symlinkEscapeThroughRootIsNotAllowed(@TempDir Path ws, @TempDir Path ext,
            @TempDir Path outside) throws Exception {
        // 链接本体放在外部授权根内,但 realpath 落在根外:上游 gate 以 realpath 判定,
        // 本节点消费的 realPath 即逃逸后的真实路径 → 不放行(SKIP,交后续授权决议)
        Path secret = Files.writeString(outside.resolve("secret.txt"), "x");
        Path link = ext.resolve("leak");
        Files.createSymbolicLink(link, secret);
        Path real = link.toRealPath();
        assertTrue(!real.startsWith(ext.toRealPath()), "前置:realpath 确已逃逸到根外");

        WorkspaceManager wm = mock(WorkspaceManager.class);
        when(wm.externalRootsOf(anyString())).thenReturn(List.of(ext.toRealPath()));
        ExternalRootAllowCheck check = new ExternalRootAllowCheck(wm);

        assertTrue(check.check(pathCtx(task(ws), PermissionGate.Op.READ, real)).isSkip(),
                "符号链接逃逸不误放");
    }

    @Test
    void noRootsOrNullTaskSkips(@TempDir Path ws, @TempDir Path outside) throws Exception {
        Path f = Files.writeString(outside.resolve("c.txt"), "x").toRealPath();
        WorkspaceManager wm = mock(WorkspaceManager.class);
        when(wm.externalRootsOf(anyString())).thenReturn(List.of()); // 未注册/无根:空列表
        ExternalRootAllowCheck check = new ExternalRootAllowCheck(wm);

        assertTrue(check.check(pathCtx(task(ws), PermissionGate.Op.READ, f)).isSkip(), "无外部根 SKIP");
        assertTrue(check.check(pathCtx(null, PermissionGate.Op.READ, f)).isSkip(), "无 task 上下文 SKIP");
        assertTrue(check.check(pathCtx(task(ws), PermissionGate.Op.READ, null)).isSkip(), "无 realPath SKIP");
    }

    // ---- 完整 gate 链装配:外部根内不弹窗、未注册根照常走授权决议链 ----

    /** 真实 WorkspaceManager(临时目录;HubPool/RpcDispatcher/TaskManager 打桩,零 wsl 调用)。 */
    private WorkspaceManager realManager(Path ws) throws Exception {
        WorkerProperties p = new WorkerProperties();
        p.setHomeDir(tempDir.resolve("home").toString());
        p.setDataDir(tempDir.resolve("data").toString());
        p.setWorkspaceRoot(ws.toString());
        p.getSandbox().getWsl().setDistro("eagent");
        ObjectProvider<TaskManager> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mock(TaskManager.class));
        WorkspaceManager wm = new WorkspaceManager(p, mock(RpcDispatcher.class), mock(HubPool.class),
                provider, new WslUmounter(p, (argv, timeoutMs) -> 0));
        initViaReflection(wm);
        return wm;
    }

    /** @PostConstruct init 为包私有,测试跨包经反射触发(同 WorkspaceExternalRootsTest 惯例)。 */
    private static void initViaReflection(WorkspaceManager wm) throws Exception {
        java.lang.reflect.Method m = WorkspaceManager.class.getDeclaredMethod("init");
        m.setAccessible(true);
        try {
            m.invoke(wm);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof Exception ex) {
                throw ex;
            }
            throw e;
        }
    }

    /** PendingAsks 打桩:ask 一旦被调用即视为测试失败(外部根内不应弹授权窗)。 */
    private PendingAsks asksFailing() throws InterruptedException {
        PendingAsks asks = mock(PendingAsks.class);
        when(asks.ask(any(), anyString(), anyString(), any(), anyList(), anyLong()))
                .thenThrow(new AssertionError("外部授权根内访问不应触发授权弹窗"));
        return asks;
    }

    /** PendingAsks 打桩:回传未识别答案 → parseScope DENY(模拟用户拒绝,负例用)。 */
    private PendingAsks asksDenying() throws InterruptedException {
        PendingAsks asks = mock(PendingAsks.class);
        when(asks.ask(any(), anyString(), anyString(), any(), anyList(), anyLong()))
                .thenReturn(new PendingAsks.AskAnswer("answered", "nope"));
        return asks;
    }

    private PermissionGate gate(WorkspaceManager wm, PendingAsks asks) {
        GrantRegistry grants = new GrantRegistry(asks, new WorkerProperties(), wm,
                mock(TaskStore.class), null);
        return new PermissionGate(wm, grants,
                new WorkspaceAllowCheck(),
                new MissingPathCheck(),
                new SkillsReadAllowCheck(new WorkerProperties()),
                new ExternalRootAllowCheck(wm),
                new OverBroadRootCheck(),
                new AuthorizeCheck(grants),
                new CommandCheck(new WorkerProperties(), wm, grants),
                new PrivilegeCheck(grants));
    }

    private TaskEntry task(Path ws) {
        ModelSnapshot snap = new ModelSnapshot("cfg", "openai-compat",
                "http://localhost:9999/v1", "m", null);
        return new TaskEntry("t-1", "任务", snap, "k", ws.toString(), "main-agent", 10_000);
    }

    @Test
    void registeredExternalRootAllowsWithoutPopup(@TempDir Path ws, @TempDir Path ext)
            throws Exception {
        WorkspaceManager wm = realManager(ws);
        Path extDir = Files.createDirectories(ext.resolve("data"));
        wm.addExternalRoot(ws.toString(), extDir.toString());
        Path f = Files.writeString(extDir.resolve("a.txt"), "OUTSIDE-BUT-GRANTED");
        PermissionGate gate = gate(wm, asksFailing());

        // 注册即授权:读/写均直接放行,全链无一处发起 ask
        gate.requirePath(task(ws), "agent-1", f.toRealPath().toString(), PermissionGate.Op.READ);
        gate.requirePath(task(ws), "agent-1",
                extDir.toRealPath().resolve("new.txt").toString(), PermissionGate.Op.WRITE);
    }

    @Test
    void unregisteredOutsideStillGoesToAuthorizeChain(@TempDir Path ws, @TempDir Path outside)
            throws Exception {
        WorkspaceManager wm = realManager(ws);
        Path f = Files.writeString(outside.resolve("plain.txt"), "x");
        PermissionGate gate = gate(wm, asksDenying());
        TaskEntry t = task(ws);

        // 未注册为外部根的工作区外路径:放行环 SKIP,落到 AuthorizeCheck → 用户拒绝 → 异常回灌
        try {
            gate.requirePath(t, "agent-1", f.toRealPath().toString(), PermissionGate.Op.READ);
            throw new AssertionError("未授权外部路径应当被拒绝");
        } catch (PermissionDeniedException expected) {
            assertEquals("拒绝", expected.getMessage());
        }
        // 同一工作区注册外部根后,同路径(另一目录)即放行:证明放行环数据源是 externalRoots
        Path extDir = Files.createDirectories(outside.resolve("granted"));
        wm.addExternalRoot(ws.toString(), extDir.toString());
        gate.requirePath(t, "agent-1", extDir.toRealPath().toString(), PermissionGate.Op.READ);
    }
}
