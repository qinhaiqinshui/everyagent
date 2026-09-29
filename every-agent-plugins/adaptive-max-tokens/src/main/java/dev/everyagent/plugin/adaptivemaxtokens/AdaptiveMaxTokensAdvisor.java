package dev.everyagent.plugin.adaptivemaxtokens;

import com.openai.errors.OpenAIServiceException;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.agent.AgentEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 自适应输出预算 advisor(红线:一个 advisor 只负责一个功能)。
 *
 * <p>检测到模型流中的 {@code finish_reason=length} 帧后,自动放大 maxTokens 重试,
 * 直到产出有效结果或触及 ceiling。位于 {@link ToolCallingAdvisor#DEFAULT_ORDER} + 250
 * (Guard +300 外侧),Guard 检测到耗尽时先补合成 finish_reason=length 帧再抛异常,
 * 本 advisor 消费帧升级预算重试——只认帧,不认异常类型/文案。
 *
 * <p>双信号模型:Guard 的帧(数据面)先于异常(控制面)到达——Reactor 保证
 * onNext(帧) 先于 onError(异常),flag 必已置位;complete 路径(无 Guard 真实帧)
 * 同样通过 {@code switchIfEmpty} 触发升级。
 *
 * <p>per-run 实例字段(currentBudget / attempt / baseMaxTokens)任务级持续生效,主/子
 * agent 各自隔离;per-subscription 状态(lengthFlag)在 {@code Flux.defer} 闭包中创建,
 * 多任务并发安全。
 *
 * <p>重试自包装:重试 Flux 通过 {@link #doRetry} 方法创建,自带完整的 doOnNext /
 * filter / switchIfEmpty / onErrorResume 链,不依赖 {@code chain.copy(this)} 的重入行为
 * ——无论测试桩还是生产链,重试流都被正确包装。
 */
public class AdaptiveMaxTokensAdvisor implements StreamAdvisor {

    private static final Logger log = LoggerFactory.getLogger(AdaptiveMaxTokensAdvisor.class);

    /** finish_reason=length 语义:输出量达上限但未完成。 */
    private static final Set<String> LENGTH = Set.of("length");

    private final AgentEntity a;
    private final WorkerProperties.Limits.AdaptiveMaxTokens cfg;
    private final long effectiveCeiling;

    // ---- per-run 实例字段(任务级持续生效) ----

    /** 原始 base maxTokens(首次 adviseStream 时锁定,不受重试改写影响)。 */
    private long baseMaxTokens;
    /** 当前生效预算(首次为 base maxTokens,升级后持续生效)。 */
    private long currentBudget;
    /** 当前升级次数。 */
    private int attempt = 0;
    /** 连续低水位轮次计数(低水位回落用)。 */
    private int lowWaterRounds = 0;
    /** 是否已因 400 回退(回退后停止继续上调)。 */
    private boolean rolledBack400 = false;

    public AdaptiveMaxTokensAdvisor(AgentEntity a, WorkerProperties.Limits.AdaptiveMaxTokens cfg,
            long effectiveCeiling) {
        this.a = a;
        this.cfg = cfg;
        this.effectiveCeiling = effectiveCeiling;
    }

    @Override
    public String getName() {
        return "Adaptive Max Tokens Advisor";
    }

    @Override
    public int getOrder() {
        // Guard(+300)外侧:Guard 的帧先到,flag 置位,再触发升级重试。
        return ToolCallingAdvisor.DEFAULT_ORDER + 250;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        Integer baseMt = maxTokensOf(request);
        if (!cfg.isEnabled() || baseMt == null || baseMt <= 0) {
            // 未配置 base maxTokens 或 disabled → 直通,不干预。
            return chain.nextStream(request);
        }

        // 首次:锁定 baseMaxTokens 和初始化 currentBudget。
        if (baseMaxTokens == 0) {
            baseMaxTokens = baseMt;
        }
        if (currentBudget == 0) {
            currentBudget = baseMt;
        }

        return doRetry(request, chain);
    }

    /**
     * 单轮流处理:包装 {@code chain.nextStream(request)} 的 Flux,附加 length 帧检测、
     * 吞帧、低水位检查、complete 路径升级(error 路径升级)。
     *
     * <p>per-subscription 状态(lengthFlag / lastCompletionTokens)在 {@code Flux.defer}
     * 闭包中创建,每次(重)订阅独立,多任务并发安全。
     */
    private Flux<ChatClientResponse> doRetry(ChatClientRequest request, StreamAdvisorChain chain) {
        return Flux.defer(() -> {
            AtomicBoolean lengthFlag = new AtomicBoolean(false);
            AtomicLong lastCompletionTokens = new AtomicLong(0);
            return chain.nextStream(request)
                    .doOnNext(chunk -> {
                        ChatResponse cr = chunk.chatResponse();
                        if (cr != null && cr.hasFinishReasons(LENGTH)) {
                            lengthFlag.set(true);
                        }
                        // 记录最后一个 chunk 的 usage completionTokens(低水位回落用)。
                        if (cr != null && cr.getMetadata() != null) {
                            Usage usage = cr.getMetadata().getUsage();
                            if (usage != null && usage.getCompletionTokens() != null) {
                                lastCompletionTokens.set(usage.getCompletionTokens());
                            }
                        }
                    })
                    // 吞掉 length 帧(防止泄漏到外层聚合器/前端)。
                    .filter(chunk -> {
                        ChatResponse cr = chunk.chatResponse();
                        return !(cr != null && cr.hasFinishReasons(LENGTH));
                    })
                    // complete 路径:流正常结束后,若 lengthFlag 置位(所有帧被 filter 吞掉
                    // → 上游 empty → switchIfEmpty 触发),执行升级重试。
                    // 同时在 doOnComplete 中做低水位检查(每轮流结束时检查 usage)。
                    .doOnComplete(() -> checkLowWatermark(lastCompletionTokens.get()))
                    .switchIfEmpty(Flux.defer(() -> {
                        if (lengthFlag.get()) {
                            // complete 路径触发升级。
                            return upgradeAndRetry(request, chain, null);
                        }
                        return Flux.empty();
                    }))
                    .onErrorResume(error -> {
                        // AdaptiveBudgetExhaustedException 不再重试,直接透传。
                        if (error instanceof AdaptiveBudgetExhaustedException) {
                            return Flux.error(error);
                        }
                        // 低水位检查(流因 error 结束时)。
                        checkLowWatermark(lastCompletionTokens.get());
                        if (lengthFlag.get()) {
                            // error 路径触发升级(Guard 的帧先到,flag 已置位)。
                            return upgradeAndRetry(request, chain, error);
                        }
                        // 400 路径:无 length 帧但 provider 返回 400(拒绝超出模型真实上限),
                        // 触发回退重试。
                        if (is400Error(error) && attempt > 0) {
                            return upgradeAndRetry(request, chain, error);
                        }
                        // 非 length 错误原样透传。
                        return Flux.error(error);
                    });
        });
    }

    /**
     * 升级逻辑(complete 和 error 两路对称,共用同一方法)。
     *
     * <p>步骤:
     * <ol>
     *   <li>400 回退检测(仅在 error 路径,error != null)</li>
     *   <li>attempt++</li>
     *   <li>newBudget = min(base × multiplier^attempt, ceiling)</li>
     *   <li>若超限或已达 ceiling → 抛 AdaptiveBudgetExhaustedException</li>
     *   <li>改写 prompt options 的 maxTokens</li>
     *   <li>通过 {@link #doRetry} 自包装重试 Flux</li>
     * </ol>
     *
     * @param error complete 路径为 null,error 路径为触发异常
     */
    private Flux<ChatClientResponse> upgradeAndRetry(ChatClientRequest request,
            StreamAdvisorChain chain, Throwable error) {
        // 400 回退:重试轮收到 400 类错误(provider 拒绝超出模型真实上限),
        // 一次性回退到触发升级前的上一个 budget 并停止继续上调。
        if (error != null && is400Error(error) && attempt > 0) {
            long prevBudget = (long) (baseMaxTokens * Math.pow(cfg.getMultiplier(), attempt - 1));
            prevBudget = Math.min(prevBudget, effectiveCeiling);
            currentBudget = prevBudget;
            rolledBack400 = true;
            log.warn("任务 {} agent {} 升级后收到 400 错误({}),回退 budget 至 {} 并停止上调",
                    a.task.taskId, a.agentId, error.getMessage(), currentBudget);
            // 用回退后的 budget 重试一次。
            ChatClientRequest newRequest = rewriteMaxTokens(request, currentBudget);
            return doRetry(newRequest, chain);
        }

        // 已因 400 回退,不再上调。
        if (rolledBack400) {
            return Flux.error(new AdaptiveBudgetExhaustedException(
                    "自适应输出预算在 400 回退后仍收到 finish_reason=length,"
                            + "已停止上调。建议:精简输入或把任务拆分成多步;降低 reasoningEffort;"
                            + "或调大 maxTokens 后重试。"));
        }

        attempt++;
        long newBudget = (long) (baseMaxTokens * Math.pow(cfg.getMultiplier(), attempt));
        newBudget = Math.min(newBudget, effectiveCeiling);

        if (attempt > cfg.getMaxRetries() || (newBudget >= effectiveCeiling && currentBudget >= effectiveCeiling)) {
            log.warn("任务 {} agent {} 自适应预算耗尽:attempt={}, currentBudget={}, ceiling={}",
                    a.task.taskId, a.agentId, attempt, currentBudget, effectiveCeiling);
            return Flux.error(new AdaptiveBudgetExhaustedException(
                    "自适应输出预算已放大至 ceiling=" + effectiveCeiling
                            + " 仍未获得有效结果(attempt=" + attempt + ")。"
                            + "建议:1)精简输入或把任务拆分成多步;"
                            + "2)降低 reasoningEffort 或减小单次输出预算;"
                            + "3)如需更长输出,调大 maxTokens 后重试。"));
        }

        currentBudget = newBudget;
        log.warn("任务 {} agent {} finish_reason=length,升级 maxTokens 至 {}(attempt={}/{})",
                a.task.taskId, a.agentId, currentBudget, attempt, cfg.getMaxRetries());

        ChatClientRequest newRequest = rewriteMaxTokens(request, currentBudget);
        // 自包装重试:doRetry 自带完整 doOnNext / filter / switchIfEmpty / onErrorResume 链,
        // 不依赖 chain.copy(this) 的重入行为。
        return doRetry(newRequest, chain);
    }

    /**
     * 低水位回落:连续 N 轮(fallbackRounds)实际输出 < currentBudget × fallbackRatio
     * → currentBudget 衰减回 base。在每轮流结束时检查 usage。
     */
    private void checkLowWatermark(long completionTokens) {
        if (currentBudget <= 0 || attempt <= 0) {
            // 未升级过,无需回落。
            return;
        }
        long threshold = (long) (currentBudget * cfg.getFallbackRatio());
        if (completionTokens < threshold) {
            lowWaterRounds++;
            if (lowWaterRounds >= cfg.getFallbackRounds()) {
                log.info("任务 {} agent {} 连续 {} 轮低输出({} < {}×{}={}),budget 衰减回 base",
                        a.task.taskId, a.agentId, lowWaterRounds, completionTokens,
                        currentBudget, cfg.getFallbackRatio(), threshold);
                // 衰减回 base(通过重置 attempt 和 currentBudget 实现)。
                attempt = 0;
                currentBudget = baseMaxTokens;
                lowWaterRounds = 0;
            }
        } else {
            // 重置计数。
            if (lowWaterRounds > 0) {
                lowWaterRounds = 0;
            }
        }
    }

    // ---- 辅助方法 ----

    private static Integer maxTokensOf(ChatClientRequest request) {
        if (request == null || request.prompt() == null) {
            return null;
        }
        ChatOptions opts = request.prompt().getOptions();
        return opts == null ? null : opts.getMaxTokens();
    }

    /**
     * 改写 prompt options 的 maxTokens,返回新的 ChatClientRequest。
     * 使用 OpenAiChatOptions.mutate() 复制完整配置后只改 maxTokens。
     */
    private static ChatClientRequest rewriteMaxTokens(ChatClientRequest request, long newBudget) {
        ChatOptions opts = request.prompt().getOptions();
        ChatOptions newOpts;
        if (opts instanceof OpenAiChatOptions openaiOpts) {
            newOpts = openaiOpts.mutate()
                    .maxTokens((int) newBudget)
                    .build();
        } else if (opts != null) {
            newOpts = opts.mutate()
                    .maxTokens((int) newBudget)
                    .build();
        } else {
            newOpts = OpenAiChatOptions.builder()
                    .maxTokens((int) newBudget)
                    .build();
        }
        Prompt newPrompt = new Prompt(request.prompt().getInstructions(), newOpts);
        return request.mutate().prompt(newPrompt).build();
    }

    /**
     * 判断是否为 400 类错误(provider 拒绝超出模型真实上限)。
     * 沿 cause 链下沉,优先检测 OpenAIServiceException 的 statusCode == 400;
     * 兜底用反射查找 statusCode() 方法(兼容测试桩与未来 SDK 变更)。
     */
    private static boolean is400Error(Throwable error) {
        int depth = 0;
        for (Throwable t = error; t != null && depth < 8; depth++, t = t.getCause()) {
            if (t instanceof OpenAIServiceException svc) {
                return svc.statusCode() == 400;
            }
            // 反射兜底:查找无参 statusCode() 方法。
            try {
                var m = t.getClass().getMethod("statusCode");
                if (m.getParameterCount() == 0) {
                    Object result = m.invoke(t);
                    if (result instanceof Integer code && code == 400) {
                        return true;
                    }
                }
            } catch (Exception ignored) {
                // 无 statusCode() 方法,继续沿 cause 链。
            }
        }
        return false;
    }
}
