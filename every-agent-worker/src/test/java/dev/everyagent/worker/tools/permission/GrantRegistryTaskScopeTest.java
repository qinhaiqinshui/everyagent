package dev.everyagent.worker.tools.permission;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.plugin.api.interaction.AskResult;
import dev.everyagent.plugin.api.interaction.InteractionService;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 「本任务全程允许」档位端到端回归(弹窗 → 决议链 → 落盘 grants.json)。
 *
 * <p>事故背景:决议链重构把弹窗档位压成一档(恒为 RUN),「本任务全程允许」既不写
 * grants.json、也不跨轮次保留——表现为「点了本任务全程,下一条输入后又得重新授权」。
 * 本测试同时覆盖真实 {@link HumanAuthorizationHandler} 的档位映射与
 * {@link GrantRegistry} 的落盘/重载。
 */
class GrantRegistryTaskScopeTest {

    @TempDir
    Path tempDir;

    /** 带 taskDir(dataDir 槽位)的任务上下文。 */
    private TaskEntry task(Path ws) throws java.io.IOException {
        ModelConfig snap = new ModelConfig("cfg", "openai-compat",
                "http://localhost:9999/v1", "m", null);
        TaskEntry t = new TaskEntry("t-1", "任务", snap, ws.toString(), "defaultworkspace", "main-agent", 10_000);
        t.taskDir(Files.createDirectories(tempDir.resolve("tasks")));
        return t;
    }

    private static WorkspaceManager stubWorkspaceManager(Path wsReal) throws java.io.IOException {
        WorkspaceManager wm = mock(WorkspaceManager.class);
        when(wm.resolve(anyString())).thenReturn(new Root(wsReal, wsReal));
        when(wm.externalRootsOf(anyString())).thenReturn(List.of());
        return wm;
    }

    /** 决议链:真实人工弹窗节点(打桩作答文本)+ 链尾兜底。 */
    private GrantRegistry withHumanPopup(TaskEntry t, String answer) throws Exception {
        InteractionService interaction = mock(InteractionService.class);
        try {
            when(interaction.ask(anyList(), anyLong(), anyMap()))
                    .thenReturn(new AskResult("answered", answer));
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
        ExecContext ctx = mock(ExecContext.class);
        when(ctx.interaction()).thenReturn(interaction);

        AuthorizationHandlerRegistry reg = new AuthorizationHandlerRegistry();
        AuthorizationHandler stub = new AuthorizationHandler() {
            @Override public String id() { return "human-authorization"; }
            @Override public float order() { return 300f; }
            @Override public AuthorizationDecision invoke(AuthorizationRequest req, AuthorizationChain next)
                    throws Exception {
                // 复用真实节点的档位映射逻辑(同一份 parseScope + Scope 映射)
                HumanAuthorizationHandler real =
                        new HumanAuthorizationHandler(new AuthorizationHandlerRegistry(), new WorkerProperties());
                // 真实节点读 req.context().interaction(),这里换成打桩上下文
                return real.invoke(new AuthorizationRequest(ctx, req.agentId(), req.grantKey(), req.prompt()), next);
            }
        };
        reg.register(stub);
        return new GrantRegistry(mock(InteractionService.class), new WorkerProperties(),
                stubWorkspaceManager(t.workspaceRoot() == null ? null : Path.of(t.workspaceRoot())), reg, null);
    }

    @Test
    void taskOptionPersistsGrantsJsonAndSurvivesReload(@TempDir Path ws) throws Exception {
        Path wsReal = ws.toRealPath();
        Path outside = Files.createDirectories(tempDir.resolve("outside"));
        Path target = Files.writeString(outside.resolve("x.txt"), "hi");
        TaskEntry t = task(wsReal);
        String grantKey = "p::read::" + target;

        GrantRegistry grants = withHumanPopup(t, "是否授权?：task");
        grants.authorize(new AuthorizationRequest(t, "main-agent", grantKey, "读文件"),
                List.of(), List.of(), List.of());

        // ① 落盘:taskDir/grants.json 含该 key
        Path grantsFile = t.dataDir().resolve("grants.json");
        assertTrue(Files.isRegularFile(grantsFile), "「本任务全程允许」必须落盘 grants.json");
        List<String> persisted = new java.util.ArrayList<>();
        for (var n : Json.parse(Files.readString(grantsFile)).path("taskGrants")) {
            persisted.add(n.asString(""));
        }
        assertTrue(persisted.contains(grantKey), "grants.json 应记录该 grantKey,实际=" + persisted);

        // ② 重载(模拟进程重启/冷启动):新实例仍认这把 key,不再弹窗
        InteractionService neverAsked = mock(InteractionService.class);
        GrantRegistry reloaded = new GrantRegistry(neverAsked, new WorkerProperties(),
                stubWorkspaceManager(wsReal), new AuthorizationHandlerRegistry(), null);
        reloaded.authorize(new AuthorizationRequest(t, "main-agent", grantKey, "读文件"),
                List.of(), List.of(), List.of());
        try {
            org.mockito.Mockito.verify(neverAsked, org.mockito.Mockito.never())
                    .ask(anyList(), anyLong(), anyMap());
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void runOptionStaysInMemoryAndDoesNotPersist(@TempDir Path ws) throws Exception {
        Path wsReal = ws.toRealPath();
        Path outside = Files.createDirectories(tempDir.resolve("outside-run"));
        Path target = Files.writeString(outside.resolve("y.txt"), "hi");
        TaskEntry t = task(wsReal);
        String grantKey = "p::read::" + target;

        GrantRegistry grants = withHumanPopup(t, "是否授权?：run");
        grants.authorize(new AuthorizationRequest(t, "main-agent", grantKey, "读文件"),
                List.of(), List.of(), List.of());

        assertFalse(Files.exists(t.dataDir().resolve("grants.json")),
                "「本轮运行内允许」是纯内存档,不得落盘");
        // 新一轮用户输入到达 → run 档清空,同 key 需重新授权
        grants.beginRun(t.subjectId());
        // 内存中已无该授权(重建实例观察磁盘态即可验证跨运行不保留)
        GrantRegistry fresh = new GrantRegistry(mock(InteractionService.class), new WorkerProperties(),
                stubWorkspaceManager(wsReal), new AuthorizationHandlerRegistry(), null);
        assertEquals(List.of(), fresh.extraRoots(t.subjectId()));
    }
}