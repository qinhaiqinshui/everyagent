package dev.everyagent.worker.ship;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.event.EventRecord;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * wire 事件格式化器(ship 层):把 {@link EventRecord} 格式化为前端可合并的事件 JSON。
 *
 * <p>逻辑等价于 {@code TaskEvents.wireEvent(EventRecord, String)}——
 * ship 层不再直接依赖 task 域的 {@code TaskEvents},此为本层自持的副本。
 *
 * <p>wire 形态:主 agent 事件不带 payload.agentId(前端以缺省识别主线程),
 * 子 agent 事件必带(按 mainAgentId 注入)。
 * seq 序列化为字符串(64 位 Snowflake > JS Number.MAX_SAFE_INTEGER,wire 传输必须字符串,
 * 否则前端 JSON.parse 丢精度会把相邻事件判为同 seq 丢弃)。
 */
public final class EventWireFormatter {

    private EventWireFormatter() {
    }

    /**
     * 单条记录 → 前端可合并的事件 JSON:子 agent 事件把 agentId 并入 payload;
     * 主 agent 事件(agentId == mainAgentId)不并入——前端以 payload.agentId 缺省识别主线程。
     */
    public static ObjectNode wireEvent(EventRecord r, String mainAgentId) {
        ObjectNode e = Json.obj();
        e.put("seq", String.valueOf(r.seq()));
        e.put("ts", r.ts());
        e.put("event", r.event());
        JsonNode payload = r.payload();
        if (r.agentId() != null && !r.agentId().equals(mainAgentId)
                && payload != null && payload.isObject()) {
            ObjectNode merged = ((ObjectNode) payload).deepCopy();
            merged.put("agentId", r.agentId());
            e.set("payload", merged);
        } else {
            e.set("payload", payload == null ? Json.obj() : payload);
        }
        return e;
    }
}
