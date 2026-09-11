package dev.everyagent.worker.tools.permission;

import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.modules.WorkspaceManager;
import dev.everyagent.worker.modules.WorkspaceManager.Root;
import dev.everyagent.worker.proto.TaskDtos.ModelSnapshot;
import dev.everyagent.worker.task.PendingAsks;
import dev.everyagent.worker.task.TaskEntry;
import dev.everyagent.worker.task.TaskStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link GrantRegistry#execRootsSandboxed} 单测(§7.17 + §13.3 L2 纵深):外部授权根
 * 并入视图、过度宽泛根(盘根/工作区祖先)拒收、与已授权 EXEC 根去重。授权经真实
 * authorize 决议链落档(弹窗打桩答「本任务」),WorkspaceManager 打桩供给
 * resolve/externalRootsOf,TaskStore 打桩供给 grants.json 落盘目录。
 */
class GrantRegistryExecRootsTest {

    @TempDir
    Path tempDir;

    private TaskEntry task(Path ws) {
        ModelSnapshot snap = new ModelSnapshot("cfg", "openai-compat",
                "http://localhost:9999/v1", "m", null);
        return new TaskEntry("t-1", "任务", snap, "k", ws.toString(), "main-agent", 10_000);
    }

    /** 弹窗打桩:答「本任务全程允许」(TASK 档,EXEC 根随授权落档)。 */
    private PendingAsks asksTaskScope() throws InterruptedException {
        PendingAsks asks = mock(PendingAsks.class);
        when(asks.ask(any(), anyString(), anyString(), any(), anyList(), anyLong()))
                .thenReturn(new PendingAsks.AskAnswer("answered", "本任务全程允许"));
        return asks;
    }

    @Test
    void mergesExternalRootsFilteredAndDeduped(@TempDir Path ws) throws Exception {
        Path wsReal = ws.toRealPath();
        Path extRoot = Files.createDirectories(tempDir.resolve("ext").resolve("root")); // 外部授权根
        Path granted = Files.createDirectories(tempDir.resolve("grant").resolve("dir")); // 已授权 EXEC 根
        Path wsParent = wsReal.getParent(); // 工作区祖先:注册时已滤过,这里纵深再滤

        GrantRegistry grants = new GrantRegistry(asksTaskScope(), new WorkerProperties(),
                stubWorkspaceManager(wsReal,
                        List.of(extRoot.toRealPath(), wsParent, granted.toRealPath())),
                mockStore(), null);
        TaskEntry t = task(ws);

        // 用户对 granted 授权 EXEC(随附旧宽根 fsRoot 模拟历史 grants.json 载入的 C:\ 形态)
        Path fsRoot = wsReal.getRoot();
        grants.authorize(t, "main-agent", "p::exec::" + granted.toRealPath(), "读目录",
                List.of(), List.of(granted.toRealPath(), fsRoot));

        // 并入 extRoot;拒收 fsRoot(盘根)与 wsParent(工作区祖先);granted 与 externalRoots
        // 重叠出现一次(去重);EXEC 根在前、外部根在后
        assertEquals(List.of(granted.toRealPath(), extRoot.toRealPath()),
                grants.execRootsSandboxed(t));
    }

    @Test
    void externalRootsOnlyWhenNoExecGrants(@TempDir Path ws) throws Exception {
        Path wsReal = ws.toRealPath();
        Path extRoot = Files.createDirectories(tempDir.resolve("only").resolve("ext"));
        GrantRegistry grants = new GrantRegistry(asksTaskScope(), new WorkerProperties(),
                stubWorkspaceManager(wsReal, List.of(extRoot.toRealPath())), mockStore(), null);

        // 无任何 EXEC 授权:视图仍含外部授权根(命令侧按 §7.17 直接生效)
        assertEquals(List.of(extRoot.toRealPath()), grants.execRootsSandboxed(task(ws)));
    }

    @Test
    void emptyExternalRootsKeepsLegacyBehavior(@TempDir Path ws) throws Exception {
        Path wsReal = ws.toRealPath();
        Path granted = Files.createDirectories(tempDir.resolve("legacy").resolve("g"));
        GrantRegistry grants = new GrantRegistry(asksTaskScope(), new WorkerProperties(),
                stubWorkspaceManager(wsReal, List.of()), mockStore(), null);
        TaskEntry t = task(ws);

        grants.authorize(t, "main-agent", "p::exec::" + granted.toRealPath(), "读目录",
                List.of(), List.of(granted.toRealPath()));
        // 无外部根:与既有行为一致,只含过滤后的 EXEC 根
        assertEquals(List.of(granted.toRealPath()), grants.execRootsSandboxed(t));
    }

    // ---- 打桩脚手架 ----

    /** WorkspaceManager 打桩(resolve + externalRootsOf;外部根列表可含过宽根供纵深过滤验证)。 */
    private WorkspaceManager stubWorkspaceManager(Path wsReal, List<Path> externalRoots)
            throws java.io.IOException {
        WorkspaceManager wm = mock(WorkspaceManager.class);
        when(wm.resolve(anyString())).thenReturn(new Root(wsReal, wsReal));
        when(wm.externalRootsOf(anyString())).thenReturn(externalRoots);
        return wm;
    }

    /** TaskStore 打桩:grants.json 落盘到临时目录(TASK 档 persist 不报错)。 */
    private TaskStore mockStore() throws java.io.IOException {
        TaskStore store = mock(TaskStore.class);
        when(store.dirOf(anyString())).thenReturn(Files.createDirectories(tempDir.resolve("tasks")));
        return store;
    }
}
