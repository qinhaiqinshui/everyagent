package dev.everyagent.worker.tools.permission;

import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.plugin.api.interaction.AskResult;
import dev.everyagent.plugin.api.interaction.InteractionService;
import dev.everyagent.plugin.api.permission.AuthorizationHandler.AuthorizationDecision;
import dev.everyagent.plugin.api.permission.AuthorizationHandler.AuthorizationRequest;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.plugin.registry.AuthorizationHandlerRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link HumanAuthorizationHandler} 决议映射单测(回归护栏)。
 *
 * <p>事故背景:责任链重构曾把弹窗三档压成一档——「本任务全程允许」与「本轮运行内允许」
 * 都返回 `ALLOW`,再由 `GrantRegistry` 按 `ALLOW → RUN` 收口,于是「本任务全程」静默降级
 * 为「本轮」,授权不落盘、下一条用户输入即失效。本测试钉住「档位必须原样带出链外」。
 */
class HumanAuthorizationHandlerTest {

    /** 弹窗打桩:返回指定作答文本(ask 状态 answered)。 */
    private static AuthorizationRequest requestAnsweredWith(String answer) {
        return requestWith(new AskResult("answered", answer));
    }

    private static AuthorizationRequest requestWith(AskResult result) {
        InteractionService interaction = mock(InteractionService.class);
        try {
            when(interaction.ask(anyList(), anyLong(), anyMap())).thenReturn(result);
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
        ExecContext ctx = mock(ExecContext.class);
        when(ctx.interaction()).thenReturn(interaction);
        return new AuthorizationRequest(ctx, "a_main", "p::read::C:\\outside\\x.txt",
                "AI 请求读取工作区外路径: C:\\outside\\x.txt");
    }

    private static AuthorizationDecision decide(AuthorizationRequest req) throws Exception {
        HumanAuthorizationHandler handler =
                new HumanAuthorizationHandler(new AuthorizationHandlerRegistry(), new WorkerProperties());
        return handler.invoke(req, r -> new AuthorizationDecision(AuthorizationDecision.Type.PASS, "chain tail"));
    }

    @Test
    void taskOptionReturnsAllowWithTaskScope() throws Exception {
        // 前端实际回传形态:「题干：值」(radio 回传选项 value 稳定 token)
        AuthorizationDecision d = decide(requestAnsweredWith("是否授权?：task"));
        assertEquals(AuthorizationDecision.Type.ALLOW, d.type());
        assertEquals(AuthorizationDecision.Scope.TASK, d.scope());
        // 文案形态(旧协议/磁盘回放)同样落 TASK
        assertEquals(AuthorizationDecision.Scope.TASK,
                decide(requestAnsweredWith("是否授权?：本任务全程允许")).scope());
    }

    @Test
    void runOptionReturnsAllowWithRunScope() throws Exception {
        AuthorizationDecision d = decide(requestAnsweredWith("是否授权?：run"));
        assertEquals(AuthorizationDecision.Type.ALLOW, d.type());
        assertEquals(AuthorizationDecision.Scope.RUN, d.scope());
        assertEquals(AuthorizationDecision.Scope.RUN,
                decide(requestAnsweredWith("是否授权?：本轮运行内允许")).scope());
    }

    @Test
    void denyOptionAndTimeoutReturnDeny() throws Exception {
        assertEquals(AuthorizationDecision.Type.DENY, decide(requestAnsweredWith("是否授权?：deny")).type());
        assertEquals(AuthorizationDecision.Type.DENY, decide(requestAnsweredWith("是否授权?：拒绝")).type());
        // 未识别/超时/取消一律按拒绝(安全缺省)
        assertEquals(AuthorizationDecision.Type.DENY, decide(requestAnsweredWith("是否授权?：随便答的")).type());
        assertEquals(AuthorizationDecision.Type.DENY,
                decide(requestWith(new AskResult("timeout", null))).type());
        assertEquals(AuthorizationDecision.Type.DENY,
                decide(requestWith(new AskResult("cancelled", null))).type());
    }
}