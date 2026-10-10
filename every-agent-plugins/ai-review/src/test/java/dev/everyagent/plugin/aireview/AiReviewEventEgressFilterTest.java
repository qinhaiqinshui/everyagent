package dev.everyagent.plugin.aireview;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.event.EgressCtx;
import dev.everyagent.plugin.api.event.EventRecord;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AiReviewEventEgressFilter 单测:审议 agent 的过程事件(思考/正文/usage/生命周期/工具结果)
 * 在出网侧被丢弃(返回 null);结果 trace(auth.review)与主 agent 事件放行。
 */
class AiReviewEventEgressFilterTest {

    private final AiReviewEventEgressFilter filter = new AiReviewEventEgressFilter();

    private static EventRecord rec(String kind, String agentId) {
        return new EventRecord(1L, 1L, kind, agentId, Json.obj(), Json.obj());
    }

    @Test
    void dropsReviewAgentProcessEvents() {
        EgressCtx ctx = new EgressCtx("t-1");
        assertNull(filter.apply(ctx, rec("llm.delta", "review-t-1")), "思考 delta 应丢弃");
        assertNull(filter.apply(ctx, rec("message", "review-t-1")), "正文 message 应丢弃");
        assertNull(filter.apply(ctx, rec("usage", "review-t-1")), "usage 应丢弃");
        assertNull(filter.apply(ctx, rec("agent.started", "review-t-1")), "生命周期事件应丢弃");
        assertNull(filter.apply(ctx, rec("tool.result", "review-t-1")), "工具结果应丢弃");
    }

    @Test
    void keepsResultTraceVisible() {
        // 结果 trace(auth.review)虽同属审议 agentId,但属「结果」不属「过程」→ 放行,保持客户端可见。
        EventRecord r = rec(AiAuthReviewer.AUTH_REVIEW_KIND, "review-t-1");
        assertSame(r, filter.apply(new EgressCtx("t-1"), r));
    }

    @Test
    void keepsMainAgentAndNullAgentEvents() {
        EgressCtx ctx = new EgressCtx("t-1");
        EventRecord main = rec("llm.delta", "main-t-1");
        assertSame(main, filter.apply(ctx, main), "非审议 agent 事件原样放行");
        EventRecord taskLevel = rec("message", null);
        assertSame(taskLevel, filter.apply(ctx, taskLevel), "agentId 为 null 的任务级事件放行");
    }

    @Test
    void hasStableIdAndOrder() {
        assertTrue(filter.id() != null && !filter.id().isBlank(), "id 应稳定非空");
        assertTrue(filter.order() == AiReviewEventEgressFilter.ORDER, "order 应为约定次序值");
    }
}