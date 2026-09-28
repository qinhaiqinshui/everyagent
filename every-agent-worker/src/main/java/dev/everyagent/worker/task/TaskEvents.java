package dev.everyagent.worker.task;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.agent.AgentEventChannel;
import dev.everyagent.plugin.api.model.EmitEvent;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 任务事件发射器:所有 stream 频道事件的唯一出口(架构 §3.3)。
 * 统一事件模型后零状态——所有事件经 {@link #emit(EmitEvent)} 单一入口,
 * 产生方自行管理 id / kind / mode / persist。
 *
 * <p>wire 形态:主 agent 事件不带 payload.agentId(前端以缺省识别主线程),
 * 子 agent 事件必带(wireEvent 按 mainAgentId 注入)。
 * seq 序列化为字符串(64 位 Snowflake &gt; JS Number.MAX_SAFE_INTEGER,wire 传输必须字符串)。
 */
public final class TaskEvents implements AgentEventChannel {

    private final EventLog log;
    private final String mainAgentId;

    public TaskEvents(EventLog log, String mainAgentId) {
        this.log = log;
        this.mainAgentId = mainAgentId;
    }

    /**
     * 统一事件发射入口:组装 payload(title/summary/content/status + data→Json.toJson)
     * + ext(persist / operate)→ {@link EventLog#append}。
     *
     * <p>agentId 兜底:低层产生方留 null → 填入 mainAgentId(任务级事件)。
     *
     * @return seq(EventLog 分配的 Snowflake ID,即 e.id())
     */
    @Override
    public long emit(EmitEvent e) {
        String agentId = (e.agentId() == null || e.agentId().isEmpty())
                ? mainAgentId : e.agentId();

        ObjectNode payload = Json.obj();
        if (e.title() != null && !e.title().isEmpty()) {
            payload.put("title", e.title());
        }
        if (e.summary() != null && !e.summary().isEmpty()) {
            payload.put("summary", e.summary());
        }
        if (e.content() != null && !e.content().isEmpty()) {
            payload.put("content", e.content());
        }
        if (e.status() != null && !e.status().isEmpty()) {
            payload.put("status", e.status());
        }
        if (e.data() != null) {
            payload.set("data", Json.toJson(e.data()));
        }

        ObjectNode ext = Json.obj();
        ext.put("persist", e.persist());
        ext.put("operate", e.mode() == EmitEvent.Mode.APPEND ? "append" : "replace");

        return log.append(e.id(), e.kind(), payload, agentId, ext, !e.persist()).seq();
    }

    /**
     * 单条记录 → 前端可合并的事件 JSON:子 agent 事件把 agentId 并入 payload;
     * 主 agent 事件(agentId == mainAgentId)不并入——前端以 payload.agentId 缺省识别主线程。
     * seq 序列化为字符串(64 位 Snowflake > JS Number.MAX_SAFE_INTEGER,wire 传输必须字符串,
     * 否则前端 JSON.parse 丢精度会把相邻事件判为同 seq 丢弃;与 Frames.wirePub 同口径)。
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
