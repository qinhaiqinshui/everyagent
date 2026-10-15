package dev.everyagent.worker.tools.permission;

import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.plugin.api.permission.AuthorizationHandler.AuthorizationRequest;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.modules.WorkspaceManager;
import dev.everyagent.worker.modules.WorkspaceManager.Root;
import dev.everyagent.worker.tools.PermissionGate.Op;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 命令链 × 工作区外部授权根(§7.8/§7.17):用户经 {@code @} 弹窗显式选择的工作区外目录
 * 已注册为该工作区 externalRoots(语义 = 完全读写 READ+WRITE+EXEC),命令串中引用其内路径
 * <b>不得</b>再触发授权 ask / AI 审议。
 *
 * <p>回归背景(2026-10-14 实锤):用户消息里 {@code @} 引用了工作区外的任务目录
 * ({@code [外部引用] <绝对路径>}),AI 为该目录执行
 * {@code Get-ChildItem -Recurse -Force <该目录>} 时仍被 {@code CommandCheck} 判为
 * 「命令中访问工作区外路径」并弹授权(任务开着 AI 审议 → 又触发一轮审议)。
 * 根因:外部授权根放行环只在文件路径链({@link ExternalRootAllowCheck})上,命令链没接。
 */
class CommandCheckExternalRootsTest {

    @TempDir
    Path tempDir;

    /** 装齐被测节点与授权桩:工作区 + 外部授权根列表(realpath 形态)。 */
    private record Rig(CommandCheck check, GrantRegistry grants, Path ws) {
    }

    private Rig rig(List<Path> externalRoots) throws IOException {
        Path ws = Files.createDirectories(tempDir.resolve("ws"));
        Path wsReal = ws.toRealPath();
        WorkspaceManager wm = mock(WorkspaceManager.class);
        when(wm.resolve(anyString())).thenReturn(new Root(wsReal, wsReal));
        when(wm.externalRootsOf(anyString())).thenReturn(externalRoots);
        GrantRegistry grants = mock(GrantRegistry.class);
        return new Rig(new CommandCheck(new WorkerProperties(), wm, grants), grants, ws);
    }

    private static PermissionContext command(Path ws, String command) {
        ExecContext exec = mock(ExecContext.class);
        return PermissionContext.builder()
                .kind(PermissionContext.Kind.COMMAND)
                .workspaceRoot(ws.toString())
                .authReq(new AuthorizationRequest(exec, "main-agent", null, null))
                .agentId("main-agent")
                .command(command)
                .build();
    }

    /** 收口:跑一遍命令链,返回结论(全链不弹窗则为 ALLOW)。 */
    private static PermissionDecision run(CommandCheck check, Path ws, String command) {
        return check.invoke(command(ws, command), ctx -> PermissionDecision.allow("chain tail"));
    }

    /** 回归主案:外部授权根内的只读命令零授权(@ 引用过的目录,命令引用它不得再弹窗)。 */
    @Test
    void pathInsideExternalRootNeedsNoAuthorization() throws IOException {
        Path ext = Files.createDirectories(tempDir.resolve("ext")).toRealPath();
        Rig r = rig(List.of(ext));

        PermissionDecision d = run(r.check(), r.ws(),
                "Get-ChildItem -Recurse -Force \"" + ext + "\" | Select-Object FullName");
        assertTrue(d.isAllow(), "外部授权根内的命令直接放行");

        d = run(r.check(), r.ws(),
                "Get-ChildItem -Recurse -File \"" + ext.resolve("sub") + "\"");
        assertTrue(d.isAllow(), "外部授权根的子目录同样放行");

        verify(r.grants(), never()).authorize(any(), anyList(), anyList(), anyList());
    }

    /** 外部授权根内 = 完全读写(READ+WRITE+EXEC):危险动词引用其内路径同样不再授权。 */
    @Test
    void dangerousVerbInsideExternalRootNeedsNoAuthorization() throws IOException {
        Path ext = Files.createDirectories(tempDir.resolve("ext")).toRealPath();
        Path file = Files.writeString(ext.resolve("a.txt"), "x");
        Rig r = rig(List.of(ext));

        assertTrue(run(r.check(), r.ws(),
                "Remove-Item -Recurse -Force \"" + ext.resolve("tmp") + "\"").isAllow(),
                "根内待建目录的删除命令放行");
        assertTrue(run(r.check(), r.ws(),
                "Remove-Item -Force \"" + file + "\"").isAllow(), "根内已存在文件的删除命令放行");
        assertTrue(run(r.check(), r.ws(), "rm -rf " + ext + "/sub").isAllow(), "POSIX 同判");

        verify(r.grants(), never()).authorize(any(), anyList(), anyList(), anyList());
    }

    /** 授权根之外仍是越界:路径授权(EXEC key)+ 危险动词授权照旧(不因外部根而放大)。 */
    @Test
    void outsideExternalRootStillAsks() throws IOException {
        Path ext = Files.createDirectories(tempDir.resolve("ext")).toRealPath();
        Path sibling = Files.createDirectories(tempDir.resolve("sib")).toRealPath();
        Rig r = rig(List.of(ext));

        assertTrue(run(r.check(), r.ws(), "Get-ChildItem \"" + sibling + "\"").isAllow(),
                "兄弟目录:授权后放行(AI 审议/弹窗结论由决议链给出,此处桩为放行)");
        List<String> keys = authorizedKeys(r.grants());
        assertTrue(keys.contains(PathSupport.pathKey(sibling, Op.EXEC)),
                "兄弟目录仍走 EXEC 路径授权: " + keys);

        Path outside = Files.writeString(tempDir.resolve("outside.txt"), "x");
        run(r.check(), r.ws(), "Remove-Item -Force \"" + outside + "\"");
        keys = authorizedKeys(r.grants());
        assertTrue(keys.stream().anyMatch(k -> k.startsWith("c::")), "越界危险动词仍授权: " + keys);
        assertTrue(keys.stream().anyMatch(k -> k.startsWith("p::exec::")), "越界路径仍授权: " + keys);
    }

    /** 工作区内增删改查自由(既有语义不受影响):即使无外部授权根也不弹。 */
    @Test
    void workspaceInternalDangerousVerbStillFree() throws IOException {
        Rig r = rig(List.of());
        Path inWs = Files.writeString(r.ws().resolve("a.txt"), "x");

        assertTrue(run(r.check(), r.ws(), "Remove-Item -Recurse -Force \"" + inWs + "\"").isAllow());
        verify(r.grants(), never()).authorize(any(), anyList(), anyList(), anyList());
    }

    private static List<String> authorizedKeys(GrantRegistry grants) {
        ArgumentCaptor<AuthorizationRequest> captor = ArgumentCaptor.forClass(AuthorizationRequest.class);
        verify(grants, atLeastOnce()).authorize(captor.capture(), anyList(), anyList(), anyList());
        List<String> keys = new ArrayList<>();
        captor.getAllValues().forEach(req -> keys.add(req.grantKey()));
        return keys;
    }
}