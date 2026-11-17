package dev.everyagent.plugin.contextcompression;

import dev.everyagent.plugin.api.model.EmitEvent;
import dev.everyagent.plugin.api.config.WorkerConfig;
import dev.everyagent.plugin.api.proto.SnowflakeId;
import dev.everyagent.plugin.api.agent.AgentContext;
import dev.everyagent.plugin.api.agent.CompressionSeed;
import dev.everyagent.plugin.api.event.Usage;
import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.plugin.api.slash.SlashTokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

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
 *       默认 95%)即触发压缩;冷启动再运行首个请求尚无运行内加性校准数据时,改用上一
 *       运行持久化的「全量视图 实测/估算」比率折算(§7.5 冷启动种子);</li>
 *   <li>调用 {@link ContextCompressor} 三阶段压缩到 ≤ 目标阈值(默认 50%),返回改写后的
 *       instructions(压缩只是不进上下文,内存 conversation 与磁盘事件日志始终全量,数据不删除);</li>
 *   <li>压缩命中即打 INFO 日志(before/after/阶段/丢弃量),便于定位。</li>
 * </ul>
 * {@link ToolCallingAdvisor} 的每一轮递归(含工具结果提交后的下一轮)都会经过最内层 advisor,
 * 天然满足「每次工具结果提交前都计算一次上下文用量」;风暴压缩只改写发给模型的 instructions,
 * 工具循环、事件发射、工具执行(用完整 fullTurnHistory)均不受影响。
 *
 * <p>设计纪律:per-run 物化(持 {@link AgentContext} 仅读快照与日志归属),无跨轮可变状态,
 * 多任务并发安全。阈值与配置取自 {@link WorkerConfig.Limits}(缺省已给出合理默认)。
 * token 估算口径经构造注入(全局 {@code TokenEstimator} 输入口径),为空回退内置 bytes/3。
 */
public class ContextCompressionAdvisor implements CallAdvisor, StreamAdvisor {

    private static final Logger log = LoggerFactory.getLogger(ContextCompressionAdvisor.class);

    private final AgentContext a;
    private final WorkerConfig.Limits limits;
    private final ContextSummarizer summarizer;
    /** token 估算口径(全局 TokenEstimator 的输入口径;为空回退内置 bytes/3)。 */
    private final ContextCompressor.TextTokens tokens;
    /** 任务级 slash token 只读查询(旧版 bottom 胶囊残留的兼容消费口;为空跳过)。 */
    private final SlashTokenService slashTokens;
    /** 手动压缩请求内存注册表(inline 胶囊一次性请求;为空不支持手动压缩)。 */
    private final ManualCompressRegistry manualRequests;

    /** 上轮实际发给模型的指令视图(含注入的提示),供下一轮 offset 校准。 */
    private List<Message> lastSent;
    /** 累积压缩基线;null = 尚未压缩过。 */
    private List<Message> baseline;
    /** 已吸收进 baseline 的「完整历史」源消息条数(下标)。 */
    private int absorbed;

    public ContextCompressionAdvisor(AgentContext a, WorkerConfig.Limits limits,
            ContextSummarizer summarizer, ContextCompressor.TextTokens tokens) {
        this(a, limits, summarizer, tokens, null, null);
    }

    public ContextCompressionAdvisor(AgentContext a, WorkerConfig.Limits limits,
            ContextSummarizer summarizer, ContextCompressor.TextTokens tokens,
            SlashTokenService slashTokens) {
        this(a, limits, summarizer, tokens, slashTokens, null);
    }

    public ContextCompressionAdvisor(AgentContext a, WorkerConfig.Limits limits,
            ContextSummarizer summarizer, ContextCompressor.TextTokens tokens,
            SlashTokenService slashTokens, ManualCompressRegistry manualRequests) {
        this.a = a;
        this.limits = limits;
        this.summarizer = summarizer;
        this.tokens = tokens;
        this.slashTokens = slashTokens;
        this.manualRequests = manualRequests;
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
        ExecContext exec = a.execution();
        if (request == null || request.prompt() == null) {
            return request;
        }

        // 手动压缩请求:用户经 /压缩上下文 inline 胶囊随消息提交触发的一次性强制压缩。
        // 即使自动压缩整体关闭也生效(用户显式请求);检测后消费式移除,不重复触发。
        boolean manualCompress = checkManualCompress(exec);

        if (!manualCompress && !limits.contextCompressionEnabled()) {
            return request;
        }
        List<Message> messages = request.prompt().getInstructions();
        if (messages == null || messages.isEmpty()) {
            if (manualCompress) {
                consumeManualCompress(exec);
            }
            return request;
        }

        List<Message> full = request.prompt().getInstructions();
        long window = contextWindowTokens();
        long trigger = ratio(window, limits.contextTriggerRatio(), limits.contextSafetyRatio());
        long target = ratio(window, limits.contextTargetRatio(), limits.contextSafetyRatio());
        long reserve = limits.contextToolReserveTokens();
        int maxToolChars = limits.contextMaxToolResultChars();

        // offset 校准:用「上轮实测 inputTokens − 上轮估算 lastSent」作为净修正项,抵消粗估
        // 与 provider 实测的系统性差异;首轮/无实测/换模型时回退固定 reserve。
        // 双向采纳(正=漏算隐藏开销、负=粗估高估),见 ContextCompressor.calibratedOverhead。
        long overhead = reserve;
        Double seedRatio = null;
        if (limits.contextOffsetEnabled()) {
            Usage last = a.lastRound();
            String lastModel = a.lastModel();
            String currentModel = a.currentModel();
            if (last != null
                    && lastSent != null && !lastSent.isEmpty()
                    && lastModel != null && !lastModel.isEmpty()
                    && currentModel != null && !currentModel.isEmpty()
                    && lastModel.equals(currentModel)) {
                overhead = ContextCompressor.calibratedOverhead(
                        last.inputTokens(), ContextCompressor.estimateTokens(lastSent, tokens), reserve);
            } else if (lastSent == null) {
                // 冷启动种子(再运行/冷重启的首个请求):运行内加性校准所需状态(lastSent/lastRound)
                // 是内存态、随运行销毁,首个请求本会退回固定 reserve——粗估高估会在窗口远未占满时
                // 误触发压缩(2026-10 t_ngj2:粗估 23~36 万、实测仅数千 token,每次再运行首轮都压
                // 一次)。改用上一运行持久化的「全量视图 实测/估算」比率折算;换模型种子作废。
                // 本运行首个实测 usage 回来后自动回到上方加性重算。
                CompressionSeed seed = exec == null ? null : exec.compressionSeed(a.agentId());
                if (seed != null && seed.usable()
                        && currentModel != null && !currentModel.isEmpty()
                        && seed.model() != null && !seed.model().isEmpty()
                        && seed.model().equals(currentModel)) {
                    seedRatio = ContextCompressor.seedRatio(
                            seed.measuredInputTokens(), seed.estimatedSentTokens());
                    if (seedRatio != null) {
                        log.debug("冷启动压缩校准种子生效: task={} agent={} 实测={} 估算={} 比率={}",
                                exec.subjectId(), a.agentId(), seed.measuredInputTokens(),
                                seed.estimatedSentTokens(), String.format("%.3f", seedRatio));
                    }
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

        long est = ContextCompressor.estimateTokens(working, tokens);
        if (seedRatio != null) {
            overhead = Math.round(est * seedRatio) - est;
        }
        long used = est + overhead;

        List<Message> toSend;
        if (used <= trigger && !manualCompress) {
            toSend = working;
        } else {
            // 手动压缩:绕过触发阈值,直接以 target 为目标压缩(trigger 传 0 强制进入压缩分支)。
            long effectiveTrigger = manualCompress ? 0 : trigger;
            String modeLabel = manualCompress ? "手动" : "自动";

            // 压缩中 trace(只含 summary;进行中瞬态,完成后同 id 更新为持久)
            long compId = SnowflakeId.next();
            a.emitter().emit(EmitEvent.transientOf(compId, "context.compression", null, null,
                    "正在" + modeLabel + "压缩上下文…(当前约 " + used + " token)", null, null, null,
                    EmitEvent.Mode.REPLACE));

            ContextSummarizer sum = limits.contextSummaryEnabled() ? summarizer : null;
            ContextCompressor.Result r = ContextCompressor.compress(working, effectiveTrigger, target, overhead, sum, tokens);
            if (!r.compressed()) {
                toSend = working;
                a.emitter().emit(EmitEvent.of(compId, "context.compression", null, null,
                        "已" + modeLabel + "压缩上下文(无需改写)", null, null, null, EmitEvent.Mode.REPLACE));
            } else {
                toSend = r.messages();
                baseline = new ArrayList<>(r.messages());
                absorbed = full.size();
                long after = ContextCompressor.estimateTokens(toSend, tokens) + overhead;
                a.emitter().emit(EmitEvent.of(compId, "context.compression", null, null,
                        "已" + modeLabel + "压缩上下文(" + stageName(r.stage()) + ", 消息 " + working.size() + "→"
                                + toSend.size() + ", 约 " + used + "→" + after + " token 估算)",
                        null, null, null, EmitEvent.Mode.REPLACE));
                log.info("任务 {} agent {} {}压缩上下文: used={} trigger={} target={} messages {}→{} 阶段={} "
                                + "丢历史轮={} 丢本轮工具对={}",
                        exec.subjectId(), a.agentId(), modeLabel, used, trigger, target, working.size(),
                        toSend.size(), stageName(r.stage()), r.droppedHistoryTurns(), r.droppedCurrentToolPairs());
                if (after > target) {
                    log.warn("任务 {} agent {} {}压缩后仍 > 目标阈值(本轮单 turn 过大),交由模型上限兜底",
                            exec.subjectId(), a.agentId(), modeLabel);
                }
            }
            // 手动压缩请求消费:无论是否实际改写,token 用后移除(一次性)。
            if (manualCompress) {
                consumeManualCompress(exec);
            }
        }

        // 压缩后注入提示:一旦发生过压缩,每轮都提示模型不要凭记忆编造早期细节。
        List<Message> finalView;
        if (baseline != null) {
            SystemMessage hint = new SystemMessage(
                    "【系统提示】早期上下文已被压缩，需要之前的具体细节时请通过工具重新读取/搜索，不要凭空猜测。");
            finalView = new ArrayList<>();
            finalView.add(hint);
            finalView.addAll(toSend);
        } else {
            finalView = toSend;
        }

        // 记录上轮实际发送视图(含注入提示),供下一轮 offset 校准与 provider 实测对账。
        lastSent = new ArrayList<>(finalView);

        // 全量视图记账(§7.5 冷启动种子):仅未压缩视图记录发送估算(经 AgentContext 槽位,
        // 由事件 advisor 随本轮 usage 事件落盘);压缩视图清 0——其估算/实测比率不表征
        // 全量历史的估算偏差,落盘会污染下一运行的冷启动种子。
        a.fullViewEstTokens(baseline == null ? ContextCompressor.estimateTokens(finalView, tokens) : 0L);

        return ChatClientRequest.builder()
                .prompt(new Prompt(finalView, request.prompt().getOptions()))
                .context(request.context())
                .build();
    }

    /** 上下文窗口口径单点:委托 {@link ModelConfig#contextWindowTokens()}(与 usage 事件/超限诊断同源,防阈值漂移)。 */
    private long contextWindowTokens() {
        ExecContext exec = a.execution();
        return exec.snapshot() == null ? WorkerConfig.DEFAULT_CONTEXT_WINDOW_TOKENS
                : exec.snapshot().contextWindowTokens();
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

    /**
     * 检测是否存在待消费的手动压缩请求,双通道(任一命中即请求有效):
     * <ul>
     *   <li>主通道:{@link ManualCompressRegistry} 内存注册表——/压缩上下文 inline 胶囊
     *       随消息提交时由 {@code CompressContextRequestNode}(轮次循环 881)登记,per-run
     *       一次性语义;</li>
     *   <li>兼容通道:任务级 slash token(旧版 bottom 胶囊写 {@code slash-tokens.json}),
     *       供线上已残留 token 的存量任务照常消费清零。</li>
     * </ul>
     *
     * @return true = 存在手动压缩请求
     */
    private boolean checkManualCompress(ExecContext exec) {
        if (exec == null) {
            return false;
        }
        if (manualRequests != null && manualRequests.has(exec.subjectId())) {
            return true;
        }
        if (slashTokens == null) {
            return false;
        }
        try {
            return slashTokens.hasKind(exec.subjectId(), CompressContextToken.KIND);
        } catch (RuntimeException e) {
            log.debug("手动压缩 token 查询失败 task={}", exec.subjectId(), e);
            return false;
        }
    }

    /**
     * 消费(移除)手动压缩请求 —— 一次性,压缩完成后立即移除,不重复触发:
     * 内存注册表 consume + 兼容通道 removeKind(移除后 slash 层广播
     * {@code slash.tokens.changed},存量任务的底部胶囊随之消失)。
     */
    private void consumeManualCompress(ExecContext exec) {
        if (exec == null) {
            return;
        }
        if (manualRequests != null) {
            manualRequests.consume(exec.subjectId());
        }
        if (slashTokens == null) {
            return;
        }
        try {
            boolean removed = slashTokens.removeKind(exec.subjectId(), CompressContextToken.KIND);
            if (removed) {
                log.debug("手动压缩请求已消费 task={} agent={}", exec.subjectId(), a.agentId());
            }
        } catch (RuntimeException e) {
            log.warn("手动压缩 token 消费失败 task={}", exec.subjectId(), e);
        }
    }
}
