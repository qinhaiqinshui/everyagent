package dev.everyagent.worker.tools;

import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.hub.HubPool;
import dev.everyagent.worker.modules.WorkspaceManager;
import dev.everyagent.worker.os.wsl.WslUmounter;
import dev.everyagent.worker.proto.TaskDtos.ModelSnapshot;
import dev.everyagent.worker.rpc.RpcDispatcher;
import dev.everyagent.worker.task.PendingAsks;
import dev.everyagent.worker.task.TaskEntry;
import dev.everyagent.worker.task.TaskManager;
import dev.everyagent.worker.task.TaskStore;
import dev.everyagent.worker.tools.permission.AuthorizeCheck;
import dev.everyagent.worker.tools.permission.CommandCheck;
import dev.everyagent.worker.tools.permission.ExternalRootAllowCheck;
import dev.everyagent.worker.tools.permission.GrantRegistry;
import dev.everyagent.worker.tools.permission.MissingPathCheck;
import dev.everyagent.worker.tools.permission.OverBroadRootCheck;
import dev.everyagent.worker.tools.permission.PrivilegeCheck;
import dev.everyagent.worker.tools.permission.SkillsReadAllowCheck;
import dev.everyagent.worker.tools.permission.WorkspaceAllowCheck;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link FsToolSupport} 外部授权根的 Sandbox 附加根并入单测(§7.17):该工作区
 * externalRoots 注册后,read_file/write_text 等在 gate 放行环通过、Sandbox 附加根
 * 直接放行——全程无授权弹窗;未注册的工作区外路径仍被 gate 授权决议链拒绝(沙箱
 * 附加根不含它,双闸语义不变)。
 */
class FsToolSupportExternalRootsTest {

    @TempDir
    Path tempDir;

    /** 真实 WorkspaceManager + 真实 gate 链(HubPool/RpcDispatcher/TaskManager/PendingAsks 打桩)。 */
    private record Stack(WorkspaceManager wm, PermissionGate gate, FsToolSupport fs) {
    }

    private Stack stack(Path ws, PendingAsks asks) throws Exception {
        WorkerProperties p = new WorkerProperties();
        p.setHomeDir(tempDir.resolve("home").toString());
        p.setDataDir(tempDir.resolve("data").toString());
        p.setWorkspaceRoot(ws.toString());
        p.getSandbox().getWsl().setDistro("eagent");
        ObjectProvider<TaskManager> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mock(TaskManager.class));
        WorkspaceManager wm = new WorkspaceManager(p, mock(RpcDispatcher.class), mock(HubPool.class),
                provider, new WslUmounter(p, (argv, timeoutMs) -> 0));
        java.lang.reflect.Method init = WorkspaceManager.class.getDeclaredMethod("init");
        init.setAccessible(true); // @PostConstruct init 为包私有,跨包测试经反射触发
        init.invoke(wm);
        GrantRegistry grants = new GrantRegistry(asks, p, wm, mock(TaskStore.class), null);
        PermissionGate gate = new PermissionGate(wm, grants,
                new WorkspaceAllowCheck(),
                new MissingPathCheck(),
                new SkillsReadAllowCheck(p),
                new ExternalRootAllowCheck(wm),
                new OverBroadRootCheck(),
                new AuthorizeCheck(grants),
                new CommandCheck(p, wm, grants),
                new PrivilegeCheck(grants));
        FsToolSupport fs = new FsToolSupport(wm, p, mock(HubPool.class), gate);
        return new Stack(wm, gate, fs);
    }

    /** 弹窗打桩:一旦发起授权 ask 即测试失败(外部根内不应弹窗)。 */
    private PendingAsks asksFailing() throws InterruptedException {
        PendingAsks asks = mock(PendingAsks.class);
        when(asks.ask(any(), anyString(), anyString(), any(), anyList(), anyLong()))
                .thenThrow(new AssertionError("外部授权根内文件操作不应触发授权弹窗"));
        return asks;
    }

    /** 弹窗打桩:未识别答案 → DENY(负例:未注册路径走授权决议链被拒)。 */
    private PendingAsks asksDenying() throws InterruptedException {
        PendingAsks asks = mock(PendingAsks.class);
        when(asks.ask(any(), anyString(), anyString(), any(), anyList(), anyLong()))
                .thenReturn(new PendingAsks.AskAnswer("answered", "nope"));
        return asks;
    }

    private TaskEntry task(Path ws) {
        ModelSnapshot snap = new ModelSnapshot("cfg", "openai-compat",
                "http://localhost:9999/v1", "m", null);
        return new TaskEntry("t-1", "任务", snap, "k", ws.toString(), "main-agent", 10_000);
    }

    @Test
    void externalRootFilesPassGateAndSandboxWithoutPopup(@TempDir Path ws, @TempDir Path ext)
            throws Exception {
        Stack s = stack(ws, asksFailing());
        Path extDir = Files.createDirectories(ext.resolve("data")).toRealPath();
        s.wm().addExternalRoot(ws.toString(), extDir.toString());
        TaskEntry t = task(ws);
        Path readTarget = Files.writeString(extDir.resolve("a.txt"), "EXT-DATA");

        // 读:gate 放行环 ALLOW + Sandbox 附加根放行
        assertEquals("EXT-DATA", s.fs().readText(t, "agent-1", readTarget.toRealPath().toString()));

        // 写(新建):同语义直接落盘到外部根内
        Path created = extDir.resolve("sub").resolve("new.txt");
        s.fs().writeText(t, "agent-1", created.toString(), "WRITTEN", false);
        assertEquals("WRITTEN", Files.readString(created));
        // 追加写(写前读)同样不弹窗
        s.fs().writeText(t, "agent-1", created.toString(), "+MORE", true);
        assertEquals("WRITTEN+MORE", Files.readString(created));
    }

    @Test
    void unregisteredOutsideIsDeniedByAuthorizeChain(@TempDir Path ws, @TempDir Path outside)
            throws Exception {
        Stack s = stack(ws, asksDenying());
        TaskEntry t = task(ws);
        Path f = Files.writeString(outside.resolve("plain.txt"), "x");

        // 未注册为外部根:Sandbox 附加根不含它,gate 落到授权决议链 → 拒绝(异常回灌模型)
        assertThrows(PermissionDeniedException.class,
                () -> s.fs().readText(t, "agent-1", f.toRealPath().toString()));
    }
}
