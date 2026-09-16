package dev.everyagent.worker.tools;

import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.hub.HubPool;
import dev.everyagent.worker.modules.WorkspaceManager;
import dev.everyagent.worker.os.OsSandbox;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link FsToolSupport} WSL 路径翻译单测:验证 wsl-direct / wsl-bwrap 后端下,
 * AI 使用的 Linux 路径正确翻译为 Windows 路径(或工作区相对路径 / UNC 路径)。
 *
 * <p>由于 {@code resolveWslPath} 为私有方法,通过 {@link #readText} / {@link #exists}
 * 等公共 API 间接验证翻译结果。
 */
class FsToolSupportWslPathTest {

    @TempDir
    Path tempDir;

    private record Stack(WorkspaceManager wm, PermissionGate gate, FsToolSupport fs, OsSandbox sandbox) {
    }

    /** 构建真实 WorkspaceManager + 打桩 OsSandbox(isWslBackend/isWslDirect 可控)。 */
    private Stack stack(Path ws, boolean wslDirect) throws Exception {
        WorkerProperties p = new WorkerProperties();
        p.setHomeDir(tempDir.resolve("data").toString());
        p.setWorkspaceRoot(ws.toString());
        p.getSandbox().getWsl().setDistro("EveryAgent");
        ObjectProvider<TaskManager> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mock(TaskManager.class));
        WorkspaceManager wm = new WorkspaceManager(p, mock(RpcDispatcher.class), mock(HubPool.class),
                provider, new WslUmounter(p, (argv, timeoutMs) -> 0));
        java.lang.reflect.Method init = WorkspaceManager.class.getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(wm);
        // 打桩 OsSandbox:isWslBackend=true, isWslDirect 按参数
        OsSandbox sandbox = mock(OsSandbox.class);
        when(sandbox.isWslBackend()).thenReturn(true);
        when(sandbox.isWslDirect()).thenReturn(wslDirect);
        when(sandbox.isWslBwrap()).thenReturn(!wslDirect);
        PendingAsks asks = mock(PendingAsks.class);
        when(asks.ask(any(), anyString(), anyString(), any(), anyList(), anyLong()))
                .thenReturn(new PendingAsks.AskAnswer("answered", "nope"));
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
        FsToolSupport fs = new FsToolSupport(wm, p, mock(HubPool.class), gate, sandbox);
        return new Stack(wm, gate, fs, sandbox);
    }

    private TaskEntry task(Path ws) {
        ModelSnapshot snap = new ModelSnapshot("cfg", "openai-compat",
                "http://localhost:9999/v1", "m", null);
        return new TaskEntry("t-wsl", "WSL测试", snap, "k", ws.toString(), "defaultworkspace", "main-agent", 10_000);
    }

    // ---- 相对路径:WSL 后端与非 WSL 后端行为一致 ----

    @Test
    void relativePathWorksInWslDirect(@TempDir Path ws) throws Exception {
        Stack s = stack(ws, true);
        TaskEntry t = task(ws);
        Files.writeString(ws.resolve("hello.txt"), "REL");
        // 相对路径在 WSL 后端下原样传递
        assertEquals("REL", s.fs().readText(t, "agent-1", "hello.txt"));
        assertTrue(s.fs().exists(t, "hello.txt"));
        assertFalse(s.fs().exists(t, "noexist.txt"));
    }

    @Test
    void relativePathWorksInWslBwrap(@TempDir Path ws) throws Exception {
        Stack s = stack(ws, false);
        TaskEntry t = task(ws);
        Files.writeString(ws.resolve("hello.txt"), "REL");
        assertEquals("REL", s.fs().readText(t, "agent-1", "hello.txt"));
    }

    // ---- wsl-bwrap:/workspace/ 前缀 → 相对路径 ----
    // 注:wsl-bwrap 的 /workspace 是固定挂载点,不依赖工作区根的 Windows 路径形态,
    // 经 WslPathMapper.toWindowsToken 的 /workspace/ 分支翻译为工作区根 + 后缀,
    // 在 Linux 测试环境同样可验证。

    @Test
    void wslBwrapWorkspaceMountStripsToRelative(@TempDir Path ws) throws Exception {
        Stack s = stack(ws, false);
        TaskEntry t = task(ws);
        Files.writeString(ws.resolve("bwrap.txt"), "BWRAP");
        // /workspace/bwrap.txt → bwrap.txt
        assertEquals("BWRAP", s.fs().readText(t, "agent-1", "/workspace/bwrap.txt"));
        assertTrue(s.fs().exists(t, "/workspace/bwrap.txt"));
    }

    @Test
    void wslBwrapCreateFileWithWorkspaceMount(@TempDir Path ws) throws Exception {
        Stack s = stack(ws, false);
        TaskEntry t = task(ws);
        s.fs().writeText(t, "agent-1", "/workspace/created.txt", "NEW", false);
        assertEquals("NEW", Files.readString(ws.resolve("created.txt")));
    }

    @Test
    void wslBwrapWorkspaceMountRoot(@TempDir Path ws) throws Exception {
        Stack s = stack(ws, false);
        TaskEntry t = task(ws);
        // /workspace → "." → 工作区根目录
        assertTrue(s.fs().exists(t, "/workspace"));
    }

    @Test
    void wslBwrapNestedPath(@TempDir Path ws) throws Exception {
        Stack s = stack(ws, false);
        TaskEntry t = task(ws);
        Files.createDirectories(ws.resolve("lib/util"));
        Files.writeString(ws.resolve("lib/util/Helper.java"), "deep");
        assertEquals("deep", s.fs().readText(t, "agent-1", "/workspace/lib/util/Helper.java"));
        assertTrue(s.fs().exists(t, "/workspace/lib/util/Helper.java"));
    }

    // ---- wsl-direct:相对路径 passthrough ----
    // 注:wsl-direct 的挂载点(/c/Users/.../eagent)依赖工作区根的 Windows 路径形态,
    // 在 Linux 测试环境无法生成有效挂载点;相对路径测试覆盖翻译核心逻辑。

    @Test
    void wslDirectRelativePathPassthrough(@TempDir Path ws) throws Exception {
        Stack s = stack(ws, true);
        TaskEntry t = task(ws);
        Files.writeString(ws.resolve("direct.txt"), "DIRECT");
        assertEquals("DIRECT", s.fs().readText(t, "agent-1", "direct.txt"));
        assertTrue(s.fs().exists(t, "direct.txt"));
    }

    @Test
    void wslDirectCreateFileRelative(@TempDir Path ws) throws Exception {
        Stack s = stack(ws, true);
        TaskEntry t = task(ws);
        s.fs().writeText(t, "agent-1", "newdirect.txt", "CREATED", false);
        assertEquals("CREATED", Files.readString(ws.resolve("newdirect.txt")));
    }

    // ---- 非 WSL 后端:osSandbox=null → 零行为变化 ----

    @Test
    void nonWslBackendPassthrough(@TempDir Path ws) throws Exception {
        // 使用 4 参数构造器(osSandbox=null)
        WorkerProperties p = new WorkerProperties();
        p.setHomeDir(tempDir.resolve("data2").toString());
        p.setWorkspaceRoot(ws.toString());
        ObjectProvider<TaskManager> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mock(TaskManager.class));
        WorkspaceManager wm = new WorkspaceManager(p, mock(RpcDispatcher.class), mock(HubPool.class),
                provider, new WslUmounter(p, (argv, timeoutMs) -> 0));
        java.lang.reflect.Method init = WorkspaceManager.class.getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(wm);
        PendingAsks asks = mock(PendingAsks.class);
        when(asks.ask(any(), anyString(), anyString(), any(), anyList(), anyLong()))
                .thenReturn(new PendingAsks.AskAnswer("answered", "nope"));
        GrantRegistry grants = new GrantRegistry(asks, p, wm, mock(TaskStore.class), null);
        PermissionGate gate = new PermissionGate(wm, grants,
                new WorkspaceAllowCheck(), new MissingPathCheck(),
                new SkillsReadAllowCheck(p), new ExternalRootAllowCheck(wm),
                new OverBroadRootCheck(), new AuthorizeCheck(grants),
                new CommandCheck(p, wm, grants), new PrivilegeCheck(grants));
        FsToolSupport fs = new FsToolSupport(wm, p, mock(HubPool.class), gate); // 4-arg, osSandbox=null

        TaskEntry t = task(ws);
        Files.writeString(ws.resolve("plain.txt"), "PASS");
        // 相对路径正常工作
        assertEquals("PASS", fs.readText(t, "agent-1", "plain.txt"));
        assertTrue(fs.exists(t, "plain.txt"));
    }

    // ---- WSL 后端下不可映射的 Linux 绝对路径 → UNC,exists 返回 false ----

    @Test
    void wslDirectUnmappablePathReturnsFalseExists(@TempDir Path ws) throws Exception {
        Stack s = stack(ws, true);
        TaskEntry t = task(ws);
        // /tmp/ 不在任何已知挂载根下 → UNC 路径,测试环境不可达
        assertFalse(s.fs().exists(t, "/tmp/nonexistent_file.txt"));
    }

    @Test
    void wslBwrapUnmappablePathReturnsFalseExists(@TempDir Path ws) throws Exception {
        Stack s = stack(ws, false);
        TaskEntry t = task(ws);
        // /root/ 不匹配 /workspace 或 /mnt/ → UNC 路径,测试环境不可达
        assertFalse(s.fs().exists(t, "/root/nonexistent_file.txt"));
    }
}
