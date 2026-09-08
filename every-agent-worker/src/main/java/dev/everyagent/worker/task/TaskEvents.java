package dev.everyagent.worker.task;

import dev.everyagent.contract.json.Json;
import dev.everyagent.worker.proto.Events;
import dev.everyagent.worker.proto.Events.ToolCallPart;
import dev.everyagent.worker.proto.ShortIds;
import dev.everyagent.worker.proto.SnowflakeId;
import dev.everyagent.worker.proto.TaskDtos.ModelSnapshot;
import dev.everyagent.worker.proto.TaskDtos.Usage;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 任务事件发射器:所有 stream 频道事件的唯一出口(架构 §3.3)。
 * 事件名不分主/子(delta/message/error 同名),归属由 agentId 决定。
 * 纪律:record.agentId 恒非空(任务级事件 = mainAgentId)——jsonl 每行可路由到
 * &lt;agentId&gt;.jsonl;wire 形态主 agent 事件不带 payload.agentId(前端以缺省识别主线程),
 * 子 agent 事件必带(wireEvent 按 mainAgentId 剥离)。
 *
 * <p>纯显示事件收敛:重试生命周期 / 任务耗时等无副作用展示统一走 {@link Events#TASK_TRACE}
 * (带 traceId,前端按 id 原地 upsert——同一波重试的 attempt/progress/resolved 共用一条 trace)。
 * 瞬态实例(每秒倒计时的 progress 等)经 {@code ext.persist=false} 标记,不落盘。
 */
public final class TaskEvents {

    private final EventLog log;
    private final String mainAgentId;
    /** agentId → 当前波次重试 traceId(attempt 建、resolved/exhausted 收尾移除,仿 node 侧 upsert)。 */
    private final Map<String, String> retryTraceIds = new ConcurrentHashMap<>();
    /** agentId → 当前上下文压缩 traceId(压缩开始建、完成/兜底收尾移除,同 traceId upsert)。 */
    private final Map<String, String> compressionTraceIds = new ConcurrentHashMap<>();
    /**
     * traceId → 当前容灾切换链已累积的完整模型链(同一波连续失败切换共用一条 trace,
     * 逐次追加「 -> 模型」,作为 trace 的 content 展示;波收口 {@link #modelFailoverClose} 移除)。
     */
    private final Map<String, String> failoverTraceChains = new ConcurrentHashMap<>();
    /**
     * agentId → 当前轮共享 seq(雪花 ID):一轮 = 一次模型响应回合。流式 chunk(delta/thinking)
     * 到来时若该 agent 尚无当前轮 seq 则现场分配一个,之后该轮全部 delta/thinking 与定型
     * message 都用这个 seq;message 发送完成后清空(下一轮重新分配)。独立于 retryTraceIds,
     * 与本任务其它 agent 互不干扰(并发安全:ConcurrentHashMap + computeIfAbsent/remove)。
     */
    private final Map<String, Long> roundSeqs = new ConcurrentHashMap<>();

    public TaskEvents(EventLog log, String mainAgentId) {
        this.log = log;
        this.mainAgentId = mainAgentId;
    }

    public long userMessage(String text) {
        return userMessage(text, null);
    }

    /**
     * 用户消息事件:text 为 AI 可见明文;rawContent 为原始输入(含 opaque token 串,
     * 仅用于前端消息回放还原胶囊,可为空=纯文本输入)。
     */
    public long userMessage(String text, String rawContent) {
        ObjectNode p = Json.obj();
        p.put("text", text);
        if (rawContent != null && !rawContent.isEmpty()) {
            p.put("rawContent", rawContent);
        }
        return log.append(Events.USER_MESSAGE, p, mainAgentId, null).seq();
    }

    /**
     * 一轮新开(瞬态,不落盘;仅主 agent)。payload.startSeq = 开轮 user.message 的 seq,
     * 供前端把本轮折叠标记锚定到该输入;ext.persist=false 保证不写磁盘 jsonl。
     */
    public long roundOpened(long startSeq, String user) {
        ObjectNode p = Json.obj();
        p.put("startSeq", String.valueOf(startSeq));
        p.put("user", user == null ? "" : user);
        ObjectNode ext = Json.obj().put("persist", false);
        return log.append(Events.ROUND_OPENED, p, mainAgentId, ext).seq();
    }

    /**
     * 一轮闭合(瞬态,不落盘;仅主 agent)。payload.startSeq/endSeq/finalReply 与
     * rounds.jsonl 闭合行同源;ext.persist=false 保证不写磁盘 jsonl。
     */
    public long roundClosed(long startSeq, long endSeq, String finalReply) {
        ObjectNode p = Json.obj();
        p.put("startSeq", String.valueOf(startSeq));
        p.put("endSeq", String.valueOf(endSeq));
        p.put("finalReply", finalReply == null ? "" : finalReply);
        ObjectNode ext = Json.obj().put("persist", false);
        return log.append(Events.ROUND_CLOSED, p, mainAgentId, ext).seq();
    }

    /** 正文流(瞬态,不落盘;主/子同名,agentId 决定归属;同轮共享轮 seq)。 */
    public long delta(String agentId, String text) {
        ObjectNode p = Json.obj();
        p.put("text", text);
        return log.append(roundSeq(agentId), Events.DELTA, p, agentId, null).seq();
    }

    /** 思考流(瞬态,不落盘;主/子同名;同轮共享轮 seq)。 */
    public long thinking(String agentId, String text) {
        ObjectNode p = Json.obj();
        p.put("text", text);
        return log.append(roundSeq(agentId), Events.THINKING, p, agentId, null).seq();
    }

    /** 完成一轮:完整思考 + 正文 + 工具调用下发(真实 id)。落盘的权威记录(主/子同名)。 */
    public long message(String agentId, String thinking, String text, List<ToolCallPart> toolCalls) {
        return appendMessage(agentId, thinking, text, toolCalls);
    }

    private long appendMessage(String agentId, String thinking,
            String text, List<ToolCallPart> toolCalls) {
        ObjectNode p = Json.obj();
        if (thinking != null && !thinking.isEmpty()) {
            p.put("thinking", thinking);
        }
        p.put("text", text == null ? "" : text);
        if (toolCalls != null && !toolCalls.isEmpty()) {
            var arr = p.putArray("toolCalls");
            for (ToolCallPart tc : toolCalls) {
                ObjectNode o = arr.addObject();
                o.put("id", tc.id());
                o.put("name", tc.name());
                o.put("arguments", tc.arguments() == null ? "" : tc.arguments());
            }
        }
        return log.append(takeRoundSeq(agentId), Events.MESSAGE, p, agentId, null).seq();
    }

    // ---- 同轮共享 seq(一轮 = 一次模型响应回合:流式 chunk + 定型 message)----

    /**
     * 取(或分配)该 agent 当前轮的共享 seq:首个流式 chunk 到达时现场分配雪花 ID,
     * 该轮后续 delta/thinking 沿用;message 完成后再由 {@link #takeRoundSeq} 清空。
     */
    private long roundSeq(String agentId) {
        return roundSeqs.computeIfAbsent(keyOf(agentId), k -> SnowflakeId.next());
    }

    /**
     * 取当前轮共享 seq 并清空(轮收口):message 之后下一轮重新分配。无流式 chunk
     * (纯工具轮 / 直接定型)时返回 null → 现场分配一个独立轮 seq。
     */
    private long takeRoundSeq(String agentId) {
        Long seq = roundSeqs.remove(keyOf(agentId));
        return seq == null ? SnowflakeId.next() : seq;
    }

    /**
     * 轮次 key:空 agentId(主 agent 可能传空串或 mainAgentId)归并到 mainAgentId,
     * 保证主 agent 两种写法共享同一轮 seq、不误开第二轮;子 agent 恒非空各自独立。
     */
    private String keyOf(String agentId) {
        if (agentId == null || agentId.isEmpty()) {
            return mainAgentId == null || mainAgentId.isEmpty() ? "\u0000main" : mainAgentId;
        }
        return agentId;
    }

    /**
     * 单轮模型调用实测 usage(agentId 为主/子 agent 真实 Id)。
     * round = 本轮输入/输出;total = 该 agent 累计;contextWindowTokens 由调用方兜底
     * (快照未配置窗口时回退 ContextOverflow.DEFAULT_CONTEXT_WINDOW_TOKENS,不出现缺省 null)。
     */
    public long usage(String agentId, String model, Long contextWindowTokens, Usage round, Usage total) {
        ObjectNode p = Json.obj();
        if (model != null) {
            p.put("model", model);
        }
        if (contextWindowTokens != null) {
            p.put("contextWindowTokens", contextWindowTokens);
        }
        p.set("round", Json.toJson(round));
        p.set("total", Json.toJson(total));
        return log.append(Events.USAGE, p, agentId, null).seq();
    }

    /**
     * 工具返回(落盘;callId 与 message.toolCalls[].id 配对,name 供冷启动重建 ToolResponseMessage)。
     */
    public long toolResult(String callId, String name, String summary, boolean truncated, String agentId) {
        ObjectNode p = Json.obj();
        p.put("callId", callId);
        if (name != null && !name.isEmpty()) {
            p.put("name", name);
        }
        p.put("summary", summary);
        if (truncated) {
            p.put("truncated", true);
        }
        return log.append(Events.TOOL_RESULT, p, agentId, null).seq();
    }

    public long askCreate(String askId, String kind, List<PendingAsks.AskQuestion> questions,
            Long timeoutMs, String agentId) {
        ObjectNode p = Json.obj();
        p.put("askId", askId);
        p.put("kind", kind);
        p.set("questions", questionsToJson(questions));
        if (timeoutMs != null) {
            p.put("timeoutMs", timeoutMs);
        }
        return log.append(Events.ASK_CREATE, p, agentId, null).seq();
    }

    public long askState(String askId, String kind, String status,
            List<PendingAsks.AskQuestion> questions, String agentId) {
        ObjectNode p = Json.obj();
        p.put("askId", askId);
        p.put("kind", kind);
        p.put("status", status);
        p.set("questions", questionsToJson(questions));
        return log.append(Events.ASK_STATE, p, agentId, null).seq();
    }

    /** 多问题选择题 → 前端 ask.create/ask.state 的 questions 数组(含 id/prompt/options)。 */
    private static tools.jackson.databind.node.ArrayNode questionsToJson(
            List<PendingAsks.AskQuestion> questions) {
        var arr = Json.arr();
        if (questions != null) {
            for (PendingAsks.AskQuestion q : questions) {
                ObjectNode o = Json.obj();
                o.put("id", q.id());
                o.put("prompt", q.prompt());
                o.set("options", Json.toJson(q.options()));
                arr.add(o);
            }
        }
        return arr;
    }

    public long askResolved(String askId, String by, String status, String agentId) {
        ObjectNode p = Json.obj();
        p.put("askId", askId);
        p.put("by", by);
        p.put("status", status);
        return log.append(Events.ASK_RESOLVED, p, agentId, null).seq();
    }

    /** 子 agent spawn 生命周期(agent.started;只对子 agent 发,必带 agentId)。 */
    public long agentStarted(String agentId, String title, String input) {
        ObjectNode p = Json.obj();
        p.put("agentId", agentId);
        p.put("title", title == null ? "" : title);
        p.put("input", input);
        return log.append(Events.AGENT_STARTED, p, agentId, null).seq();
    }

    public long agentDone(String agentId, String result, Usage usage) {
        ObjectNode p = Json.obj();
        p.put("agentId", agentId);
        p.put("result", result);
        p.set("usage", Json.toJson(usage));
        return log.append(Events.AGENT_DONE, p, agentId, null).seq();
    }

    /**
     * 主/子统一状态事件(持久;agent 列表状态机唯一事件源)。
     * status:running / waiting-user / done / failed / stopped。
     * payload 不写 agentId(与 message/delta 同构):主 agent 由 record.agentId == mainAgentId
     * 经 wireEvent 剥离 → 前端以缺省识别主线程;子 agent 由 wireEvent 注入 payload.agentId。
     */
    public long agentStatus(String agentId, String status) {
        ObjectNode p = Json.obj();
        p.put("status", status);
        return log.append(Events.AGENT_STATUS, p, agentId, null).seq();
    }

    /** 失败记录(主/子同名;agentId = mainAgentId 即任务级失败,子 agent id 即该子失败)。 */
    public long error(String agentId, String message) {
        ObjectNode p = Json.obj();
        p.put("message", message);
        return log.append(Events.ERROR, p, agentId, null).seq();
    }

    public long cancelled(String by) {
        ObjectNode p = Json.obj();
        p.put("by", by);
        return log.append(Events.CANCELLED, p, mainAgentId, null).seq();
    }

    /**
     * 一次退避重试即将开始(瞬态,不落盘;前端据此展示「N 秒后重试(第 x/总 次)」)。
     * 同一 agent 一波重试共用一条 task.trace(按 traceId 原地更新);首次失败建 trace,
     * 后续 progress/resolved/exhausted 复用同一 traceId。
     */
    public long retryAttempt(String agentId, int attempt, int maxAttempts, long delayMs, String error) {
        String traceId = retryTraceIds.computeIfAbsent(agentId, k -> ShortIds.next("trace"));
        return appendTrace(traceId, "request_retry", "请求重试",
                retrySummary(attempt, maxAttempts, delayMs, error),
                retryDetail(attempt, maxAttempts, delayMs, 0, delayMs, error),
                "retrying",
                retryMeta(attempt, maxAttempts, delayMs, 0, delayMs, error),
                false);
    }

    /**
     * 退避倒计时刷新(瞬态,不落盘;每秒一条,前端据此实时更新剩余等待秒数)。
     * <b>禁止落盘</b>:每秒写盘会爆磁盘,整波重试的落盘记录由 retryResolved/retryExhausted 各一条承担。
     */
    public long retryProgress(String agentId, int attempt, int maxAttempts, long delayMs,
            long elapsedMs, long remainingMs, String error) {
        String traceId = retryTraceIds.get(agentId);
        if (traceId == null) {
            return -1; // 防御:progress 必在 attempt 之后,缺失则丢弃
        }
        return appendTrace(traceId, "request_retry", "请求重试",
                retrySummary(attempt, maxAttempts, remainingMs, error),
                retryDetail(attempt, maxAttempts, delayMs, elapsedMs, remainingMs, error),
                "retrying",
                retryMeta(attempt, maxAttempts, delayMs, elapsedMs, remainingMs, error),
                false);
    }

    /**
     * 整波瞬时错误重试<b>成功</b>收口(持久化,整波仅此一条落盘;含重试次数与总退避耗时)。
     */
    public long retryResolved(String agentId, int attempts, long totalDelayMs) {
        String traceId = retryTraceIds.remove(agentId);
        if (traceId == null) {
            return -1;
        }
        ObjectNode meta = Json.obj();
        meta.put("attempts", attempts);
        meta.put("totalDelayMs", totalDelayMs);
        meta.put("status", "resolved");
        return appendTrace(traceId, "request_retry", "请求重试已恢复",
                "重试 " + attempts + " 次后已恢复(共退避 " + formatDurationShort(totalDelayMs) + ")",
                null, "resolved", meta, true);
    }

    /**
     * 整波瞬时错误重试<b>耗尽失败</b>收口(持久化,整波仅此一条落盘;含尝试次数与最终错误)。
     * 任务级 error 事件照常由调用方另行发出。
     */
    public long retryExhausted(String agentId, int attempts, int maxAttempts, String error) {
        String traceId = retryTraceIds.remove(agentId);
        if (traceId == null) {
            return -1;
        }
        ObjectNode meta = Json.obj();
        meta.put("attempts", attempts);
        meta.put("maxAttempts", maxAttempts);
        meta.put("error", error == null ? "" : error);
        meta.put("status", "exhausted");
        return appendTrace(traceId, "request_retry", "请求重试失败",
                "重试 " + attempts + " 次后仍失败,已放弃",
                error == null || error.isEmpty() ? null : error, "exhausted", meta, true);
    }

    /**
     * 上下文压缩<b>开始</b>(瞬态,不落盘;同一 traceId 由完成/兜底更新,仿 retry 生命周期):
     * 压缩触发即发一条只含 summary 的 trace(「压缩中」),完成后 {@link #contextCompressDone}
     * 用同一 traceId 更新为「已自动压缩上下文」。只在 summary 承载文案,不填 title/content/metadata。
     */
    public long contextCompressStarted(String agentId, String summary) {
        String traceId = compressionTraceIds.computeIfAbsent(agentId, k -> ShortIds.next("trace"));
        return appendTrace(traceId, "context_compression", null,
                summary == null || summary.isEmpty() ? "正在自动压缩上下文…" : summary,
                null, null, null, false);
    }

    /**
     * 上下文压缩<b>完成/兜底收尾</b>(持久化,与开始 trace 同 traceId;整波仅此一条落盘):
     * 压缩执行完(uncompressed 视图实际生成)后调用,summary 更新为「已自动压缩上下文」。
     */
    public long contextCompressDone(String agentId, String summary) {
        String traceId = compressionTraceIds.remove(agentId);
        if (traceId == null) {
            return -1;
        }
        return appendTrace(traceId, "context_compression", null,
                summary == null || summary.isEmpty() ? "已自动压缩上下文" : summary,
                null, null, null, true);
    }

    // NOTE:本轮用户任务端到端耗时不再发 task_duration trace,由 MeasureDurationAdvisor
    // 回填进 rounds.jsonl(RoundIndexStore.recordDuration),前端折叠标记旁展示。
    // NOTE:本轮文件变更不再发 kind='file_changes' 的 task.trace——轻量摘要内联进 rounds.jsonl
    // 每轮行(fileChanges 字段),全文单独落盘 file-changes/<roundId>.json,经 task.fileChanges RPC 读取。

    /**
     * 模型切换 trace(持久化;kind='model_switch'):用户输入箱切到其它模型后续跑时,
     * 在主线显式标注本轮使用的模型,否则用户看不到"本轮用了哪个模型"(旧任务冻结模型被覆盖)。
     * 仅当用户显式切到了与任务此前冻结 configId 不同的模型时由调用方触发。
     *
     * @param snapshot     本轮实际生效的模型快照(已解析)
     * @param oldConfigId  任务此前冻结的 configId(空=旧任务无冻结模型)
     */
    public long modelSwitch(ModelSnapshot snapshot, String oldConfigId) {
        ObjectNode meta = Json.obj();
        meta.put("newConfigId", snapshot.configId());
        if (oldConfigId != null && !oldConfigId.isEmpty()) {
            meta.put("oldConfigId", oldConfigId);
        }
        return appendTrace(ShortIds.next("trace"), "model_switch", "模型切换",
                "已切换至 " + modelLabel(snapshot), null, "done", meta, true);
    }

    /**
     * 模型池自动切换 trace(持久化;kind='model_failover'):池模型({@code ModelPoolChatModel},
     * provider=model-pool 配置)当前成员请求异常切换到下一成员时,在主线显式标注这次容灾切换。
     * 同一波连续失败切换(同一次请求内逐个换成员)共用一条 trace:traceId 非空且已建时
     * 把完整切换链「模型1 -> 模型2 -> 模型3」写入 content,summary 为「容灾切换模型:」
     * + 最终成功配置名 + 模型;首次切换(traceId 为空/未建)新建 trace。
     * 不填 title。返回实际使用的 traceId,供同波后续切换复用。
     *
     * @param traceId  同波已建 traceId(连续切换传入);null/空或已收口则新建
     * @param snapshot 切到的池内模型快照(已解析)
     */
    public String modelFailoverSwitch(String traceId, ModelSnapshot snapshot) {
        String modelDesc = snapshot.configId() + " " + snapshot.model();
        String id;
        String chain;
        String prev = traceId == null ? null : failoverTraceChains.get(traceId);
        if (prev == null) {
            id = (traceId == null || traceId.isEmpty()) ? ShortIds.next("trace") : traceId;
            chain = modelDesc;
        } else {
            id = traceId;
            chain = prev + " -> " + modelDesc;
        }
        failoverTraceChains.put(id, chain);
        appendTrace(id, "model_failover", null, "容灾切换模型：" + modelDesc, chain, "done", null, true);
        return id;
    }

    /**
     * 容灾切换波收口:移除该 traceId 的链状态。一次模型调用链(同一波连续切换)结束后调用,
     * 之后新的容灾切换重新新建 trace,不误追加到旧链。
     */
    public void modelFailoverClose(String traceId) {
        if (traceId != null) {
            failoverTraceChains.remove(traceId);
        }
    }

    /**
     * AI 安全审议结论 trace(持久化;kind='auth.review',plan-unattended-ai-auth 步骤 4/5)。
     * 授权走 {@code AiAuthReviewer} 时由审议组件在一次审议结束后调用:每次授权请求一条,
     * 落盘主 agent jsonl 供事后审计追溯;审议链路经重试 advisor 与池模型自然发出的
     * retry.resolved / retry.exhausted / model_failover trace 同样落盘同入审计
     * (retry.progress 维持瞬态不落盘,与主链一致)。
     *
     * @param agentId    审议 agentId(形如 review-&lt;shortId&gt;)
     * @param decision   审议结论:ALLOW / DENY / ESCALATE
     * @param confidence 置信度(0~1 或模型原始字符串)
     * @param reason     结论说明(异常/超时回退时附 error/timeout 原因)
     * @param scope      ALLOW → "run"(按 RUN 档授权);DENY/ESCALATE → "deny"
     * @param grantKey   被审议的授权 key(如 p::read::… / c::verb)
     * @param prompt     授权请求原文(prompt 可能含敏感授权信息,但计划要求落盘供审计)
     * @param taskId     被授权任务 id
     */
    public long authReview(String agentId, String decision, String confidence, String reason,
            String scope, String grantKey, String prompt, String taskId) {
        ObjectNode meta = Json.obj();
        meta.put("decision", decision == null ? "" : decision);
        meta.put("confidence", confidence == null ? "" : confidence);
        meta.put("reason", reason == null ? "" : reason);
        meta.put("scope", scope == null ? "" : scope);
        meta.put("grantKey", grantKey == null ? "" : grantKey);
        meta.put("prompt", prompt == null ? "" : prompt);
        meta.put("taskId", taskId == null ? "" : taskId);
        meta.put("agentId", agentId == null ? "" : agentId);
        return appendTrace(ShortIds.next("trace"), "auth.review", "AI 安全审议",
                "审议结果：" + (decision == null ? "" : decision), null, "done", meta, true);
    }

    /** 模型可读标签(厂商 · 模型;缺模型用 configId 兜底)。 */
    private static String modelLabel(ModelSnapshot s) {
        if (s == null) {
            return "(未知模型)";
        }
        String provider = s.provider() == null ? "" : s.provider();
        String model = s.model() == null ? "" : s.model();
        if (!model.isEmpty()) {
            return (provider.isEmpty() ? "" : provider + " · ") + model;
        }
        return s.configId() == null ? "" : s.configId();
    }

    /** 统一 trace 事件出口:payload 与前端 TaskTraceRecord 同形;persist=false 经 ext 标记为瞬态。 */
    private long appendTrace(String traceId, String kind, String title, String summary,
            String content, String status, JsonNode metadata, boolean persist) {
        ObjectNode p = Json.obj();
        p.put("traceId", traceId);
        p.put("kind", kind);
        p.put("title", title == null ? "" : title);
        if (summary != null && !summary.isEmpty()) {
            p.put("summary", summary);
        }
        if (content != null && !content.isEmpty()) {
            p.put("content", content);
        }
        if (status != null) {
            p.put("status", status);
        }
        p.put("createdAt", System.currentTimeMillis());
        if (metadata != null && !metadata.isEmpty()) {
            p.set("metadata", metadata);
        }
        ObjectNode ext = persist ? null : Json.obj().put("persist", false);
        return log.append(Events.TASK_TRACE, p, mainAgentId, ext).seq();
    }

    /** 收起态摘要:重试进行中的「第 x/总 次 · y 秒后重试」文案。 */
    private static String retrySummary(int attempt, int maxAttempts, long remainingMs, String error) {
        String countdown = remainingMs > 0 ? formatDurationShort(remainingMs) + " 后重试" : "即将重试";
        return "第 " + attempt + "/" + maxAttempts + " 次失败," + countdown;
    }

    /** 展开态正文:错误原文 + 重试参数。 */
    private static String retryDetail(int attempt, int maxAttempts, long delayMs,
            long elapsedMs, long remainingMs, String error) {
        StringBuilder sb = new StringBuilder();
        sb.append("attempt: ").append(attempt).append('/').append(maxAttempts)
                .append(", delayMs: ").append(delayMs)
                .append(", elapsedMs: ").append(elapsedMs)
                .append(", remainingMs: ").append(remainingMs);
        if (error != null && !error.isEmpty()) {
            sb.append('\n').append(error);
        }
        return sb.toString();
    }

    private static ObjectNode retryMeta(int attempt, int maxAttempts, long delayMs,
            long elapsedMs, long remainingMs, String error) {
        ObjectNode meta = Json.obj();
        meta.put("attempt", attempt);
        meta.put("maxAttempts", maxAttempts);
        meta.put("delayMs", delayMs);
        meta.put("elapsedMs", elapsedMs);
        meta.put("remainingMs", remainingMs);
        meta.put("error", error == null ? "" : error);
        meta.put("status", "retrying");
        return meta;
    }

    /** 把毫秒格式化为简短的「Xs」/「XmY s」英文风格文案(分隔线式 trace 标题)。 */
    private static String formatDurationShort(long ms) {
        long totalSeconds = Math.max(1, Math.round((double) ms / 1000));
        if (totalSeconds < 60) {
            return totalSeconds + "s";
        }
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return seconds == 0 ? (minutes + "m") : (minutes + "m" + seconds + "s");
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
