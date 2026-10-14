package dev.everyagent.plugin.aireview;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.event.EgressCtx;
import dev.everyagent.plugin.api.event.RoundEgressFilter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 轮次级出网过滤器:从轮次视图的 {@code agentRanges} 中移除 AI 审议 agent 的进出区间,
 * 使「AI 审议会话内容」不出现在前端的轮次/子 agent 轨里(§5.3/§14.13)。
 *
 * <p>轮次视图是 plugin-api 约定的<b>域中性 JSON 视图</b>(运行时 {@code ObjectNode},见
 * {@link RoundEgressFilter}),含可选 {@code agentRanges:[{agentId,title,startSeq,endSeq}]}。
 * 本过滤器把 {@code agentId} 以 {@code review-} 前缀开头的条目移除,其余条目原样保留;无
 * {@code agentRanges} 或为空则原样返回。返回视图(原样或裁剪后)= 放行整轮;不丢整轮。
 *
 * <p>与事件级 filter({@link AiReviewEventEgressFilter})同源:两者都按「审议 agentId 前缀约定」
 * 判定。分界不同——轮次轨是<b>内容视图</b>(审议轮区间属会话内容,一律裁掉);事件级则只丢「会话内容」
 * 事件,放行状态/用量(agent.started/agent.status/usage,前端 agent 胶囊列表的数据源)与结果 trace。
 */
public class AiReviewRoundEgressFilter implements RoundEgressFilter {

    /** 过滤器 id(本插件内稳定唯一,与事件级 filter 区分)。 */
    public static final String ID = "ai-review-hide-process-round";

    /** 链上位置:与事件级 filter 取同一次序值({@value});轮次级独立链,次序仅本插件内自洽。 */
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
    public Object apply(EgressCtx ctx, Object round) {
        if (!(round instanceof ObjectNode node)) {
            return round;
        }
        JsonNode ranges = node.path("agentRanges");
        if (!ranges.isArray() || ranges.size() == 0) {
            return node;
        }
        ArrayNode kept = Json.arr();
        for (JsonNode a : ranges) {
            if (!AiAuthReviewer.isReviewAgentId(a.path("agentId").asString(""))) {
                kept.add(a);
            }
        }
        node.set("agentRanges", kept);
        return node;
    }
}