package dev.everyagent.plugin.unattended;

import dev.everyagent.plugin.api.WorkerServices;
import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.plugin.api.permission.AuthorizationChain;
import dev.everyagent.plugin.api.permission.AuthorizationHandler;
import dev.everyagent.plugin.api.permission.AuthorizationHandler.AuthorizationDecision;
import dev.everyagent.plugin.api.permission.AuthorizationHandler.AuthorizationRequest;
import dev.everyagent.plugin.api.slash.SlashTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * UnattendedAuthHandler 单测:开关真相源 = 任务级 {@code unattended.mode} slash token
 * (经 {@code WorkerServices.slashTokens().hasKind(subjectId, KIND)} 判定)。
 *
 * <ul>
 *   <li>hasKind=false → 不处理,交下一环({@code next.proceed} 被调用,返回 tail 的 PASS);</li>
 *   <li>hasKind=true  → 短路 DENY(无人值守模式拒绝授权),不再走 tail。</li>
 * </ul>
 *
 * <p>约束(§14.9):插件对 worker 零依赖——WorkerServices / ExecContext 均以 Mockito mock 承接。
 */
class UnattendedAuthHandlerTest {

    private WorkerServices services;
    private SlashTokenService slashTokens;
    private ExecContext context;

    @BeforeEach
    void setUp() {
        services = mock(WorkerServices.class);
        slashTokens = mock(SlashTokenService.class);
        context = mock(ExecContext.class);
        when(services.slashTokens()).thenReturn(slashTokens);
        when(context.subjectId()).thenReturn("t-1");
    }

    private AuthorizationRequest request() {
        return new AuthorizationRequest(context, "agent-1", "p::write::/tmp/x", "允许写 /tmp/x?");
    }

    @Test
    void passesThroughWhenUnattendedTokenAbsent() throws Exception {
        when(slashTokens.hasKind("t-1", UnattendedToken.KIND)).thenReturn(false);

        UnattendedAuthHandler handler = new UnattendedAuthHandler(services);
        AtomicBoolean tailHit = new AtomicBoolean(false);
        AuthorizationChain tail = req -> {
            tailHit.set(true);
            return new AuthorizationDecision(AuthorizationDecision.Type.PASS, "tail");
        };

        AuthorizationDecision decision = handler.invoke(request(), tail);

        assertEquals(AuthorizationDecision.Type.PASS, decision.type(), "无 token 时不处理,交下一环");
        assertTrue(tailHit.get(), "无 token 时应确实走到 tail");
    }

    @Test
    void deniesWhenUnattendedTokenPresent() throws Exception {
        when(slashTokens.hasKind("t-1", UnattendedToken.KIND)).thenReturn(true);

        UnattendedAuthHandler handler = new UnattendedAuthHandler(services);
        AtomicBoolean tailHit = new AtomicBoolean(false);
        AuthorizationChain tail = req -> {
            tailHit.set(true);
            return new AuthorizationDecision(AuthorizationDecision.Type.PASS, "tail");
        };

        AuthorizationDecision decision = handler.invoke(request(), tail);

        assertEquals(AuthorizationDecision.Type.DENY, decision.type(), "无人值守模式短路拒绝授权");
        assertEquals("无人值守模式拒绝授权", decision.reason());
        assertFalse(tailHit.get(), "DENY 短路时不应走到 tail");
    }
}