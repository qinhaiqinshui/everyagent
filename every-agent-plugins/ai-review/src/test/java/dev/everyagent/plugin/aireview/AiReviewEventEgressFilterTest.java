package dev.everyagent.plugin.aireview;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.event.EgressCtx;
import dev.everyagent.plugin.api.event.EventRecord;
import dev.everyagent.plugin.api.event.Events;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AiReviewEventEgressFilter 单测:审议 agent 的<b>会话内容</b>事件(思考/正文/工具结果/错误/
 * agent.done)在出网侧被丢弃(返回 null);状态与用量(agent.started/agent.status/usage)、
 * 结果 trace(auth.review)与主 agent 事件放行。
 */
class AiReviewEventEgressFilterTest {

    private final AiReviewEventEgressFilter filter = new AiReviewEventEgressFilter();

    private static EventRecord rec(String kind, String agentId) {
        return new EventRecord(1L, 1L, kind, agentId, Json.obj(), Json.obj());
    }

    @Test
    void dropsReviewAgentContentEvents() {
        EgressCtx ctx = new EgressCtx("t-1");
        assertNull(filter.apply(ctx, rec(Events.DELTA, "review-t-1")), "思考 delta 应丢弃");
        assertNull(filter.apply(ctx, rec(Events.THINKING, "review-t-1")), "思考 thinking 应丢弃");
        assertNull(filter.apply(ctx, rec(Events.MESSAGE, "review-t-1")), "正文 message 应丢弃");
        assertNull(filter.apply(ctx, rec(Events.TOOL_RESULT, "review-t-1")), "工具结果应丢弃");
        assertNull(filter.apply(ctx, rec(Events.ERROR, "review-t-1")), "错误应丢弃");
        assertNull(filter.apply(ctx, rec(Events.TASK_TRACE, "review-t-1")), "重试/容灾 trace 应丢弃");
        assertNull(filter.apply(ctx, rec(Events.AGENT_DONE, "review-t-1")),
                "agent.done 带本轮输出正文(content),属会话内容,应丢弃");
    }

    @Test
    void keepsReviewAgentStatusAndUsageVisible() {
        // 状态/用量是前端 agent 胶囊列表(输入框上方)的数据源:必须出网,否则审议胶囊在整轮
        // 运行期间停在「空闲 + 无用量」,直到任务收口后重拉台账才补齐(实测 bug)。
        EgressCtx ctx = new EgressCtx("t-1");
        EventRecord started = rec(Events.AGENT_STARTED, "review-t-1");
        assertSame(started, filter.apply(ctx, started), "agent.started 应放行(胶囊标题/创建时间)");
        EventRecord status = rec(Events.AGENT_STATUS, "review-t-1");
        assertSame(status, filter.apply(ctx, status), "agent.status 应放行(胶囊状态)");
        EventRecord usage = rec(Events.USAGE, "review-t-1");
        assertSame(usage, filter.apply(ctx, usage), "usage 应放行(胶囊用量/上下文占用)");
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
        EventRecord main = rec(Events.DELTA, "main-t-1");
        assertSame(main, filter.apply(ctx, main), "非审议 agent 事件原样放行");
        EventRecord mainMessage = rec(Events.MESSAGE, "main-t-1");
        assertSame(mainMessage, filter.apply(ctx, mainMessage), "主 agent 正文原样放行");
        EventRecord taskLevel = rec(Events.MESSAGE, null);
        assertSame(taskLevel, filter.apply(ctx, taskLevel), "agentId 为 null 的任务级事件放行");
    }

    @Test
    void hasStableIdAndOrder() {
        assertTrue(filter.id() != null && !filter.id().isBlank(), "id 应稳定非空");
        assertTrue(filter.order() == AiReviewEventEgressFilter.ORDER, "order 应为约定次序值");
    }
}