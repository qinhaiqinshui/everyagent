package dev.everyagent.worker.tools.permission;

import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.plugin.api.interaction.AskResult;
import dev.everyagent.plugin.api.interaction.InteractionService;
import dev.everyagent.plugin.api.model.ModelConfig;
import dev.everyagent.plugin.api.permission.AuthorizationHandler.AuthorizationRequest;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.modules.WorkspaceManager;
import dev.everyagent.worker.modules.WorkspaceManager.Root;
import dev.everyagent.worker.plugin.registry.AuthorizationHandlerRegistry;
import dev.everyagent.worker.task.TaskEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link GrantRegistry#execRootsSandboxed} 单测(§7.17 + §13.3 L2 纵深):外部授权根
 * 并入视图、过度宽泛根(盘根/工作区祖先)拒收、与已授权 EXEC 根去重。授权经真实
 * authorize(AuthorizationRequest) 决议链落档(弹窗打桩答「本任务」),WorkspaceManager
 * 打桩供给 resolve/externalRootsOf;grants.json 落盘目录经 TaskEntry.dataDir() 槽位注入。
 */
class GrantRegistryExecRootsTest {

    @TempDir
    Path tempDir;

    private TaskEntry task(Path ws) throws java.io.IOException {
        ModelConfig snap = new ModelConfig("cfg", "openai-compat",
                "http://localhost:9999/v1", "m", null);
        TaskEntry t = new TaskEntry("t-1", "任务", snap, ws.toString(), "defaultworkspace", "main-agent", 10_000);
        t.taskDir(Files.createDirectories(tempDir.resolve("tasks"))); // grants.json 落盘目录(dataDir 槽位)
        return t;
    }

    /** 组装授权请求(TaskEntry 即 ExecContext 实现)。 */
    private static AuthorizationRequest req(ExecContext ctx, String agentId, String grantKey, String prompt) {
        return new AuthorizationRequest(ctx, agentId, grantKey, prompt);
    }

    /** 弹窗打桩:答「本任务全程允许」(TASK 档,EXEC 根随授权落档)。 */
    private InteractionService asksTaskScope() throws InterruptedException {
        InteractionService asks = mock(InteractionService.class);
        when(asks.ask(anyList(), anyLong(), anyMap()))
                .thenReturn(new AskResult("answered", "本任务全程允许"));
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
                new AuthorizationHandlerRegistry(), null);
        TaskEntry t = task(ws);

        // 用户对 granted 授权 EXEC(随附旧宽根 fsRoot 模拟历史 grants.json 载入的 C:\ 形态)
        Path fsRoot = wsReal.getRoot();
        grants.authorize(req(t, "main-agent", "p::exec::" + granted.toRealPath(), "读目录"),
                List.of(), List.of(granted.toRealPath(), fsRoot), List.of());

        // 并入 extRoot;拒收 fsRoot(盘根)与 wsParent(工作区祖先);granted 与 externalRoots
        // 重叠出现一次(去重);EXEC 根在前、外部根在后
        assertEquals(List.of(granted.toRealPath(), extRoot.toRealPath()),
                grants.execRootsSandboxed(ws.toString(), "t-1"));
    }

    @Test
    void externalRootsOnlyWhenNoExecGrants(@TempDir Path ws) throws Exception {
        Path wsReal = ws.toRealPath();
        Path extRoot = Files.createDirectories(tempDir.resolve("only").resolve("ext"));
        GrantRegistry grants = new GrantRegistry(asksTaskScope(), new WorkerProperties(),
                stubWorkspaceManager(wsReal, List.of(extRoot.toRealPath())), new AuthorizationHandlerRegistry(),
                null);
        TaskEntry t = task(ws);

        // 无任何 EXEC 授权:视图仍含外部授权根(命令侧按 §7.17 直接生效)
        assertEquals(List.of(extRoot.toRealPath()), grants.execRootsSandboxed(t.workspaceRoot(), "t-1"));
    }

    @Test
    void emptyExternalRootsKeepsLegacyBehavior(@TempDir Path ws) throws Exception {
        Path wsReal = ws.toRealPath();
        Path granted = Files.createDirectories(tempDir.resolve("legacy").resolve("g"));
        GrantRegistry grants = new GrantRegistry(asksTaskScope(), new WorkerProperties(),
                stubWorkspaceManager(wsReal, List.of()), new AuthorizationHandlerRegistry(), null);
        TaskEntry t = task(ws);

        grants.authorize(req(t, "main-agent", "p::exec::" + granted.toRealPath(), "读目录"),
                List.of(), List.of(granted.toRealPath()), List.of());
        // 无外部根:与既有行为一致,只含过滤后的 EXEC 根
        assertEquals(List.of(granted.toRealPath()), grants.execRootsSandboxed(t.workspaceRoot(), "t-1"));
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
}

