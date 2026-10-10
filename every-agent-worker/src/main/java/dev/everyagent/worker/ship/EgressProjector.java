package dev.everyagent.worker.ship;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.event.EgressCtx;
import dev.everyagent.plugin.api.event.EventEgressFilter;
import dev.everyagent.plugin.api.event.EventRecord;
import dev.everyagent.plugin.api.event.RoundEgressFilter;
import dev.everyagent.worker.task.RoundIndex;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 出网单点投影器——<b>客户端可见形态的唯一转换者</b>。
 *
 * <p>「不可展示」是<b>出网(egress)</b>问题而非产生问题:事件照进 EventLog(落盘不动),出网侧收敛到
 * 本投影器——4 个出网口(stream 推送 / task.poll / task.roundTail / task.rounds)全部只经此处把
 * {@link EventRecord} / 轮次编码为前端可合并的 wire JSON。投影器内先跑一条<b>可插拔的出网过滤链</b>
 * ({@link EventEgressFilterRegistry} / {@link RoundEgressFilterRegistry}),再执行原 wire 逻辑。
 *
 * <p><b>唯一性</b>:原 {@code TaskEvents.wireEvent} 与 {@code EventWireFormatter} 的 wire 实现已整体
 * 迁入本类并删除——新出网口在类型层面无法绕过投影器(不存在其他 wire 转换者)。
 *
 * <p><b>契约</b>(见 {@link EventEgressFilter} / {@link RoundEgressFilter}):
 * <ul>
 *   <li>事件级作用于 <b>pre-wire 的 {@link EventRecord}</b>;过滤器丢弃返回 {@code null};</li>
 *   <li>过滤链<b>不得改动 {@code ext}</b>(ext 是落盘态镜像),改写须在 payload 内自成信封;</li>
 *   <li>轮次级先以域中性 JSON 视图跑链(可裁剪 agentRanges),再 wire(seq 字符串化)。</li>
 * </ul>
 *
 * <p>wire 形态:主 agent 事件不带 payload.agentId(前端以缺省识别主线程),子 agent 事件必带;
 * seq 序列化为字符串(64 位 Snowflake &gt; JS Number.MAX_SAFE_INTEGER,wire 传输必须字符串)。
 */
@Component
public class EgressProjector {

    private final EventEgressFilterRegistry eventFilters;
    private final RoundEgressFilterRegistry roundFilters;

    public EgressProjector(EventEgressFilterRegistry eventFilters,
                           RoundEgressFilterRegistry roundFilters) {
        this.eventFilters = eventFilters;
        this.roundFilters = roundFilters;
    }

    /**
     * 事件级出网投影:先按 order 跑 {@link EventEgressFilter} 链(可丢/改),通过后执行原 wire 逻辑
     * (把子 agent 事件注入 payload.agentId、seq 字符串化等)。
     *
     * @param subjectId  主体 id(task 域 = taskId)
     * @param mainAgentId 主 agent id(子 agent 事件据此判定是否注入 payload.agentId)
     * @return 前端可合并的事件 JSON;被过滤链丢弃时返回 {@code null}(调用方跳过推送,但游标仍推进)
     */
    public ObjectNode projectEvent(String subjectId, String mainAgentId, EventRecord r) {
        EgressCtx ctx = new EgressCtx(subjectId);
        EventRecord cur = r;
        for (EventEgressFilter f : eventFilters.sorted()) {
            cur = f.apply(ctx, cur);
            if (cur == null) {
                return null; // 丢弃(不进入任何出网口;事件仍照常落盘)
            }
        }
        return wireEvent(cur, mainAgentId);
    }

    /**
     * 轮次级出网投影:先以域中性 JSON 视图跑 {@link RoundEgressFilter} 链(可裁剪 agentRanges / 丢轮),
     * 再 wire 为前端 rounds 形态(seq 字符串化)。
     *
     * @param subjectId  主体 id(task 域 = taskId)
     * @param mainAgentId 主 agent id(预留:轮次视图按主体语义裁剪时可用)
     * @return 前端 rounds 条目 JSON;被过滤链丢弃时返回 {@code null}
     */
    public ObjectNode projectRound(String subjectId, String mainAgentId, RoundIndex.Round r) {
        EgressCtx ctx = new EgressCtx(subjectId);
        Object cur = toEgressRound(r);
        for (RoundEgressFilter f : roundFilters.sorted()) {
            cur = f.apply(ctx, cur);
            if (cur == null) {
                return null; // 丢弃整轮
            }
        }
        return wireRound((ObjectNode) cur);
    }

    // ---- 事件 wire(自 TaskEvents.wireEvent 原样迁入)----

    /**
     * 单条记录 → 前端可合并的事件 JSON:子 agent 事件把 agentId 并入 payload;
     * 主 agent 事件(agentId == mainAgentId)不并入——前端以 payload.agentId 缺省识别主线程。
     * seq 序列化为字符串(64 位 Snowflake &gt; JS Number.MAX_SAFE_INTEGER,wire 传输必须字符串,
     * 否则前端 JSON.parse 丢精度会把相邻事件判为同 seq 丢弃)。
     */
    private static ObjectNode wireEvent(EventRecord r, String mainAgentId) {
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

    // ---- 轮次:Round → pre-wire 视图 → (过滤) → wire(自 TaskManager.wireRound 原样迁入)----

    /**
     * {@link RoundIndex.Round} → 域中性 pre-wire JSON 视图(供 {@link RoundEgressFilter} 处理)。
     * seq 保持数值、未闭合端为 JSON null;字段顺序与最终 wire 一致。
     */
    private static ObjectNode toEgressRound(RoundIndex.Round r) {
        ObjectNode n = Json.obj();
        n.put("index", r.index());
        n.put("startSeq", r.startSeq());
        if (r.endSeq() != null) {
            n.put("endSeq", r.endSeq());
        } else {
            n.putNull("endSeq");
        }
        n.put("user", r.user());
        n.put("finalReply", r.finalReply());
        n.put("durationMs", r.durationMs());
        if (r.roundId() != null && !r.roundId().isBlank()) {
            n.put("roundId", r.roundId()); // roundId 稳定主键:缺失(旧行)不写
        }
        if (r.userMessage() != null) {
            n.set("userMessage", r.userMessage()); // 完整 user.message payload(旧行缺失不写)
        }
        ArrayNode agentRanges = Json.arr();
        for (RoundIndex.AgentRange s : r.agentRanges()) {
            ObjectNode e = Json.obj();
            e.put("agentId", s.agentId());
            e.put("title", s.title());
            if (s.startSeq() != null) {
                e.put("startSeq", s.startSeq());
            } else {
                e.putNull("startSeq");
            }
            if (s.endSeq() != null) {
                e.put("endSeq", s.endSeq());
            } else {
                e.putNull("endSeq");
            }
            agentRanges.add(e);
        }
        n.set("agentRanges", agentRanges);
        return n;
    }

    /**
     * pre-wire 视图 → 前端 wire 形态(seq 一律字符串;endSeq 未闭合为 "";roundId 非空才写;
     * 与 rounds.jsonl 行格式一致)。零过滤时与原 {@code TaskManager.wireRound} 逐字节等价。
     */
    private static ObjectNode wireRound(ObjectNode r) {
        JsonNode endSeq = r.path("endSeq");
        ObjectNode n = Json.obj();
        n.put("index", r.path("index").asLong());
        n.put("startSeq", String.valueOf(r.path("startSeq").asLong()));
        n.put("endSeq", (endSeq.isMissingNode() || endSeq.isNull()) ? "" : String.valueOf(endSeq.asLong()));
        n.put("user", r.path("user").asString(""));
        n.put("finalReply", r.path("finalReply").asString(""));
        n.put("durationMs", r.path("durationMs").asLong());
        JsonNode roundId = r.path("roundId");
        if (roundId.isTextual() && !roundId.asText().isBlank()) {
            n.put("roundId", roundId.asText()); // roundId 稳定主键:缺失(旧行)不写
        }
        if (r.has("userMessage")) {
            JsonNode um = r.get("userMessage");
            if (um != null && !um.isNull()) {
                n.set("userMessage", um); // 完整 user.message payload:懒加载骨架起点(旧行缺失不写)
            }
        }
        ArrayNode agentRanges = Json.arr();
        JsonNode ranges = r.path("agentRanges");
        if (ranges.isArray()) {
            for (JsonNode a : ranges) {
                JsonNode s = a.path("startSeq");
                JsonNode e = a.path("endSeq");
                agentRanges.add(Json.obj()
                        .put("agentId", a.path("agentId").asString(""))
                        .put("title", a.path("title").asString(""))
                        .put("startSeq", (s.isMissingNode() || s.isNull()) ? "" : String.valueOf(s.asLong()))
                        .put("endSeq", (e.isMissingNode() || e.isNull()) ? "" : String.valueOf(e.asLong())));
            }
        }
        n.set("agentRanges", agentRanges);
        return n;
    }
}