package dev.everyagent.worker.agent;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.event.EventPayloads;
import dev.everyagent.plugin.api.model.EmitEvent;
import dev.everyagent.worker.agent.AgentEntity;
import dev.everyagent.plugin.api.config.WorkerConfig;
import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.plugin.api.model.ModelConfig;
import dev.everyagent.plugin.api.event.Events;
import dev.everyagent.plugin.api.event.Events.ToolCallPart;
import dev.everyagent.plugin.api.proto.SnowflakeId;
import dev.everyagent.plugin.api.event.Usage;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import reactor.core.publisher.Flux;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * worker 事件发射 advisor(架构 §5.2 + 红线:一个 advisor 只负责一个功能)。
 *
 * <p>继承 {@link ToolCallingAdvisor},复用其递归工具循环(绝不手搓);仅重写受保护 hook,
 * 在模型流的关键节点把 worker 事件协议({@code TaskEvents})发射出去——不改动循环逻辑本身:
 * <ul>
 *   <li>{@link #adviseStream}:逐 chunk 旁路发瞬态 {@code delta}(正文增量,真逐字流)
 *       + 瞬态 {@code thinking}(思考差分,同频逐字流;Spring AI 2.0.1 的
 *       OpenAiChatModel 在每个 chunk 的 metadata 里带 reasoningContent 累积值,
 *       与 doAfterStream 共用 thinkingAcc 差分取增量,工具轮被 filter 的帧由
 *       doAfterStream 兜底)。</li>
 *   <li>{@link #doAfterStream}:每轮模型响应聚合完成后,发 {@code message}
 *       (完整思考+正文+工具调用下发,真实 id,落盘权威记录;瞬态 delta/thinking 已在
 *       {@link #adviseStream} 逐 chunk 发出,权威值由前端替换定稿;思考差分在此仅兜底
 *       acc 未追平的部分) + 实测 {@code usage};事件名不分主/子,归属由 agentId 决定;
 *       并把本轮正文写入 {@link AgentEntity#lastText}(最终轮即全文)。</li>
 *   <li>工具执行侧事件({@code tool.started}/{@code tool.result})<b>不在本类</b>:
 *       已迁 per-call 工具执行拦截链的发射节点({@code ToolEventInterceptor},§7.14.3)
 *       ——本类回归纯模型流事件;原 {@code doGetNextInstructionsForToolCallStream} 从
 *       {@code conversationHistory} 回扫本轮结果并按「名字+入参」查账的发射段已删除
 *       (框架 hook 保留默认转发,递归循环不受影响)。</li>
 * </ul>
 *
 * <p>设计纪律:本类不持有任何 per-task 可变状态(仅持有 {@link AgentEntity} 引用,运行期只读其
 * 不可变字段、仅写 {@code lastText} 终值),多任务并发复用同一 {@link ToolCallingManager} 单例时,
 * 事件发射与工具循环均线程安全。
 *
 * <p>注意:Spring AI 流式 {@code ToolCallingAdvisor} 的聚合是"旁路"的——
 * {@code MessageAggregator} 只在透传 chunk 的 doOnNext/doOnComplete 上搭桥,聚合值
 * 喂给 {@link #doAfterStream}(每轮一次,含全文),原始 chunk 照常流向下游。故在
 * {@link #adviseStream} 对 super 输出 tap 即得真逐字流(每 chunk 一条瞬态
 * {@code delta} 与思考差分 {@code thinking}),工具循环仍完全由框架驱动;工具轮的
 * 聚合帧已被框架 filter 挡在 tap 之外,不会重复下发。
 */
public class WorkerToolEventAdvisor extends ToolCallingAdvisor {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WorkerToolEventAdvisor.class);

    private final AgentEntity a;
    /** 思考累积值追踪(跨轮差分,防串轮;adviseStream tap 与 doAfterStream 共用)。 */
    private final AtomicReference<String> thinkingAcc = new AtomicReference<>("");
    /** 本轮事件 id(雪花):每轮模型响应首 chunk 分配,delta/thinking/message 共用;message 后清零。 */
    private final AtomicLong roundId = new AtomicLong(0);
    /**
     * 本轮思考计时起点(首个思考增量到达时刻,毫秒;每轮 message 后清零)。
     * 前端思考块的实时读秒基准即此值(与 tool.started 的 startedAt 同口径:宿主时钟毫秒),
     * 随权威 message 事件下发,历史回放也能还原思考耗时。
     */
    private final AtomicLong thinkingStartMs = new AtomicLong(0);
    /** 本轮末个思考增量到达时刻(毫秒;与起点差值 = 思考阶段真实流式耗时,不含随后正文生成/工具执行)。 */
    private final AtomicLong thinkingEndMs = new AtomicLong(0);

    public WorkerToolEventAdvisor(ToolCallingManager toolCallingManager, AgentEntity a) {
        super(toolCallingManager, DEFAULT_TOOL_EXECUTION_ELIGIBILITY_CHECKER, DEFAULT_ORDER, true);
        this.a = a;
    }

    /**
     * 逐 chunk 旁路发瞬态 {@code delta} + {@code thinking}(真逐字流):super 的输出
     * Flux 就是模型原始 chunk(含工具循环递归各轮;工具轮携带 toolCalls 的聚合帧已被
     * 框架 filter,不会到此处)。每轮 chunk 先于 {@link #doAfterStream} 的权威
     * {@code message} 下发,前端先聚合流式锚点、轮末权威替换。
     * 尾部 usage 修正帧 generations 为空,getResult() 为 null,空守卫天然跳过。
     */
    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest,
            StreamAdvisorChain streamAdvisorChain) {
        return super.adviseStream(chatClientRequest, streamAdvisorChain)
                .doOnNext(this::emitDeltas);
    }

    /**
     * 单个 chunk 的增量发射:正文增量 → 瞬态 {@code delta};思考累积值差分 → 瞬态
     * {@code thinking}。空文本/空帧跳过;事件占 seq 不落盘(见 TaskStore.PERSIST_SKIP)。
     *
     * <p>正文 {@code getText()} 是 chunk 自身增量,直接发;而 Spring AI 2.0.1 的
     * OpenAiChatModel.internalStream 自行把 reasoningContent 按 completion 累积拼接
     * (reasoningMap.merge(String::concat)),metadata 恒为「到当前 chunk 为止的累积值」,
     * 故思考侧必须与 doAfterStream 共用同一 thinkingAcc 做差分(前缀增长取增量、
     * 非前缀重置防串轮)才能得到与 delta 同频的逐字流。工具轮被 filter 掉的帧其思考
     * 增量由 doAfterStream 差分兜底(acc 未追平),message 事件的完整 thinking 再由
     * 前端替换定稿。
     */
    private void emitDeltas(ChatClientResponse chunk) {
        ChatResponse cr = chunk.chatResponse();
        if (cr == null || cr.getResult() == null || cr.getResult().getOutput() == null) {
            return;
        }
        // 防御:执行主体已收口(用户取消/finish 完成)时不再发射瞬态事件。
        // 即使 reactive 链的 dispose 有微秒级竞态窗口,残留 chunk 也不会泄漏到前端。
        // 域中性纪律:经 ExecContext.terminal() 槽位判收口,不感知 task 域类型(§7.20)。
        if (a.execution().terminal()) {
            return;
        }
        AssistantMessage out = cr.getResult().getOutput();
        long rid = ensureRoundId();
        String piece = out.getText();
        if (piece != null && !piece.isEmpty()) {
            a.emitter.emit(EmitEvent.transientOf(rid, Events.DELTA,
                    null, null, null, piece, null, null, EmitEvent.Mode.APPEND));
        }
        String thinking = thinkingOf(out);
        if (!thinking.isEmpty()) {
            String diff = diffThinking(thinking);
            if (!diff.isEmpty()) {
                touchThinking();
                a.emitter.emit(EmitEvent.transientOf(rid, Events.THINKING,
                        null, null, null, diff, null, null, EmitEvent.Mode.APPEND));
            }
        }
    }

    /**
     * 记录思考增量到达时刻:首个增量定起点(compareAndSet 抢 0),每个增量推进终点。
     * 起点/终点同取宿主时钟,差值即本轮思考的流式耗时;起点随权威 {@code message}
     * 事件下发({@code thinkingStartedAt}/{@code thinkingDurationMs}),供前端实时读秒
     * 与历史回放展示(与 tool.started/tool.result 的 startedAt/durationMs 同口径)。
     */
    private void touchThinking() {
        long now = System.currentTimeMillis();
        thinkingStartMs.compareAndSet(0, now);
        thinkingEndMs.set(now);
    }

    @Override
    protected ChatClientResponse doAfterStream(ChatClientResponse chatClientResponse,
            org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain streamAdvisorChain) {
        ChatResponse cr = chatClientResponse.chatResponse();
        if (cr == null || cr.getResult() == null) {
            return chatClientResponse;
        }
        AssistantMessage out = cr.getResult().getOutput();
        if (out == null) {
            return chatClientResponse;
        }
        // 防御:执行主体已收口时不再发射任何事件(取消后 dispose 与 reactor 线程间有竞态窗口,
        // 残留轮次在此直接丢弃,不再产生 message/usage 等落盘事件,也不写入 lastText)。
        // 域中性纪律:经 ExecContext 槽位取收口态与主体 ID,不感知 task 域类型(§7.20)。
        ExecContext exec = a.execution();
        if (exec.terminal()) {
            log.warn("[leak-guard] 执行主体已收口(subject={}),拦截轮次事件发射 agentId={} thread={}",
                    exec.subjectId(), a.agentId, Thread.currentThread().getName());
            return chatClientResponse;
        }
        log.debug("[advisor] doAfterStream 发射轮次 agentId={} subject={} hasToolCalls={} thread={}",
                a.agentId, exec.subjectId(),
                out.getToolCalls() != null && !out.getToolCalls().isEmpty(),
                Thread.currentThread().getName());
        long rid = ensureRoundId();
        // 文本(逐字 delta 已在 adviseStream tap 逐 chunk 发出;此处只记终值供收口/子 agent 结果用)
        String text = out.getText() == null ? "" : out.getText();
        if (!text.isEmpty()) {
            a.lastText = text;
        }
        // 思考差分(整轮累积值取新增段):逐 chunk 已在 adviseStream tap 差分发出,
        // 此处仅兜底工具轮被 filter 帧的增量(acc 未追平的部分),正常轮次 diff 为空。
        String thinking = thinkingOf(out);
        if (!thinking.isEmpty()) {
            String diff = diffThinking(thinking);
            if (!diff.isEmpty()) {
                touchThinking();
                a.emitter.emit(EmitEvent.transientOf(rid, Events.THINKING,
                        null, null, null, diff, null, null, EmitEvent.Mode.APPEND));
            }
        }
        // 最近活动快照(§5.6,list_agents/wait_agents 契约的 latestActivity):
        // 每轮把 reasoning/content 合并写回实体,主 agent 等待/列表时即见最新轮次进展。
        if (!text.isEmpty() || !thinking.isEmpty()) {
            a.updateActivity(thinking.isEmpty() ? null : thinking, text.isEmpty() ? null : text, null);
        }
        // 工具调用视图(真实 id,随 message 下发;message.toolCalls 是权威)
        List<AssistantMessage.ToolCall> calls =
                out.getToolCalls() == null ? List.of() : out.getToolCalls();
        List<ToolCallPart> parts = new ArrayList<>();
        for (AssistantMessage.ToolCall tc : calls) {
            parts.add(new ToolCallPart(tc.id(), tc.name(), tc.arguments() == null ? "" : tc.arguments()));
        }
        // 落盘权威轮次记录(主/子同名,归属由 agentId 决定)。
        // 顺序纪律:message 必须最先于 usage 发——本轮 delta/thinking/message 共享
        // 同一轮 seq,若 usage 的独立雪花 seq 先到,前端折叠器(同轮同 seq,其余按 seq 递增)
        // 会把随后到达的 message(seq 较小)判为旧轮丢弃,权威定型帧丢失。
        ObjectNode messageData = Json.obj();
        if (!thinking.isEmpty()) {
            messageData.put("thinking", thinking);
            // 思考耗时口径(与 tool.result 的 startedAt/durationMs 同构):起点 = 本轮首个
            // 思考增量到达时刻,时长 = 末个增量 - 首个增量(思考阶段真实流式耗时,不含随后的
            // 正文生成/工具执行)。随权威 message 落盘,历史回放即可还原思考耗时。
            long tStart = thinkingStartMs.get();
            if (tStart > 0) {
                messageData.put("thinkingStartedAt", tStart);
                messageData.put("thinkingDurationMs", Math.max(0, thinkingEndMs.get() - tStart));
            }
        }
        messageData.set("toolCalls", EventPayloads.toolCallsToJson(parts));
        a.emitter.emit(EmitEvent.of(rid, Events.MESSAGE,
                null, null, null, text, null, messageData, EmitEvent.Mode.REPLACE));
        roundId.set(0);
        thinkingStartMs.set(0);
        thinkingEndMs.set(0);
        // 队列续跑的最终回答回写已归位 task 层:consumeInput 消费下一条排队输入前,经
        // ConversationLoader.catchUpRuntime 从本事件流追回(与冷启动重建同一谓词)。
        // 本 advisor 至此不再触碰会话内存,只负责事件发射与 agent 层会计(§7.20 域中性)。
        // 实测 usage(末帧携带才发)
        org.springframework.ai.chat.metadata.Usage round = cr.getMetadata() == null ? null : cr.getMetadata().getUsage();
        if (log.isDebugEnabled()) {
            log.debug("[CacheToken] WorkerToolEventAdvisor doAfterStream | metadata={} | usage={}",
                    cr.getMetadata() == null ? "null" : "<present>",
                    round == null ? "null" : "{prompt=" + round.getPromptTokens()
                            + ", completion=" + round.getCompletionTokens()
                            + ", cacheRead=" + round.getCacheReadInputTokens() + "}");
        }
        if (round != null) {
            long in = round.getPromptTokens() == null ? 0 : round.getPromptTokens();
            long outTok = round.getCompletionTokens() == null ? 0 : round.getCompletionTokens();
            if (in != 0 || outTok != 0) {
                // 缓存命中 token:先取标准 OpenAI 字段,再兜底厂商自定义字段(DeepSeek 等)
                long cacheRead = CacheTokenExtractor.extract(round, cr.getMetadata());
                log.debug("[CacheToken] 最终提取 cacheRead={} (agent={})", cacheRead, a.agentId);
                Usage roundUsage = new Usage(in, outTok,
                        round.getTotalTokens() == null ? in + outTok : round.getTotalTokens(),
                        cacheRead);
                // 先把本轮累计进实体,再发射 usage:同一帧的 total 即含本轮的真实累计
                // (usage 事件 total 载荷 / 台账 usage / agent.done 用量的共同供体)。
                a.addUsage(round, cacheRead);
                ObjectNode usageData = Json.obj();
                usageData.put("model", a.options.getModel());
                usageData.put("contextWindowTokens", contextWindowTokens());
                // 压缩冷启动种子(§7.5):仅「全量未压缩发送视图」的轮次携带估算(压缩 advisor
                // 记账;压缩视图轮次为 0 不携带——其比率不表征全量历史的估算偏差)。
                long fullViewEst = a.fullViewEstTokens();
                if (fullViewEst > 0) {
                    usageData.put("fullViewEstTokens", fullViewEst);
                }
                log.debug("[CacheToken] 发射 usage 事件 | round={} | total={}",
                        roundUsage, a.usageRef().get());
                usageData.set("round", Json.toJson(roundUsage));
                usageData.set("total", Json.toJson(a.usageRef().get()));
                a.emitter.emit(EmitEvent.of(SnowflakeId.next(), Events.USAGE,
                        null, null, null, null, null, usageData, EmitEvent.Mode.REPLACE));
                // 最近一轮实测 usage 按 agent 记录(主/子都写;供 ContextCompressionAdvisor 读取 offset),
                // 同时保存累计 usage 与上下文快照(台账 usage/context 字段供体)。
                a.recordLastRound(roundUsage, a.usageRef().get(), contextWindowTokens(), a.options.getModel());
                // 任务级记录(最近一轮占用快照 + task.updated 广播)已反转为事件投影:
                // TaskEntry 订阅本事件流,按 usage 事件载荷维护 TaskSummary.usage 供体并触发
                // 广播(§7.20 下层不感知上层操作;本类至此对 task 域类型零依赖,§14.11 达标)。
            }
        }
        return chatClientResponse;
    }

    /**
     * 工具执行后的下一轮指令 hook:<b>本类不再发射任何事件</b>(纯 super 转发,框架
     * 递归循环需要该 hook 继续工作)。{@code tool.started}/{@code tool.result} 已迁
     * per-call 工具执行拦截链的发射节点({@code ToolEventInterceptor},每次调用一条,
     * callId 直配,§7.14.3)。
     */
    @Override
    protected List<Message> doGetNextInstructionsForToolCallStream(ChatClientRequest chatClientRequest,
            ChatClientResponse chatClientResponse, ToolExecutionResult toolExecutionResult) {
        return super.doGetNextInstructionsForToolCallStream(chatClientRequest, chatClientResponse,
                toolExecutionResult);
    }

    /** 上下文窗口口径单点:委托 {@link ModelConfig#contextWindowTokens()}(未配置/非法回退默认窗口)。 */
    private long contextWindowTokens() {
        ModelConfig snapshot = a.execution().snapshot();
        return snapshot == null ? WorkerConfig.DEFAULT_CONTEXT_WINDOW_TOKENS : snapshot.contextWindowTokens();
    }

    /** 从 assistant message metadata 取 reasoningContent 累积值(OpenAI 兼容思考字段)。 */
    private static String thinkingOf(AssistantMessage out) {
        if (out.getMetadata() == null) {
            return "";
        }
        Object rc = out.getMetadata().get("reasoningContent");
        return rc instanceof String s ? s : "";
    }

    /** 思考累积值 → 本处理新增段(非前缀增长时重置追踪,防串轮)。 */
    private String diffThinking(String accumulated) {
        String cur = thinkingAcc.get();
        if (accumulated.equals(cur)) {
            return "";
        }
        if (accumulated.length() > cur.length() && accumulated.startsWith(cur)) {
            String diff = accumulated.substring(cur.length());
            thinkingAcc.set(accumulated);
            return diff;
        }
        thinkingAcc.set(accumulated);
        return accumulated;
    }

    /** 确保本轮已分配雪花 id(轮首 delta/thinking 到来时分配,message 后清零)。 */
    private long ensureRoundId() {
        long rid = roundId.get();
        if (rid == 0) {
            rid = SnowflakeId.next();
            roundId.set(rid);
        }
        return rid;
    }
}
