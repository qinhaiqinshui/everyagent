package dev.everyagent.worker.task;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.model.EmitEvent;
import dev.everyagent.plugin.api.model.EventEmitter;
import tools.jackson.databind.node.ObjectNode;

/**
 * 任务事件发射器:所有 stream 频道事件的唯一出口(架构 §3.3)。
 * 统一事件模型后零状态——所有事件经 {@link #emit(EmitEvent)} 单一入口,
 * 产生方自行管理 id / kind / mode / persist。
 *
 * <p>wire 形态(客户端可见形态)统一由出网单点投影器 {@code EgressProjector} 产出:
 * 主 agent 事件不带 payload.agentId(前端以缺省识别主线程),子 agent 事件必带。
 */
public final class TaskEvents implements EventEmitter {

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
}
