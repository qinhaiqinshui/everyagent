package dev.everyagent.plugin.aireview;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.event.EgressCtx;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AiReviewRoundEgressFilter 单测:轮次视图中审议 agent 的 agentRanges 条目被移除,其余原样保留;
 * 无 agentRanges / 空 / 非对象视图原样返回。
 */
class AiReviewRoundEgressFilterTest {

    private final AiReviewRoundEgressFilter filter = new AiReviewRoundEgressFilter();

    private static ObjectNode range(String agentId) {
        ObjectNode r = Json.obj();
        r.put("agentId", agentId);
        r.put("title", agentId + "-title");
        r.put("startSeq", 120);
        r.put("endSeq", 150);
        return r;
    }

    private static ObjectNode roundWith(ObjectNode... ranges) {
        ObjectNode n = Json.obj();
        n.put("index", 1);
        n.put("startSeq", 100);
        n.put("endSeq", 160);
        n.put("user", "u");
        n.put("finalReply", "r");
        var arr = Json.arr();
        for (ObjectNode r : ranges) {
            arr.add(r);
        }
        n.set("agentRanges", arr);
        return n;
    }

    @Test
    void trimsReviewAgentRanges() {
        ObjectNode n = roundWith(range("sub_1"), range("review-t-1"), range("sub_2"));
        ObjectNode res = (ObjectNode) filter.apply(new EgressCtx("t-1"), n);
        JsonNode ranges = res.path("agentRanges");
        assertEquals(2, ranges.size());
        assertEquals("sub_1", ranges.get(0).path("agentId").asString());
        assertEquals("sub_2", ranges.get(1).path("agentId").asString());
        // 其余字段原样:轮本身(用户/最终回复/seq)不动。
        assertEquals("u", res.path("user").asString());
        assertEquals(100, res.path("startSeq").asInt());
        assertEquals(160, res.path("endSeq").asInt());
    }

    @Test
    void keepsRoundWithoutReviewRange() {
        ObjectNode n = roundWith(range("sub_1"), range("sub_2"));
        assertSame(n, filter.apply(new EgressCtx("t-1"), n));
    }

    @Test
    void keepsRoundWithoutAgentRanges() {
        ObjectNode n = Json.obj();
        n.put("index", 2);
        assertSame(n, filter.apply(new EgressCtx("t-1"), n));
    }

    @Test
    void passesThroughNonObjectRound() {
        Object other = "not-an-object";
        assertSame(other, filter.apply(new EgressCtx("t-1"), other));
    }
}