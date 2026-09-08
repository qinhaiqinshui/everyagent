package dev.everyagent.worker.task;

import dev.everyagent.worker.proto.Events.ToolCallPart;
import dev.everyagent.worker.proto.TaskDtos.Usage;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * worker 事件发射 advisor(架构 §5.2 + 红线:一个 advisor 只负责一个功能)。
 *
 * <p>继承 {@link ToolCallingAdvisor},复用其递归工具循环(绝不手搓);仅重写受保护 hook,
 * 在工具循环的关键节点把 worker 事件协议({@link TaskEvents})发射出去——不改动循环逻辑本身:
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
 *   <li>{@link #doGetNextInstructionsForToolCallStream}:工具执行完毕后,只从结果历史里挑出
 *       「本轮刚下发」(callId 命中本轮 message.toolCalls)的 {@link ToolResponseMessage} 发
 *       {@code tool.result}(落盘,callId 配对),历史轮次结果不重复发射。</li>
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

    /** 工具返回事件侧截断阈值;返回给模型的仍是完整结果(与 AgentRunner 同源语义)。 */
    private static final int MAX_TOOL_EVENT_CHARS = 100_000;

    private final AgentEntity a;
    /** 思考累积值追踪(跨轮差分,防串轮;adviseStream tap 与 doAfterStream 共用)。 */
    private final AtomicReference<String> thinkingAcc = new AtomicReference<>("");

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
        AssistantMessage out = cr.getResult().getOutput();
        String piece = out.getText();
        if (piece != null && !piece.isEmpty()) {
            a.task.events.delta(a.agentId, piece);
        }
        String thinking = thinkingOf(out);
        if (!thinking.isEmpty()) {
            String diff = diffThinking(thinking);
            if (!diff.isEmpty()) {
                a.task.events.thinking(a.agentId, diff);
            }
        }
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
        String agentId = a.agentId;
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
                a.task.events.thinking(agentId, diff);
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
        a.task.events.message(agentId, thinking, text, parts);
        // 队列续跑修复:把「最终回答轮」(无工具调用的收口轮)回写会话内存。
        // 同一次运行内,下一条排队输入会被 consumeInput 直接 append 到 conversation;
        // 若本轮 assistant 回复不回写,模型将看到两条连续 user 消息(a、b)而重复回答上一轮。
        // 工具轮(calls 非空)不回写——避免留下无配对 tool 结果的孤立 assistant 消息。
        // 冷启动(re-run)的历史仍由 ConversationLoader 从磁盘完整重建,此处只补运行期增量。
        if (calls.isEmpty()) {
            a.conversation.add(AssistantMessage.builder()
                    .content(text)
                    .build());
        }
        // 实测 usage(末帧携带才发)
        org.springframework.ai.chat.metadata.Usage round = cr.getMetadata() == null ? null : cr.getMetadata().getUsage();
        if (round != null) {
            long in = round.getPromptTokens() == null ? 0 : round.getPromptTokens();
            long outTok = round.getCompletionTokens() == null ? 0 : round.getCompletionTokens();
            if (in != 0 || outTok != 0) {
                Usage roundUsage = new Usage(in, outTok,
                        round.getTotalTokens() == null ? in + outTok : round.getTotalTokens());
                a.task.events.usage(agentId, a.options.getModel(), contextWindowTokens(), roundUsage,
                        a.usageRef().get());
                // 最近一轮实测 usage 按 agent 记录(主/子都写;供 ContextCompressionAdvisor 读取 offset)
                a.recordLastRound(roundUsage, a.options.getModel());
                // 主 agent:记录最近一轮上下文用量(任务列表/聊天页电池数据源,随 meta 持久化)
                // → 触发任务列表用量实时广播(task.updated,每轮一次)。
                if (a.kind == AgentEntity.Kind.MAIN) {
                    a.task.recordUsage(roundUsage, contextWindowTokens(), a.options.getModel());
                    Runnable broadcast = a.task.onUsageBroadcast;
                    if (broadcast != null) {
                        try {
                            broadcast.run();
                        } catch (RuntimeException e) {
                            // 广播失败不阻塞模型流
                        }
                    }
                }
            }
        }
        return chatClientResponse;
    }

    @Override
    protected List<Message> doGetNextInstructionsForToolCallStream(ChatClientRequest chatClientRequest,
            ChatClientResponse chatClientResponse, ToolExecutionResult toolExecutionResult) {
        // 工具已执行:只发「本轮刚下发」的工具结果(callId 与 message.toolCalls[].id 配对)。
        // 此前遍历整个 conversationHistory 会把历史所有 ToolResponseMessage 逐条重发,
        // 导致同一 callId 的 tool.result 在每轮工具执行后都被再次落盘(前端按 seq 无法去重,
        // 表现为「工具 ×N」的重复孤儿块)。这里以本轮 assistant 消息下发的 toolCall id 为
        // 白名单,只发射匹配项,历史结果一律跳过。白名单为空(拿不到本轮 id 的异常形态)时
        // 保守跳过发射,绝不重放历史,避免再次污染事件流。
        String agentId = a.agentId;
        Set<String> currentCallIds = currentRoundToolCallIds(chatClientResponse);
        if (currentCallIds.isEmpty()) {
            return super.doGetNextInstructionsForToolCallStream(chatClientRequest, chatClientResponse,
                    toolExecutionResult);
        }
        if (toolExecutionResult.conversationHistory() != null) {
            for (Message m : toolExecutionResult.conversationHistory()) {
                if (m instanceof ToolResponseMessage trm) {
                    for (ToolResponseMessage.ToolResponse r : trm.getResponses()) {
                        if (!currentCallIds.contains(r.id())) {
                            continue; // 历史轮次的工具结果:本轮未下发,不重复发射
                        }
                        String summary = r.responseData() == null ? "(无返回)" : r.responseData();
                        boolean truncated = summary.length() > MAX_TOOL_EVENT_CHARS;
                        if (truncated) {
                            summary = summary.substring(0, MAX_TOOL_EVENT_CHARS) + "…(截断)";
                        }
                        a.task.events.toolResult(r.id(), r.name(), summary, truncated, agentId);
                    }
                }
            }
        }
        return super.doGetNextInstructionsForToolCallStream(chatClientRequest, chatClientResponse, toolExecutionResult);
    }

    /**
     * 取本轮 assistant 消息下发的工具调用 id 集合(用于过滤历史重复的 tool.result)。
     * 来源与 {@link #doAfterStream} 发射 message.toolCalls 的同一响应对象一致,保证白名单
     * 覆盖「本轮真正下发的工具」;响应缺失或无法解析时返回空集合(调用方保守跳过发射)。
     */
    private static Set<String> currentRoundToolCallIds(ChatClientResponse chatClientResponse) {
        Set<String> ids = new HashSet<>();
        if (chatClientResponse == null) {
            return ids;
        }
        ChatResponse chatResponse = chatClientResponse.chatResponse();
        if (chatResponse == null || chatResponse.getResult() == null) {
            return ids;
        }
        AssistantMessage out = chatResponse.getResult().getOutput();
        if (out == null || out.getToolCalls() == null) {
            return ids;
        }
        for (AssistantMessage.ToolCall call : out.getToolCalls()) {
            if (call.id() != null && !call.id().isBlank()) {
                ids.add(call.id());
            }
        }
        return ids;
    }

    /** 任务快照 params 里的上下文窗口大小(未配置/非法回退默认窗口,与压缩/超限诊断口径一致)。 */
    private Long contextWindowTokens() {
        JsonNode params = a.task.snapshot.params();
        if (params != null && params.isObject() && params.has("contextWindowTokens")) {
            long v = params.path("contextWindowTokens").asLong(0);
            if (v > 0) {
                return v;
            }
        }
        return ContextOverflow.DEFAULT_CONTEXT_WINDOW_TOKENS;
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
}
