package dev.everyagent.worker.tools.permission;

import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.plugin.api.interaction.AskResult;
import dev.everyagent.plugin.api.interaction.InteractionService;
import dev.everyagent.plugin.api.permission.AuthorizationChain;
import dev.everyagent.plugin.api.permission.AuthorizationHandler;
import dev.everyagent.plugin.api.permission.AuthorizationHandler.AuthorizationDecision;
import dev.everyagent.plugin.api.permission.AuthorizationHandler.AuthorizationRequest;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.plugin.registry.AuthorizationHandlerRegistry;
import dev.everyagent.worker.tools.PermissionDeniedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 授权决议链 fail-closed 收口回归(§7.8):
 * <ul>
 *   <li>链尾兜底拒绝——决议链<b>零节点</b>或<b>全 PASS</b>时,不再隐式放行(RUN 档),
 *       而是抛 {@link PermissionDeniedException}(「无授权决议者，默认拒绝」)。
 *       历史行为是 fail-open:链上没人决策=默认允许,一旦人工弹窗节点意外缺失
 *       (装配被破坏),所有越权请求会静默放行——本收口把它翻成默认拒绝。</li>
 *   <li>启动断言——{@link AuthorizationChainStartupCheck} 在人工授权节点缺失时
 *       抛异常中止 worker 启动(装配期 fail-fast),与链尾兜底构成双防线。</li>
 *   <li>正常弹窗路径不回归——真实 {@link HumanAuthorizationHandler} 决议 ALLOW 后
 *       仍正常落档放行。</li>
 * </ul>
 */
class AuthorizationChainFailClosedTest {

    @TempDir
    Path tempDir;

    // ---- 脚手架 ----

    /** 直构执行上下文(subjectId/dataDir/interaction 三槽位打桩)。 */
    private ExecContext ctx(InteractionService asks) {
        ExecContext ctx = mock(ExecContext.class);
        when(ctx.subjectId()).thenReturn("t-1");
        when(ctx.dataDir()).thenReturn(tempDir.resolve("tasks"));
        when(ctx.interaction()).thenReturn(asks);
        return ctx;
    }

    private static AuthorizationRequest req(ExecContext ctx, String grantKey) {
        return new AuthorizationRequest(ctx, "main-agent", grantKey, "读文件");
    }

    /** 弹窗打桩:回传指定作答文本。 */
    private static InteractionService popupAnswered(String answer) {
        InteractionService asks = mock(InteractionService.class);
        try {
            when(asks.ask(anyList(), anyLong(), anyMap())).thenReturn(new AskResult("answered", answer));
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
        return asks;
    }

    private GrantRegistry grants(AuthorizationHandlerRegistry handlers) {
        return new GrantRegistry(mock(InteractionService.class), new WorkerProperties(),
                mock(dev.everyagent.worker.modules.WorkspaceManager.class), handlers, null);
    }

    /** 恒 PASS 节点(只观察、不决策,如第三方插件的自动放行判定未命中场景)。 */
    private static AuthorizationHandler passThrough(String id, float order) {
        return new AuthorizationHandler() {
            @Override public String id() { return id; }
            @Override public float order() { return order; }
            @Override public AuthorizationDecision invoke(AuthorizationRequest req, AuthorizationChain next)
                    throws Exception {
                return next.proceed(req);
            }
        };
    }

    // ---- 链尾兜底拒绝(fail-closed) ----

    @Test
    void zeroHandlersDeniedInsteadOfSilentAllow() {
        GrantRegistry grants = grants(new AuthorizationHandlerRegistry()); // 零节点
        ExecContext ctx = ctx(popupAnswered("是否授权?：task"));

        PermissionDeniedException e = assertThrows(PermissionDeniedException.class,
                () -> grants.authorize(req(ctx, "p::read::" + tempDir.resolve("outside.txt")),
                        List.of(), List.of(), List.of()));
        assertEquals("无授权决议者，默认拒绝", e.getMessage());
        // 拒绝不得留下任何授权痕迹(不落 run/task 档)
        assertEquals(List.of(), grants.extraRoots("t-1"));
        assertEquals(List.of(), grants.execRoots("t-1"));
    }

    @Test
    void allPassHandlersDeniedAtChainTail() {
        AuthorizationHandlerRegistry handlers = new AuthorizationHandlerRegistry();
        handlers.register(passThrough("test-pass-1", 100f)); // 全 PASS:无人决策
        handlers.register(passThrough("test-pass-2", 200f));
        GrantRegistry grants = grants(handlers);
        ExecContext ctx = ctx(popupAnswered("是否授权?：task"));

        PermissionDeniedException e = assertThrows(PermissionDeniedException.class,
                () -> grants.authorize(req(ctx, "p::write::" + tempDir.resolve("outside.txt")),
                        List.of(), List.of(), List.of()));
        assertEquals("无授权决议者，默认拒绝", e.getMessage());
        assertEquals(List.of(), grants.extraRoots("t-1"));
    }

    // ---- 正常弹窗路径不回归 ----

    @Test
    void realPopupHandlerStillGrantsAndRecordsScope() {
        AuthorizationHandlerRegistry handlers = new AuthorizationHandlerRegistry();
        handlers.register(new HumanAuthorizationHandler(handlers, new WorkerProperties()));
        GrantRegistry grants = grants(handlers);
        Path outside = tempDir.resolve("outside").resolve("x.txt");
        ExecContext ctx = ctx(popupAnswered("是否授权?：run")); // 弹窗答「本轮运行内允许」

        assertDoesNotThrow(() -> grants.authorize(req(ctx, "p::read::" + outside),
                List.of(outside), List.of(), List.of()));
        // 授权落档:RUN 档根生效;新一轮用户输入后失效(证明落的是 run 档、路径未回归)
        assertEquals(List.of(outside), grants.extraRoots("t-1"));
        grants.beginRun("t-1");
        assertEquals(List.of(), grants.extraRoots("t-1"));
    }

    // ---- 启动断言(fail-fast) ----

    @Test
    void startupCheckFailsWithoutHumanHandler() {
        AuthorizationHandlerRegistry empty = new AuthorizationHandlerRegistry();
        empty.register(passThrough("test-pass-1", 100f)); // 有节点但没有人工授权节点
        assertThrows(IllegalStateException.class,
                () -> new AuthorizationChainStartupCheck(empty).afterSingletonsInstantiated());
        assertThrows(IllegalStateException.class,
                () -> new AuthorizationChainStartupCheck(new AuthorizationHandlerRegistry())
                        .afterSingletonsInstantiated());
    }

    @Test
    void startupCheckPassesWithHumanHandler() {
        AuthorizationHandlerRegistry handlers = new AuthorizationHandlerRegistry();
        handlers.register(new HumanAuthorizationHandler(handlers, new WorkerProperties()));
        AuthorizationChainStartupCheck check = new AuthorizationChainStartupCheck(handlers);
        assertDoesNotThrow(check::afterSingletonsInstantiated); // 不抛即通过
    }
}
