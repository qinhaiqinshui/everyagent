package dev.everyagent.plugin.aireview;

import dev.everyagent.plugin.api.WorkerServices;
import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.plugin.api.permission.AuthorizationChain;
import dev.everyagent.plugin.api.permission.AuthorizationHandler.AuthorizationDecision;
import dev.everyagent.plugin.api.permission.AuthorizationHandler.AuthorizationRequest;
import dev.everyagent.plugin.api.slash.SlashTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AiReviewAuthHandler 单测:开关判定真相源是<b>任务级 slash token</b>
 * ({@link SlashTokenService#hasKind(String, String)})。
 *
 * <p>关键回归:任务无 {@code ai.review} token(hasKind=false)→ 不适用,交下一环
 * ({@code next.proceed(req)});有 token → 进入 AI 审议路径(本类用 Mockito mock
 * {@link AiAuthReviewer} 覆盖三态分派,不引入真实审议所需的重量级桩件)。
 *
 * <p>不再断言 metadata["ai-review"]——那是随 meta.json 落盘的副本,本次修复已删除该判定。
 */
class AiReviewAuthHandlerTest {

    private static final String TASK_ID = "t-1";

    private WorkerServices services;
    private SlashTokenService slashTokens;
    private ExecContext context;
    private AiAuthReviewer reviewer;

    @BeforeEach
    void setUp() {
        services = mock(WorkerServices.class);
        slashTokens = mock(SlashTokenService.class);
        when(services.slashTokens()).thenReturn(slashTokens);
        // ExecContext 需被 mock 返回 subjectId(判定分区键)
        context = mock(ExecContext.class);
        when(context.subjectId()).thenReturn(TASK_ID);
        // AiAuthReviewer 是普通类,Mockito 可 mock(review 返回预设三态)
        reviewer = mock(AiAuthReviewer.class);
    }

    private AuthorizationRequest request() {
        return new AuthorizationRequest(context, "agent-1", "p::write::/x", "rm -rf /");
    }

    // ---- 关键回归:无 token → PASS(交下一环) ----

    @Test
    void proceedsWhenNoReviewToken() throws Exception {
        when(slashTokens.hasKind(TASK_ID, AiReviewToken.KIND)).thenReturn(false);
        AiReviewAuthHandler handler = new AiReviewAuthHandler(reviewer, services);

        AtomicInteger proceeded = new AtomicInteger();
        AuthorizationDecision marker =
                new AuthorizationDecision(AuthorizationDecision.Type.PASS, "marker");
        AuthorizationChain next = req -> {
            proceeded.incrementAndGet();
            return marker;
        };

        AuthorizationDecision d = handler.invoke(request(), next);

        assertEquals(1, proceeded.get(), "无 ai.review token → 应调用 next.proceed(req)(不适用放行)");
        assertSame(marker, d, "应原样返回下一环决议");
        // 判定依据是 slashTokens().hasKind(subjectId, ai.review)
        verify(slashTokens).hasKind(TASK_ID, AiReviewToken.KIND);
    }

    // ---- 有 token → 进入 AI 审议路径:ALLOW 短路 ----

    @Test
    void reviewsAndShortCircuitsWhenReviewTokenPresent() throws Exception {
        when(slashTokens.hasKind(TASK_ID, AiReviewToken.KIND)).thenReturn(true);
        when(reviewer.review(any()))
                .thenReturn(ReviewDecision.of(ReviewDecision.Verdict.ALLOW, 1.0, "安全"));
        AiReviewAuthHandler handler = new AiReviewAuthHandler(reviewer, services);

        AtomicInteger proceeded = new AtomicInteger();
        AuthorizationChain next = req -> {
            proceeded.incrementAndGet();
            return new AuthorizationDecision(AuthorizationDecision.Type.PASS, "next");
        };
        AuthorizationRequest req = request();

        AuthorizationDecision d = handler.invoke(req, next);

        assertEquals(AuthorizationDecision.Type.ALLOW, d.type(), "AI 审议 ALLOW 应短路放行");
        assertEquals(0, proceeded.get(), "ALLOW 短路,不应交下一环");
        verify(slashTokens).hasKind(TASK_ID, AiReviewToken.KIND);
        verify(reviewer).review(req);
    }

    // ---- 有 token + 审议回退(fallback)→ PASS(交下一环) ----

    @Test
    void proceedsWhenReviewFallsBack() throws Exception {
        when(slashTokens.hasKind(TASK_ID, AiReviewToken.KIND)).thenReturn(true);
        when(reviewer.review(any()))
                .thenReturn(ReviewDecision.fallback("timeout"));
        AiReviewAuthHandler handler = new AiReviewAuthHandler(reviewer, services);

        AtomicInteger proceeded = new AtomicInteger();
        AuthorizationDecision marker =
                new AuthorizationDecision(AuthorizationDecision.Type.PASS, "marker");
        AuthorizationChain next = req -> {
            proceeded.incrementAndGet();
            return marker;
        };

        AuthorizationDecision d = handler.invoke(request(), next);

        assertEquals(1, proceeded.get(), "审议回退(fallback)→ 交下一环");
        assertSame(marker, d);
    }
}