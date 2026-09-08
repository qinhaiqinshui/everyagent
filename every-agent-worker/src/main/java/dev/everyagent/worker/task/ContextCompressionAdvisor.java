package dev.everyagent.worker.task;

import dev.everyagent.worker.config.WorkerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import dev.everyagent.worker.proto.TaskDtos.Usage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文压缩 advisor(架构 §5.8 压缩落地 + 红线:一个 advisor 只负责一个功能)。
 *
 * <p>挂在工具循环<b>最内层</b>(order = {@link ToolCallingAdvisor#DEFAULT_ORDER} + 400,
 * 位于重试 advisor 更内层),每轮模型请求前执行一次:
 * <ul>
 *   <li><b>事前检查</b>(不等待模型报错):估算当前 {@code prompt.instructions} 用量
 *       (UTF-8 bytes/3 + 工具预留),&gt; 触发阈值(窗口 × triggerRatio × safetyRatio,
 *       默认 95%)即触发压缩;</li>
 *   <li>调用 {@link ContextCompressor} 三阶段压缩到 ≤ 目标阈值(默认 50%),返回改写后的
 *       instructions(压缩只是不进上下文,内存 conversation 与磁盘事件日志始终全量,数据不删除);</li>
 *   <li>压缩命中即打 INFO 日志(before/after/阶段/丢弃量),便于定位。</li>
 * </ul>
 * {@link ToolCallingAdvisor} 的每一轮递归(含工具结果提交后的下一轮)都会经过最内层 advisor,
 * 天然满足「每次工具结果提交前都计算一次上下文用量」;风暴压缩只改写发给模型的 instructions,
 * 工具循环、事件发射、工具执行(用完整 fullTurnHistory)均不受影响。
 *
 * <p>设计纪律:per-run 物化(持 {@link AgentEntity} 仅读快照与日志归属),无跨轮可变状态,
 * 多任务并发安全。阈值与配置取自 {@link WorkerProperties.Limits}(缺省已给出合理默认)。
 */
public class ContextCompressionAdvisor implements CallAdvisor, StreamAdvisor {

    private static final Logger log = LoggerFactory.getLogger(ContextCompressionAdvisor.class);

    private final AgentEntity a;
    private final WorkerProperties.Limits limits;
    private final ContextSummarizer summarizer;

    /** 上轮实际发给模型的指令视图(含注入的提示),供下一轮 offset 校准。 */
    private List<Message> lastSent;
    /** 累积压缩基线;null = 尚未压缩过。 */
    private List<Message> baseline;
    /** 已吸收进 baseline 的「完整历史」源消息条数(下标)。 */
    private int absorbed;

    public ContextCompressionAdvisor(AgentEntity a, WorkerProperties.Limits limits, ContextSummarizer summarizer) {
        this.a = a;
        this.limits = limits;
        this.summarizer = summarizer;
    }

    @Override
    public String getName() {
        return "Context Compression Advisor";
    }

    /** 最内层:每轮模型调用前(含工具循环递归的每一轮)都执行,晚于换模型容灾。 */
    @Override
    public int getOrder() {
        return ToolCallingAdvisor.DEFAULT_ORDER + 400;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        return chain.nextCall(maybeCompress(request));
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return chain.nextStream(maybeCompress(request));
    }

    /** 检查并压缩;未触发/无需改写时也统一走「状态化发送视图」构造,保证 lastSent 口径一致。 */
    private ChatClientRequest maybeCompress(ChatClientRequest request) {
        if (request == null || request.prompt() == null) {
            return request;
        }
        if (!limits.isContextCompressionEnabled()) {
            return request;
        }
        List<Message> messages = request.prompt().getInstructions();
        if (messages == null || messages.isEmpty()) {
            return request;
        }

        List<Message> full = request.prompt().getInstructions();
        long window = contextWindowTokens();
        long trigger = ratio(window, limits.getContextTriggerRatio(), limits.getContextSafetyRatio());
        long target = ratio(window, limits.getContextTargetRatio(), limits.getContextSafetyRatio());
        long reserve = limits.getContextToolReserveTokens();
        int maxToolChars = limits.getContextMaxToolResultChars();

        // offset 校准:用「上轮实测 inputTokens - 上轮估算 lastSent」作为隐藏开销,校正
        // tokenizer 差异/注入提示/服务商计数误差;首轮、无实测或换模型时回退为固定 reserve。
        long overhead = reserve;
        if (limits.isContextOffsetEnabled()) {
            Usage last = a.lastRound();
            String lastModel = a.lastModel();
            String currentModel = a.options == null ? null : a.options.getModel();
            if (last != null && last.inputTokens() > 0
                    && lastSent != null && !lastSent.isEmpty()
                    && lastModel != null && !lastModel.isEmpty()
                    && currentModel != null && !currentModel.isEmpty()
                    && lastModel.equals(currentModel)) {
                long offset = last.inputTokens() - ContextCompressor.estimateTokens(lastSent);
                if (offset > 0) {
                    overhead = Math.max(reserve, offset);
                }
            }
        }

        // 构造发送前视图:未压缩过 = 完整历史(仅截断超大工具结果);压缩过 = 累积基线 + 本轮新增。
        List<Message> working;
        if (baseline == null) {
            working = ContextCompressor.truncateHugeToolResults(full, maxToolChars);
        } else {
            List<Message> delta = full.subList(absorbed, full.size());
            delta = ContextCompressor.truncateHugeToolResults(delta, maxToolChars);
            working = new ArrayList<>(baseline);
            working.addAll(delta);
        }

        long used = ContextCompressor.estimateTokens(working) + overhead;

        List<Message> toSend;
        if (used <= trigger) {
            toSend = working;
        } else {
            // 压缩中 trace(只含 summary;进行中瞬态,完成后同 traceId 更新为持久)
            a.task.events.contextCompressStarted(a.agentId,
                    "正在自动压缩上下文…(当前约 " + used + " token)");

            ContextSummarizer sum = limits.isContextSummaryEnabled() ? summarizer : null;
            ContextCompressor.Result r = ContextCompressor.compress(working, trigger, target, overhead, sum);
            if (!r.compressed()) {
                toSend = working;
                a.task.events.contextCompressDone(a.agentId, "已自动压缩上下文(无需改写)");
            } else {
                toSend = r.messages();
                baseline = new ArrayList<>(r.messages());
                absorbed = full.size();
                long after = ContextCompressor.estimateTokens(toSend) + overhead;
                a.task.events.contextCompressDone(a.agentId,
                        "已自动压缩上下文(" + stageName(r.stage()) + ", 消息 " + working.size() + "→"
                                + toSend.size() + ", 约 " + used + "→" + after + " token 估算)");
                log.info("任务 {} agent {} 上下文压缩: used={} trigger={} target={} messages {}→{} 阶段={} "
                                + "丢历史轮={} 丢本轮工具对={}",
                        a.task.taskId, a.agentId, used, trigger, target, working.size(), toSend.size(),
                        stageName(r.stage()), r.droppedHistoryTurns(), r.droppedCurrentToolPairs());
                if (after > target) {
                    log.warn("任务 {} agent {} 上下文压缩后仍 > 目标阈值(本轮单 turn 过大),交由模型上限兜底",
                            a.task.taskId, a.agentId);
                }
            }
        }

        // 压缩后注入提示:一旦发生过压缩,每轮都提示模型不要凭记忆编造早期细节。
        List<Message> finalView;
        if (baseline != null) {
            SystemMessage hint = new SystemMessage(
                    "【系统提示】早期上下文已被自动压缩，需要之前的具体细节时请通过工具重新读取/搜索，不要凭空猜测。");
            finalView = new ArrayList<>();
            finalView.add(hint);
            finalView.addAll(toSend);
        } else {
            finalView = toSend;
        }

        // 记录上轮实际发送视图(含注入提示),供下一轮 offset 校准与 provider 实测对账。
        lastSent = new ArrayList<>(finalView);

        return ChatClientRequest.builder()
                .prompt(new Prompt(finalView, request.prompt().getOptions()))
                .context(request.context())
                .build();
    }

    private long contextWindowTokens() {
        JsonNode params = a.task.snapshot == null ? null : a.task.snapshot.params();
        if (params != null && params.isObject() && params.has("contextWindowTokens")) {
            long v = params.path("contextWindowTokens").asLong(0);
            if (v > 0) {
                return v;
            }
        }
        return ContextOverflow.DEFAULT_CONTEXT_WINDOW_TOKENS;
    }

    /** floor(window × ratio × safetyRatio)。 */
    private static long ratio(long window, double ratio, double safety) {
        return (long) Math.floor(window * ratio * safety);
    }

    private static String stageName(int stage) {
        return switch (stage) {
            case 1 -> "A(历史轮去工具调用)";
            case 2 -> "B(本轮前50%工具调用)";
            case 3 -> "C(删最旧历史轮)";
            default -> "无";
        };
    }
}