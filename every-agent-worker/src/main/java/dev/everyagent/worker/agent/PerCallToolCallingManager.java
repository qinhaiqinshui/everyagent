package dev.everyagent.worker.agent;

import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.plugin.api.model.EventEmitter;
import dev.everyagent.plugin.api.spi.ToolExecutionContext;
import dev.everyagent.plugin.api.spi.ToolExecutionChain;
import dev.everyagent.plugin.api.spi.ToolExecutionInterceptor;
import dev.everyagent.plugin.api.spi.ToolOutcomeLog;
import dev.everyagent.worker.plugin.registry.ToolExecutionInterceptorRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallLimitBehavior;
import org.springframework.ai.model.tool.ToolCallLimitExceededException;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.ai.tool.observation.DefaultToolCallingObservationConvention;
import org.springframework.ai.tool.observation.ToolCallingObservationContext;
import org.springframework.ai.tool.observation.ToolCallingObservationConvention;
import org.springframework.ai.tool.observation.ToolCallingObservationDocumentation;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * <b>照抄块</b>:本类的 {@link #executeToolCalls}/{@link #executeToolCall}/
 * {@link #buildToolContext}/{@link #buildConversationHistoryAfterToolExecution} 照抄自
 * <b>Spring AI 2.0.1</b> 的 {@code org.springframework.ai.model.tool.DefaultToolCallingManager}
 * (自建 {@code InternalToolExecutionResult} 与 {@link ToolCallLimits} 等价物——后者框架侧
 * package-private 包外不可引用),<b>逐语义对齐,不自行重设计</b>;差异仅两处:
 * <ol>
 *   <li>{@code executeToolCall} 签名增 {@code chatResponse} 参数(切口 ctx 契约成员需要);</li>
 *   <li>工具调用 for 循环内插<b>两处切口</b>调用项目 per-call 工具执行拦截链(方案 B,见下)。</li>
 * </ol>
 * <b>升级 Spring AI 版本时 diff 框架源码对齐本类,禁止顺手重设计</b>(照抄纪律)。
 *
 * <p><b>红线豁免记录</b>:照抄 DefaultToolCallingManager 循环体 + 插切口 = 自持循环的
 * 轻量形态(用户 2026 讨论确认放行),豁免 AGENTS.md「不得手搓工具调用循环」红线;
 * 豁免范围仅限本照抄块,不构成对自研行为重设计的授权。
 *
 * <p><b>切口(方案 B,严格)</b>——每次工具调用执行一遍
 * {@link ToolExecutionInterceptor} 链,链尾 = 框架原生 observation + 调用 + 异常处理:
 * <ul>
 *   <li><b>下行</b>(回调解析后、observation 前:ToolCall 全字段 + ToolCallback 在手):
 *       链节点下行段可改写参数({@code ctx.arguments(...)})、可合成当前调用结果
 *       ({@code ctx.synthesize(串)} 哨兵 → 链尾跳过 observation + 真实调用,合成串沿框架
 *       ToolResponse 构造路径流经全部上行改写节点,循环继续推进下一个调用);
 *       <b>禁止 mid-batch break</b>(框架循环无批内中断,returnDirect 是批级出口标志)。</li>
 *   <li><b>上行</b>(observe 返回后、ToolResponse 构造前:结果串 + 配对 id 在手):链节点
 *       上行段({@code next.proceed} 返回后)可改写结果串、可发事件,不可撤销已执行调用。</li>
 * </ul>
 *
 * <p><b>per-run 数据通道</b>:本类是<b>共享无状态单例</b>(bean 装配,替换原
 * {@code InterceptingToolCallingManager} 的 per-run 构造)——per-run 四件套(agentId +
 * agent 级 emitter + {@link ExecContext} + {@link ToolOutcomeLog})由 {@code AgentRunner} 组装
 * prompt 时经 {@code options.toolContext(RUN_CONTEXT_KEY, RunContext)} 下行,框架
 * {@link #buildToolContext} 原样透传,切口从 {@code ToolContext} map 取出构造 per-call
 * ctx;map 无该键(单测/裸用)时退化为<b>纯框架路径</b>,行为与
 * {@code DefaultToolCallingManager} 逐位一致。不依赖 ThreadLocal(reactive 流的工具
 * 执行可能切换 boundedElastic 线程)。
 */
public final class PerCallToolCallingManager implements ToolCallingManager {

    private static final Log logger = LogFactory.getLog(PerCallToolCallingManager.class);

    /** 照抄:默认 observation 设施(NOOP registry + 默认约定;与 builder 缺省一致)。 */
    private static final ObservationRegistry DEFAULT_OBSERVATION_REGISTRY = ObservationRegistry.NOOP;
    private static final ToolCallingObservationConvention DEFAULT_OBSERVATION_CONVENTION =
            new DefaultToolCallingObservationConvention();

    /**
     * 照抄(常量池对齐 2.0.1):回调解析失败前的「LLM 可能改写了工具名」警告首尾段。
     */
    private static final String POSSIBLE_LLM_TOOL_NAME_CHANGE_WARNING_START =
            "LLM may have adapted the tool name '";
    private static final String POSSIBLE_LLM_TOOL_NAME_CHANGE_WARNING_END =
            "', especially if the name was truncated due to length limits. If this is the case,"
                    + " you can customize the prefixing and processing logic using McpToolNamePrefixGenerator";

    // ══════════ 照抄字段(四槽位 = 原 ToolCallingManager.builder() 的配置,显式传构造) ══════════

    private final ObservationRegistry observationRegistry = DEFAULT_OBSERVATION_REGISTRY;
    private final ToolCallbackResolver toolCallbackResolver;
    private final ToolExecutionExceptionProcessor toolExecutionExceptionProcessor;
    private final ToolCallLimits toolCallLimits;
    private final boolean resolutionFallbackEnabled;
    private ToolCallingObservationConvention observationConvention = DEFAULT_OBSERVATION_CONVENTION;

    /** 项目切口:per-call 拦截链注册表(共享,链每次调用执行一遍)。 */
    private final ToolExecutionInterceptorRegistry interceptorRegistry;
    private final ToolExecutionChainExecutor chainExecutor = new ToolExecutionChainExecutor();

    /**
     * prompt options 框架 {@code ToolContext} map 里携带 per-run 三件套的键
     * ({@code AgentRunner} 写入,切口读取)。
     */
    public static final String RUN_CONTEXT_KEY = "everyagent.runContext";

    /**
     * per-run 四件套(agentId + agent 级 emitter(agentId 包装)/执行上下文/工具结果账本),
     * 经 prompt options 的 toolContext 下行——共享单例 TCM 的 per-run 数据通道。
     * agentId 供链节点做 MAIN/SUB_AGENT 归属判定(file-change 一阶段记账消费,§7.15.2)。
     */
    public record RunContext(String agentId, EventEmitter emitter, ExecContext execution,
            ToolOutcomeLog toolOutcomes) {
    }

    public PerCallToolCallingManager(ToolCallbackResolver toolCallbackResolver,
            ToolExecutionExceptionProcessor toolExecutionExceptionProcessor,
            ToolCallLimits toolCallLimits,
            boolean resolutionFallbackEnabled,
            ToolExecutionInterceptorRegistry interceptorRegistry) {
        Assert.notNull(this.observationRegistry, "observationRegistry cannot be null");
        Assert.notNull(toolCallbackResolver, "toolCallbackResolver cannot be null");
        Assert.notNull(toolExecutionExceptionProcessor, "toolCallExceptionConverter cannot be null");
        Assert.notNull(toolCallLimits, "toolCallLimits cannot be null");
        this.toolCallbackResolver = toolCallbackResolver;
        this.toolExecutionExceptionProcessor = toolExecutionExceptionProcessor;
        this.toolCallLimits = toolCallLimits;
        this.resolutionFallbackEnabled = resolutionFallbackEnabled;
        this.interceptorRegistry = interceptorRegistry;
    }

    // ════════════════════════ 照抄:resolveToolDefinitions ════════════════════════

    @Override
    public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
        Assert.notNull(chatOptions, "chatOptions cannot be null");
        return new ArrayList<>(CollectionUtils.isEmpty(chatOptions.getToolCallbacks())
                ? List.of() : chatOptions.getToolCallbacks())
                .stream().map(ToolCallback::getToolDefinition).toList();
    }

    // ════════════════════════ 照抄:executeToolCalls ════════════════════════

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
        Assert.notNull(prompt, "prompt cannot be null");
        Assert.notNull(chatResponse, "chatResponse cannot be null");
        Optional<Generation> toolCallGeneration = chatResponse.getResults().stream()
                .filter(g -> !CollectionUtils.isEmpty(g.getOutput().getToolCalls()))
                .findFirst();
        if (toolCallGeneration.isEmpty()) {
            throw new IllegalStateException("No tool call requested by the chat model");
        }
        AssistantMessage assistantMessage = toolCallGeneration.get().getOutput();
        ToolContext toolContext = buildToolContext(prompt, assistantMessage);
        InternalToolExecutionResult internalToolExecutionResult = executeToolCall(prompt, chatResponse,
                assistantMessage, toolContext);
        List<Message> conversationHistory = this.buildConversationHistoryAfterToolExecution(
                prompt.getInstructions(), assistantMessage, internalToolExecutionResult.toolResponseMessage());
        return ToolExecutionResult.builder()
                .conversationHistory(conversationHistory)
                .returnDirect(internalToolExecutionResult.returnDirect())
                .build();
    }

    // ════════════════════════ 照抄:buildToolContext ════════════════════════

    private static ToolContext buildToolContext(Prompt prompt, AssistantMessage assistantMessage) {
        Map<String, Object> context = Map.of();
        ChatOptions options = prompt.getOptions();
        if (options instanceof ToolCallingChatOptions toolCallingChatOptions) {
            if (!CollectionUtils.isEmpty(toolCallingChatOptions.getToolContext())) {
                context = new HashMap<>(toolCallingChatOptions.getToolContext());
            }
        }
        return new ToolContext(context);
    }

    // ════════════════════════ 照抄 + 两处切口:executeToolCall ════════════════════════
    // 与框架 2.0.1 的差异:签名增 chatResponse(切口 ctx 契约成员);循环内插 per-call 链切口。

    private InternalToolExecutionResult executeToolCall(Prompt prompt, ChatResponse chatResponse,
            AssistantMessage assistantMessage, ToolContext toolContext) {
        List<ToolCallback> toolCallbacks = List.of();
        ChatOptions options = prompt.getOptions();
        if (options instanceof ToolCallingChatOptions toolCallingChatOptions) {
            if (!CollectionUtils.isEmpty(toolCallingChatOptions.getToolCallbacks())) {
                toolCallbacks = toolCallingChatOptions.getToolCallbacks();
            }
        }

        List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();
        Boolean returnDirect = null;

        Map<String, Integer> cumulativeToolCallsByTool = ToolCallLimits.countPriorToolCalls(prompt.getInstructions());
        int totalToolCalls = cumulativeToolCallsByTool.values().stream().mapToInt(Integer::intValue).sum();

        Iterator<AssistantMessage.ToolCall> iterator = assistantMessage.getToolCalls().iterator();
        while (iterator.hasNext()) {
            AssistantMessage.ToolCall toolCall = iterator.next();

            if (logger.isDebugEnabled()) {
                logger.debug("Executing tool call: " + toolCall.name());
            }

            String toolName = toolCall.name();
            totalToolCalls++;
            Integer currentToolCallCount = cumulativeToolCallsByTool.merge(toolName, 1, Integer::sum);

            // ── 照抄:ToolCallLimits 检查(两种超限 behavior:THROW 抛 / RETURN_ERROR_RESPONSE 合成错误结果继续) ──
            ToolCallLimits.Breach breach = this.toolCallLimits.check(toolName, currentToolCallCount, totalToolCalls);
            if (breach != null) {
                toolResponses.add(new ToolResponseMessage.ToolResponse(toolCall.id(), toolName, breach.message()));
                if (this.toolCallLimits.onLimitExceeded() == ToolCallLimitBehavior.THROW) {
                    ToolResponseMessage toolResponseMessage = ToolResponseMessage.builder()
                            .responses(toolResponses).build();
                    List<Message> conversationHistory = buildConversationHistoryAfterToolExecution(
                            prompt.getInstructions(), assistantMessage, toolResponseMessage);
                    ToolExecutionResult toolExecutionResult = ToolExecutionResult.builder()
                            .conversationHistory(conversationHistory)
                            .returnDirect(Objects.requireNonNullElse(returnDirect, false))
                            .build();
                    throw new ToolCallLimitExceededException(breach.toolName(), breach.limit(),
                            toolExecutionResult);
                }
                continue;
            }

            // ── 照抄:空参归一 ──
            String toolCallArguments = toolCall.arguments();
            if (!StringUtils.hasText(toolCallArguments)) {
                if (logger.isWarnEnabled()) {
                    logger.warn("Tool call arguments are null or empty for tool: " + toolName
                            + ". Using empty JSON object as default.");
                }
                toolCallArguments = "{}";
            }

            // ── 照抄:回调解析(options 内回调名匹配 → resolutionFallbackEnabled 回退 → 未找到抛 ISE) ──
            ToolCallback toolCallback = toolCallbacks.stream()
                    .filter(tool -> toolName.equals(tool.getToolDefinition().name()))
                    .findFirst()
                    .orElseGet(() -> {
                        if (this.resolutionFallbackEnabled) {
                            return this.toolCallbackResolver.resolve(toolName);
                        }
                        return null;
                    });
            if (toolCallback == null) {
                if (logger.isWarnEnabled()) {
                    logger.warn(POSSIBLE_LLM_TOOL_NAME_CHANGE_WARNING_START + toolName
                            + POSSIBLE_LLM_TOOL_NAME_CHANGE_WARNING_END);
                }
                throw new IllegalStateException("No ToolCallback found for tool name: " + toolName);
            }

            // ── 照抄:returnDirect 的 AND 累积(首轮记,后续 &&) ──
            if (returnDirect == null) {
                returnDirect = toolCallback.getToolMetadata().returnDirect();
            }
            else {
                returnDirect = returnDirect && toolCallback.getToolMetadata().returnDirect();
            }

            // ══ 项目切口(方案 B)═══════════════════════════════════════════════════
            // 下行 = 此处(回调解析后、observation 前):per-call 链节点下行段在 next.proceed
            // 之前执行(可改写参数 / synthesize 合成哨兵);链尾 = 框架原生 observation +
            // 调用 + 异常处理(哨兵置位则跳过真实调用);上行 = observe 返回后、ToolResponse
            // 构造前:各节点上行段(next.proceed 返回后)对结果串改写/发事件。per-run 三件套
            // 从框架 ToolContext map 取出(见 RUN_CONTEXT_KEY);无则纯框架路径。
            String toolCallResult = executeWithPerCallChain(prompt, chatResponse, toolContext,
                    assistantMessage.getToolCalls(), toolCall, toolCallback, toolCallArguments);

            // ── 照抄:结果构造(null 归一为空串) ──
            toolResponses.add(new ToolResponseMessage.ToolResponse(toolCall.id(), toolName,
                    toolCallResult != null ? toolCallResult : ""));
        }

        ToolResponseMessage toolResponseMessage = ToolResponseMessage.builder().responses(toolResponses).build();
        return new InternalToolExecutionResult(toolResponseMessage, Objects.requireNonNullElse(returnDirect, false));
    }

    // ════════════════════════ 照抄:框架原生单调用执行(observation + call + 异常处理) ════════════════════════

    /**
     * observation 生命周期照抄:TOOL_CALL 文档 + 默认约定,observe(Supplier) 包住
     * {@code callback.call},异常经 {@link ToolExecutionExceptionProcessor}(本仓
     * {@code CANCELLING_PROCESSOR}:取消类异常重新上抛中断 agent 线程,其余转模型可读文本)。
     *
     * <p>observation parent 装配:待接 trace 时启用
     * {@code ParentObservations.reactiveContextOrNull(this.observationRegistry)}(照抄
     * {@code ToolCallReactiveContextHolder.getContext().getOrDefault("micrometer.observation",
     * registry.getCurrentObservation())};当前全仓零 ObservationRegistry 消费,NOOP 空转,
     * 见 {@link ParentObservations})。
     */
    private String frameworkToolCall(AssistantMessage.ToolCall toolCall, ToolCallback toolCallback,
            String toolCallArguments, ToolContext toolContext) {
        Observation parentObservation = null; // = ParentObservations.reactiveContextOrNull(this.observationRegistry);

        ToolCallingObservationContext observationContext = ToolCallingObservationContext.builder()
                .toolDefinition(toolCallback.getToolDefinition())
                .toolMetadata(toolCallback.getToolMetadata())
                .toolCallId(toolCall.id())
                .toolType(toolCall.type())
                .toolCallArguments(toolCallArguments)
                .build();

        Observation observation = ToolCallingObservationDocumentation.TOOL_CALL
                .observation(this.observationConvention, DEFAULT_OBSERVATION_CONVENTION,
                        () -> observationContext, this.observationRegistry)
                .parentObservation(parentObservation);

        return observation.observe(() -> {
            String toolCallResult;
            try {
                toolCallResult = toolCallback.call(toolCallArguments, toolContext);
            }
            catch (ToolExecutionException e) {
                toolCallResult = this.toolExecutionExceptionProcessor.process(e);
            }
            observationContext.setToolCallResult(toolCallResult);
            return toolCallResult;
        });
    }

    // ════════════════════════ 项目切口:per-call 拦截链 ════════════════════════

    /**
     * 执行 per-call 拦截链(每次工具调用一遍):
     * <ul>
     *   <li>无 per-run 上下文(map 无 {@link #RUN_CONTEXT_KEY}:单测/裸用)或无拦截器
     *       → 纯框架路径(行为与 {@code DefaultToolCallingManager} 逐位一致);</li>
     *   <li>链尾 = {@code frameworkToolCall}(下行合成哨兵置位则跳过 observation + 真实调用,
     *       直接返回合成串——方案 B:短路仅当前调用,循环继续推进);</li>
     *   <li>RuntimeException 原样上抛(取消类异常穿透中断 agent 线程),受检异常包
     *       {@code RuntimeException}(拦截器 {@code invoke} 声明 throws Exception)。</li>
     * </ul>
     */
    private String executeWithPerCallChain(Prompt prompt, ChatResponse chatResponse, ToolContext toolContext,
            List<AssistantMessage.ToolCall> toolCalls, AssistantMessage.ToolCall toolCall,
            ToolCallback toolCallback, String toolCallArguments) {
        Object runCtx = toolContext.getContext().get(RUN_CONTEXT_KEY);
        if (!(runCtx instanceof RunContext run)) {
            return frameworkToolCall(toolCall, toolCallback, toolCallArguments, toolContext);
        }
        List<ToolExecutionInterceptor> interceptors = interceptorRegistry.sorted();
        if (interceptors.isEmpty()) {
            return frameworkToolCall(toolCall, toolCallback, toolCallArguments, toolContext);
        }
        PerCallToolExecutionContext ctx = new PerCallToolExecutionContext(run, prompt, chatResponse,
                toolCalls, toolCall, toolCallback, toolCallArguments);
        // 计时起点:下/上行切口间自然可得(startedAt/durationMs 供发射点与前端读秒)。
        ctx.startedAt(System.currentTimeMillis());
        ToolExecutionChain tail = c -> {
            String synthesized = c.synthesizedResult();
            if (synthesized != null) {
                // 方案 B:下行合成短路当前调用(跳过 observation + 真实调用),
                // 合成串沿框架 ToolResponse 构造路径流经全部上行改写节点。
                return synthesized;
            }
            return frameworkToolCall(c.toolCall(), c.toolCallback(), c.arguments(), toolContext);
        };
        try {
            return chainExecutor.runCall(interceptors, ctx, tail);
        }
        catch (RuntimeException e) {
            throw e;
        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ════════════════════════ 照抄:buildConversationHistoryAfterToolExecution ════════════════════════

    private List<Message> buildConversationHistoryAfterToolExecution(List<Message> messages,
            AssistantMessage assistantMessage, ToolResponseMessage toolResponseMessage) {
        List<Message> conversationHistory = new ArrayList<>(messages);
        conversationHistory.add(assistantMessage);
        conversationHistory.add(toolResponseMessage);
        return conversationHistory;
    }

    // ════════════════════════ 照抄:InternalToolExecutionResult 等价 record ════════════════════════

    /** 框架 {@code DefaultToolCallingManager$InternalToolExecutionResult} 的等价 record。 */
    private record InternalToolExecutionResult(ToolResponseMessage toolResponseMessage, boolean returnDirect) {
    }

    // ════════════════════════ per-call ctx 实现(ExecContext 槽位委托 RunContext) ════════════════════════

    /**
     * per-call 执行上下文:批级(prompt/chatResponse/toolCalls)与 per-call
     * (toolCall/toolCallback/arguments/合成哨兵/startedAt)成员 + ExecContext 槽位委托
     * {@code RunContext.execution};{@code emitter()} 覆写为 <b>agent 级</b>包装口
     * (链上事件自动携带归属 agentId,主/子一致;null 回退任务级口)。
     */
    static final class PerCallToolExecutionContext implements ToolExecutionContext {

        private final RunContext run;
        private final Prompt prompt;
        private final ChatResponse chatResponse;
        private final List<AssistantMessage.ToolCall> toolCalls;
        private final AssistantMessage.ToolCall toolCall;
        private final ToolCallback toolCallback;
        private String arguments;
        private String synthesized;
        private long startedAt;

        PerCallToolExecutionContext(RunContext run, Prompt prompt, ChatResponse chatResponse,
                List<AssistantMessage.ToolCall> toolCalls, AssistantMessage.ToolCall toolCall,
                ToolCallback toolCallback, String arguments) {
            this.run = run;
            this.prompt = prompt;
            this.chatResponse = chatResponse;
            this.toolCalls = toolCalls;
            this.toolCall = toolCall;
            this.toolCallback = toolCallback;
            this.arguments = arguments;
        }

        @Override public Prompt prompt() { return prompt; }
        @Override public ChatResponse chatResponse() { return chatResponse; }
        @Override public List<AssistantMessage.ToolCall> toolCalls() { return toolCalls; }
        @Override public AssistantMessage.ToolCall toolCall() { return toolCall; }
        @Override public ToolCallback toolCallback() { return toolCallback; }
        @Override public String arguments() { return arguments; }
        @Override public void arguments(String arguments) { this.arguments = arguments; }
        @Override public void synthesize(String result) { this.synthesized = result; }
        @Override public String synthesizedResult() { return synthesized; }
        @Override public long startedAt() { return startedAt; }
        @Override public void startedAt(long epochMs) { this.startedAt = epochMs; }
        @Override public ToolOutcomeLog toolOutcomes() { return run.toolOutcomes(); }
        @Override public String agentId() { return run.agentId(); }

        // ── ExecContext 槽位:委托 run.execution;emitter 覆写为 agent 级包装 ──

        @Override public EventEmitter emitter() {
            return run.emitter() != null ? run.emitter() : run.execution().emitter();
        }
        @Override public String subjectId() { return run.execution().subjectId(); }
        @Override public String workspaceRoot() { return run.execution().workspaceRoot(); }
        @Override public String workspaceId() { return run.execution().workspaceId(); }
        @Override public dev.everyagent.plugin.api.model.ModelConfig snapshot() {
            return run.execution().snapshot();
        }
        @Override public dev.everyagent.plugin.api.agent.AgentFactory agentFactory() {
            return run.execution().agentFactory();
        }
        @Override public Map<String, Object> metadata() { return run.execution().metadata(); }
        @Override public java.nio.file.Path dataDir() { return run.execution().dataDir(); }
        @Override public boolean terminal() { return run.execution().terminal(); }
        @Override public dev.everyagent.plugin.api.interaction.InteractionService interaction() {
            return run.execution().interaction();
        }
        @Override public Map<String, dev.everyagent.plugin.api.agent.AgentContext> agents() {
            return run.execution().agents();
        }
    }
}
