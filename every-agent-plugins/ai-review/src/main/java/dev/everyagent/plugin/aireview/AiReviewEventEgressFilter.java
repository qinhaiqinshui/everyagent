package dev.everyagent.plugin.aireview;

import dev.everyagent.plugin.api.event.EgressCtx;
import dev.everyagent.plugin.api.event.EventEgressFilter;
import dev.everyagent.plugin.api.event.EventRecord;

/**
 * 事件级出网过滤器:丢弃 AI 审议 agent 的<b>过程事件</b>,让「AI 审议过程」不出网(但仍照常落盘)。
 *
 * <p>审议 agent 以 per-task 固定 agentId {@code review-<subjectId>} 注册(§8.3)。其
 * {@code run()} 期间由 advisor 链以自身身份发射的全部过程事件——思考(delta/thinking)、正文(message)、
 * usage、per-run 生命周期(agent.started/status/done/error)、工具结果——都带 {@code review-} 前缀
 * 的 agentId,一律在此丢弃(返回 {@code null});事件体本身照旧进 {@code EventLog} 落盘,只是
 * <b>客户端不可见</b>(出网过滤,§5.3/§14.13),落盘与 seq 空间完全不变。
 *
 * <p><b>结果 trace 豁免</b>:审议结论 {@code auth.review} 以审议 agent 自身身份发射
 * ({@link AiAuthReviewer#emitAuthTrace}),同样带 {@code review-} agentId,但它是<b>结果</b>而非
 * 过程,必须保持客户端可见(前端仍显示「允许 置信度 90%」,§7.9/§14.13)。故本过滤器只丢弃
 * 「过程事件」,对结果 trace(kind={@link AiAuthReviewer#AUTH_REVIEW_KIND})放行——否则前缀规则会把
 * 结果 trace 一并丢弃,与「审议结果保留显示」的总目标相悖。
 */
public class AiReviewEventEgressFilter implements EventEgressFilter {

    /** 过滤器 id(本插件内稳定唯一)。 */
    public static final String ID = "ai-review-hide-process";

    /**
     * 链上位置:纯丢弃策略,取一个明确靠后的次序值({@value})——给未来更靠前、更细粒度的
     * payload 改写过滤器留出插入位;本插件内只有这一个事件级 filter,次序不与其它节点竞争。
     */
    public static final int ORDER = 1000;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public EventRecord apply(EgressCtx ctx, EventRecord record) {
        if (record == null) {
            return null;
        }
        // 审议 agent 的过程事件:agentId 以 review- 前缀开头 → 丢弃(不出网,仍落盘)。
        // 唯一的例外是结果 trace(kind=auth.review):它虽同属审议 agentId,但是「结果」不是
        // 「过程」,必须保留可见(§14.13「丢弃审议 agent 的过程事件」;结果 trace 保持可见)。
        if (AiAuthReviewer.isReviewAgentId(record.agentId())
                && !AiAuthReviewer.AUTH_REVIEW_KIND.equals(record.event())) {
            return null;
        }
        return record;
    }
}