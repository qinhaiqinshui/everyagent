package dev.everyagent.worker.agent;

import dev.everyagent.plugin.api.agent.AgentContext;
import dev.everyagent.plugin.api.agent.AgentFactory;
import dev.everyagent.plugin.api.event.Events;
import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.plugin.api.interaction.InteractionService;
import dev.everyagent.plugin.api.model.EmitEvent;
import dev.everyagent.plugin.api.model.EventEmitter;
import dev.everyagent.plugin.api.model.ModelConfig;
import dev.everyagent.plugin.api.shell.ExecResults;
import dev.everyagent.plugin.api.spi.ToolExecutionChain;
import dev.everyagent.plugin.api.spi.ToolExecutionContext;
import dev.everyagent.plugin.api.spi.ToolExecutionInterceptor;
import dev.everyagent.plugin.api.spi.ToolOutcomeLog;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallLimitBehavior;
import org.springframework.ai.model.tool.ToolCallLimitExceededException;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PerCallToolCallingManager} 单测:照抄块行为与框架逐位对齐 + per-call 切口(方案 B)。
 *
 * <ul>
 *   <li>批内 N 调用逐个链执行(每次调用一遍链,顺序 = 下发顺序);</li>
 *   <li>下行合成短路(unattended 混合轮形态):哨兵跳过真实调用,循环继续;</li>
 *   <li>limits 两种 behavior(THROW 抛 / RETURN_ERROR_RESPONSE 合成错误结果继续);</li>
 *   <li>未知工具(resolutionFallback=false 抛 ISE;resolver 兜底走前缀文本);</li>
 *   <li>returnDirect AND 累积;异常经 processor 回灌(前缀文本);空参归一 "{}";</li>
 *   <li>tool.started / tool.result 事件时序与字段(含中断批:started 已发 result 缺失);</li>
 *   <li>出口 conversationHistory 聚合形态。</li>
 * </ul>
 */
class PerCallToolCallingManagerTest {

    private static final String FAILURE_PREFIX = ExecResults.TOOL_FAILURE_PREFIX;

    // ── 测试设施 ──

    /** 可编排的假回调:名字/返回串/returnDirect/抛异常。 */
    private static final class FakeToolCallback implements ToolCallback {

        final String name;
        final boolean returnDirect;
        String returns;
        RuntimeException throws_;
        final List<String> receivedArgs = new ArrayList<>();
        int calls;

        FakeToolCallback(String name, String returns) {
            this(name, returns, false);
        }

        FakeToolCallback(String name, String returns, boolean returnDirect) {
            this.name = name;
            this.returns = returns;
            this.returnDirect = returnDirect;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder().name(name).description("test").inputSchema("{}").build();
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return ToolMetadata.builder().returnDirect(returnDirect).build();
        }

        @Override
        public String call(String toolInput) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            calls++;
            receivedArgs.add(toolInput);
            if (throws_ != null) {
                throw new ToolExecutionException(getToolDefinition(), throws_);
            }
            return returns;
        }
    }

    /** 记录型链节点:下行记录顺序、可改写参数、可按名合成。 */
    private static final class RecordingInterceptor implements ToolExecutionInterceptor {

        final List<String> order = new ArrayList<>();
        /** 工具名 → 合成串(命中即 synthesize + proceed)。 */
        Map<String, String> synthesizeByName = Map.of();
        /** 工具名 → 改写后的入参。 */
        Map<String, String> rewriteArgs = Map.of();

        @Override
        public String id() {
            return "recording";
        }

        @Override
        public float order() {
            return 10f;
        }

        @Override
        public String invoke(ToolExecutionContext ctx, ToolExecutionChain next) throws Exception {
            order.add(ctx.toolCall().name());
            String synthesize = synthesizeByName.get(ctx.toolCall().name());
            if (synthesize != null) {
                ctx.synthesize(synthesize);
            }
            String rewrite = rewriteArgs.get(ctx.toolCall().name());
            if (rewrite != null) {
                ctx.arguments(rewrite);
            }
            return next.proceed(ctx);
        }
    }

    /** 无 run 上下文(纯框架路径)的异常处理器:异常转前缀文本。 */
    private static final ToolExecutionExceptionProcessor FAILING_PROCESSOR = ex -> {
        Throwable cause = ex.getCause();
        return FAILURE_PREFIX + " " + (cause == null ? "boom" : cause.getMessage());
    };

    private static final class Run implements ExecContext {

        final RecordingEmitter emitter = new RecordingEmitter();
        final ToolOutcomeLog outcomes = new ToolOutcomeLog();
        final AtomicBoolean terminal = new AtomicBoolean(false);

        @Override public String subjectId() { return "t1"; }
        @Override public String workspaceRoot() { return "C:\\ws"; }
        @Override public String workspaceId() { return "ws1"; }
        @Override public ModelConfig snapshot() { return null; }
        @Override public EventEmitter emitter() { return emitter; }
        @Override public AgentFactory agentFactory() { return null; }
        @Override public Map<String, Object> metadata() { return Map.of(); }
        @Override public Path dataDir() { return null; }
        @Override public boolean terminal() { return terminal.get(); }
        @Override public InteractionService interaction() { return null; }
        @Override public Map<String, AgentContext> agents() { return Map.of(); }
    }

    private static final class RecordingEmitter implements EventEmitter {

        final List<EmitEvent> events = new ArrayList<>();

        @Override
        public long emit(EmitEvent event) {
            events.add(event);
            return event.id();
        }

        List<EmitEvent> ofKind(String kind) {
            return events.stream().filter(e -> kind.equals(e.kind())).toList();
        }
    }

    /** 永不命中的 resolver(fallback 由 manager 显式关闭时用于未知工具 ISE 断言)。 */
    private static final ToolCallbackResolver NULL_RESOLVER = name -> null;

    private static ToolCallingManager manager(ToolExecutionInterceptor... interceptors) {
        var registry = new dev.everyagent.worker.plugin.registry.ToolExecutionInterceptorRegistry();
        for (ToolExecutionInterceptor i : interceptors) {
            registry.register(i);
        }
        return manager(registry, true, 200, 500, ToolCallLimitBehavior.THROW);
    }

    private static ToolCallingManager manager(
            dev.everyagent.worker.plugin.registry.ToolExecutionInterceptorRegistry registry,
            boolean fallback, int maxPerTool, int maxTotal, ToolCallLimitBehavior behavior) {
        return new PerCallToolCallingManager(NULL_RESOLVER, FAILING_PROCESSOR,
                new ToolCallLimits(maxPerTool, Map.of(), java.util.Set.of(), maxTotal, behavior),
                fallback, registry);
    }

    private record Built(ToolCallingManager tcm, Run run, List<ToolCallback> callbacks) {

        Prompt prompt(List<Message> instructions) {
            OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
                    .toolCallbacks(callbacks.toArray(new ToolCallback[0]))
                    .toolContext(PerCallToolCallingManager.RUN_CONTEXT_KEY,
                            new PerCallToolCallingManager.RunContext(run.emitter, run, run.outcomes));
            return new Prompt(instructions, options.build());
        }

        ChatResponse response(AssistantMessage assistant) {
            return new ChatResponse(List.of(new Generation(assistant)));
        }
    }

    private static Built built(ToolCallingManager tcm, Run run, ToolCallback... callbacks) {
        return new Built(tcm, run, List.of(callbacks));
    }

    private static AssistantMessage toolCallMessage(String... idNamePairs) {
        List<AssistantMessage.ToolCall> calls = new ArrayList<>();
        for (int i = 0; i < idNamePairs.length; i += 2) {
            calls.add(new AssistantMessage.ToolCall(idNamePairs[i], "function",
                    idNamePairs[i + 1], "{\"arg\":1}"));
        }
        return AssistantMessage.builder().content("calls").toolCalls(calls).build();
    }

    private static ToolResponseMessage lastToolResponse(ToolExecutionResult result) {
        List<Message> history = result.conversationHistory();
        return (ToolResponseMessage) history.get(history.size() - 1);
    }

    // ── 用例 ──

    @Test
    void executesEachCallThroughChainInOrder() {
        RecordingInterceptor recorder = new RecordingInterceptor();
        FakeToolCallback a = new FakeToolCallback("alpha", "A");
        FakeToolCallback b = new FakeToolCallback("beta", "B");
        FakeToolCallback c = new FakeToolCallback("gamma", "C");
        Built built = built(manager(new ToolEventInterceptor(), recorder), new Run(), a, b, c);

        ToolExecutionResult result = built.tcm.executeToolCalls(
                built.prompt(List.of(new org.springframework.ai.chat.messages.UserMessage("go"))),
                built.response(toolCallMessage("c1", "alpha", "c2", "beta", "c3", "gamma")));

        // 批内 3 调用逐个链执行(每次调用一遍链,顺序 = 下发顺序)
        assertEquals(List.of("alpha", "beta", "gamma"), recorder.order);
        assertEquals(1, a.calls);
        assertEquals(1, b.calls);
        assertEquals(1, c.calls);
        // 结果配对(id/name/data 全对齐)
        ToolResponseMessage trm = lastToolResponse(result);
        assertEquals(List.of("A", "B", "C"),
                trm.getResponses().stream().map(ToolResponseMessage.ToolResponse::responseData).toList());
        assertEquals(List.of("c1", "c2", "c3"),
                trm.getResponses().stream().map(ToolResponseMessage.ToolResponse::id).toList());
        // 出口聚合形态:instructions + assistant + toolResponseMessage
        List<Message> history = result.conversationHistory();
        assertEquals(3, history.size());
        assertInstanceOf(org.springframework.ai.chat.messages.UserMessage.class, history.get(0));
        assertInstanceOf(AssistantMessage.class, history.get(1));
        assertInstanceOf(ToolResponseMessage.class, history.get(2));
        assertFalse(result.returnDirect(), "全部非 returnDirect → false");
    }

    @Test
    void synthesizeShortCircuitsOnlyCurrentCallInMixedBatch() {
        // unattended 混合轮形态:ask_user 合成 + 其他工具真执行
        RecordingInterceptor recorder = new RecordingInterceptor();
        recorder.synthesizeByName = Map.of("ask_user", "题干：首选项");
        FakeToolCallback askUser = new FakeToolCallback("ask_user", "REAL_ASK");
        FakeToolCallback file = new FakeToolCallback("read_file", "file content");
        Run run = new Run();
        Built built = built(manager(new ToolEventInterceptor(), recorder), run, askUser, file);

        ToolExecutionResult result = built.tcm.executeToolCalls(
                built.prompt(List.of(new org.springframework.ai.chat.messages.UserMessage("go"))),
                built.response(toolCallMessage("c1", "ask_user", "c2", "read_file")));

        // 合成跳过真实调用(ask_user 回调零调用),read_file 真执行,循环继续推进
        assertEquals(0, askUser.calls, "合成短路:ask_user 不真实执行");
        assertEquals(1, file.calls, "混合轮:其余调用照常真执行");
        assertEquals(List.of("ask_user", "read_file"), recorder.order, "循环不被终止");
        ToolResponseMessage trm = lastToolResponse(result);
        assertEquals("题干：首选项", trm.getResponses().get(0).responseData());
        assertEquals("file content", trm.getResponses().get(1).responseData());
        // 发射照常:两个调用都有 started/result(合成的也有)
        assertEquals(2, run.emitter.ofKind(Events.TOOL_STARTED).size());
        assertEquals(2, run.emitter.ofKind(Events.TOOL_RESULT).size());
    }

    @Test
    void downlinkArgumentRewriteReachesRealCall() {
        RecordingInterceptor recorder = new RecordingInterceptor();
        recorder.rewriteArgs = Map.of("alpha", "{\"rewritten\":true}");
        FakeToolCallback a = new FakeToolCallback("alpha", "A");
        Built built = built(manager(recorder), new Run(), a);

        built.tcm.executeToolCalls(
                built.prompt(List.of(new org.springframework.ai.chat.messages.UserMessage("go"))),
                built.response(toolCallMessage("c1", "alpha")));

        assertEquals(List.of("{\"rewritten\":true}"), a.receivedArgs, "下行改写参数对真实执行生效");
    }

    @Test
    void limitsThrowBehaviorRaisesWithPartialResult() {
        var registry = new dev.everyagent.worker.plugin.registry.ToolExecutionInterceptorRegistry();
        registry.register(new ToolEventInterceptor());
        ToolCallingManager tcm = manager(registry, true, 1, 500, ToolCallLimitBehavior.THROW);
        FakeToolCallback a = new FakeToolCallback("alpha", "A");
        Run run = new Run();
        Built built = built(tcm, run, a);

        // 第二次同工具调用 → 超 per-tool 限(1)
        ToolCallLimitExceededException ex = assertThrows(ToolCallLimitExceededException.class,
                () -> built.tcm.executeToolCalls(
                        built.prompt(List.of(new org.springframework.ai.chat.messages.UserMessage("go"),
                                toolCallMessage("c1", "alpha"),
                                toolResponseMessage("c1", "alpha", "A"))),
                        built.response(toolCallMessage("c2", "alpha"))));

        assertEquals("alpha", ex.getToolName());
        assertEquals(1, ex.getLimit());
        assertNotNull(ex.getPartialToolExecutionResult(), "携带部分结果的续接材料");
        assertEquals("c2", lastToolResponse(ex.getPartialToolExecutionResult())
                .getResponses().get(0).id(), "超限调用已配对合成错误结果");
        assertTrue(lastToolResponse(ex.getPartialToolExecutionResult())
                .getResponses().get(0).responseData().contains("Tool call limit"));
        assertEquals(0, a.calls, "超限调用不执行真实工具");
    }

    @Test
    void limitsReturnErrorResponseBehaviorSynthesizesAndContinues() {
        var registry = new dev.everyagent.worker.plugin.registry.ToolExecutionInterceptorRegistry();
        ToolCallingManager tcm = manager(registry, true, 1, 500,
                ToolCallLimitBehavior.RETURN_ERROR_RESPONSE);
        FakeToolCallback a = new FakeToolCallback("alpha", "A");
        FakeToolCallback b = new FakeToolCallback("beta", "B");
        Built built = built(tcm, new Run(), a, b);

        // 本 turn 已有一次 alpha(countPriorToolCalls 数历史 ToolResponse)→ 本轮 alpha 第二次超限
        ToolExecutionResult result = built.tcm.executeToolCalls(
                built.prompt(List.of(new org.springframework.ai.chat.messages.UserMessage("go"),
                        toolCallMessage("c0", "alpha"),
                        toolResponseMessage("c0", "alpha", "A"))),
                built.response(toolCallMessage("c1", "alpha", "c2", "beta")));

        ToolResponseMessage trm = lastToolResponse(result);
        assertEquals(0, a.calls, "超限调用合成错误结果,不执行");
        assertTrue(trm.getResponses().get(0).responseData().contains("Tool call limit"),
                "RETURN_ERROR_RESPONSE:合成错误文本作为结果");
        assertEquals("B", trm.getResponses().get(1).responseData(), "循环继续,后续调用照常执行");
        assertEquals(1, b.calls);
    }

    @Test
    void unknownToolWithoutFallbackThrowsIllegalState() {
        var registry = new dev.everyagent.worker.plugin.registry.ToolExecutionInterceptorRegistry();
        ToolCallingManager tcm = manager(registry, false, 200, 500, ToolCallLimitBehavior.THROW);
        Built built = built(tcm, new Run());

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> built.tcm.executeToolCalls(
                        built.prompt(List.of(new org.springframework.ai.chat.messages.UserMessage("go"))),
                        built.response(toolCallMessage("c1", "nope"))));
        assertTrue(ex.getMessage().contains("No ToolCallback found for tool name: nope"));
    }

    @Test
    void resolverFallbackSynthesizesErrorTextAsNormalResult() {
        // fallback=true + resolver 返回兜底回调(MissingToolCallbackResolver 生产形态):
        // 未知工具降级为一次普通调用,错误文本作为工具结果回传模型(不中断任务)
        var registry = new dev.everyagent.worker.plugin.registry.ToolExecutionInterceptorRegistry();
        registry.register(new ToolEventInterceptor());
        ToolCallingManager tcm = new PerCallToolCallingManager(
                name -> new dev.everyagent.worker.tools.MissingToolCallbackResolver().resolve(name),
                FAILING_PROCESSOR,
                new ToolCallLimits(200, Map.of(), java.util.Set.of(), 500, ToolCallLimitBehavior.THROW),
                true, registry);
        Run run = new Run();
        Built built = built(tcm, run);

        ToolExecutionResult result = built.tcm.executeToolCalls(
                built.prompt(List.of(new org.springframework.ai.chat.messages.UserMessage("go"))),
                built.response(toolCallMessage("c1", "nope")));

        String data = lastToolResponse(result).getResponses().get(0).responseData();
        assertTrue(data.startsWith(FAILURE_PREFIX), "兜底文本带失败前缀: " + data);
        // 前缀判定 → tool.result status=error,且按 callId 写账
        EmitEvent resultEvent = run.emitter.ofKind(Events.TOOL_RESULT).get(0);
        assertEquals("error", ((tools.jackson.databind.node.ObjectNode) resultEvent.data()).path("status").asText());
        assertEquals(Boolean.TRUE, run.outcomes.callOutcome("c1"), "链上行按 callId 写账");
    }

    @Test
    void returnDirectIsAndAccumulatedAcrossBatch() {
        FakeToolCallback yes = new FakeToolCallback("yes", "Y", true);
        FakeToolCallback no = new FakeToolCallback("no", "N", false);
        FakeToolCallback yes2 = new FakeToolCallback("yes2", "Y2", true);
        Built built = built(manager(), new Run(), yes, no, yes2);

        ToolExecutionResult result = built.tcm.executeToolCalls(
                built.prompt(List.of(new org.springframework.ai.chat.messages.UserMessage("go"))),
                built.response(toolCallMessage("c1", "yes", "c2", "no", "c3", "yes2")));

        assertFalse(result.returnDirect(), "首轮记 true、后续 AND 累积(false 传染)");
        // 纯 returnDirect 批
        Built allDirect = built(manager(), new Run(), yes, yes2);
        assertTrue(allDirect.tcm.executeToolCalls(
                allDirect.prompt(List.of(new org.springframework.ai.chat.messages.UserMessage("go"))),
                allDirect.response(toolCallMessage("c1", "yes", "c2", "yes2"))).returnDirect());
    }

    @Test
    void toolExecutionExceptionIsFedBackAsPrefixedText() {
        FakeToolCallback broken = new FakeToolCallback("broken", "never");
        broken.throws_ = new RuntimeException("disk full");
        Built built = built(manager(), new Run(), broken);

        ToolExecutionResult result = built.tcm.executeToolCalls(
                built.prompt(List.of(new org.springframework.ai.chat.messages.UserMessage("go"))),
                built.response(toolCallMessage("c1", "broken")));

        assertEquals(FAILURE_PREFIX + " disk full",
                lastToolResponse(result).getResponses().get(0).responseData(),
                "异常经 processor 转模型可读文本回灌(不中断任务)");
    }

    @Test
    void blankArgumentsNormalizedToEmptyJson() {
        FakeToolCallback a = new FakeToolCallback("alpha", "A");
        Run run = new Run();
        Built built = built(manager(new ToolEventInterceptor()), run, a);
        AssistantMessage assistant = AssistantMessage.builder().content("calls")
                .toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", "alpha", "  "))).build();

        built.tcm.executeToolCalls(
                built.prompt(List.of(new org.springframework.ai.chat.messages.UserMessage("go"))),
                built.response(assistant));

        assertEquals(List.of("{}"), a.receivedArgs, "空参归一为 {}");
        assertEquals("{}", ((tools.jackson.databind.node.ObjectNode)
                        run.emitter.ofKind(Events.TOOL_STARTED).get(0).data()).path("arguments").asText(),
                "事件侧同样携带归一后的入参");
    }

    @Test
    void emitsStartedThenResultPerCallWithFields() {
        FakeToolCallback slow = new FakeToolCallback("slow", "done");
        Run run = new Run();
        Built built = built(manager(new ToolEventInterceptor()), run, slow);

        ToolExecutionResult result = built.tcm.executeToolCalls(
                built.prompt(List.of(new org.springframework.ai.chat.messages.UserMessage("go"))),
                built.response(toolCallMessage("c1", "slow")));

        // 时序:started 先于 result,且各自字段齐全
        List<EmitEvent> all = run.emitter.events;
        assertEquals(Events.TOOL_STARTED, all.get(0).kind());
        assertEquals(Events.TOOL_RESULT, all.get(1).kind());
        EmitEvent started = all.get(0);
        assertFalse(started.persist(), "started 瞬态不落盘");
        tools.jackson.databind.node.ObjectNode startedData =
                (tools.jackson.databind.node.ObjectNode) started.data();
        assertEquals("c1", startedData.path("callId").asText());
        assertEquals("slow", startedData.path("name").asText());
        assertEquals("{\"arg\":1}", startedData.path("arguments").asText());
        assertTrue(startedData.path("startedAt").asLong() > 0);
        EmitEvent resultEvent = all.get(1);
        assertTrue(resultEvent.persist(), "result 落盘");
        assertEquals("done", resultEvent.summary(), "结果串原样(content 不截断)");
        tools.jackson.databind.node.ObjectNode resultData =
                (tools.jackson.databind.node.ObjectNode) resultEvent.data();
        assertEquals("c1", resultData.path("callId").asText());
        assertEquals("success", resultData.path("status").asText());
        assertEquals(startedData.path("startedAt").asLong(), resultData.path("startedAt").asLong(),
                "startedAt 在两帧间一致");
        assertTrue(resultData.path("durationMs").asLong() >= 0);
        // agentId 归属:agent 级包装口自动填(Run emitter 无包装时为 null,由 task 层兜底主 agent)
    }

    @Test
    void interruptedBatchEmitsStartedButNoResult() {
        // 中断批:执行中收口(terminal 翻转)→ started 已发、result 被 leak-guard 吞
        FakeToolCallback a = new FakeToolCallback("alpha", "A");
        Run run = new Run();
        // 切口记录型节点:在 next.proceed 内把主体置为收口(模拟取消竞态);
        // order=600 > 发射节点(500) → 位于发射节点内侧,上行先于发射节点执行
        ToolExecutionInterceptor canceller = new ToolExecutionInterceptor() {
            @Override public String id() { return "canceller"; }
            @Override public float order() { return 600f; }
            @Override public String invoke(ToolExecutionContext ctx, ToolExecutionChain next) throws Exception {
                String out = next.proceed(ctx);
                run.terminal.set(true); // 上行时已收口
                return out;
            }
        };
        Built built = built(manager(new ToolEventInterceptor(), canceller), run, a);

        ToolExecutionResult result = built.tcm.executeToolCalls(
                built.prompt(List.of(new org.springframework.ai.chat.messages.UserMessage("go"))),
                built.response(toolCallMessage("c1", "alpha")));

        assertEquals("A", lastToolResponse(result).getResponses().get(0).responseData(),
                "真实结果仍回灌模型(执行已完成,不可撤销)");
        assertEquals(1, run.emitter.ofKind(Events.TOOL_STARTED).size(), "started 已发");
        assertEquals(0, run.emitter.ofKind(Events.TOOL_RESULT).size(),
                "收口后 result 缺失(中断批前端形态:执行中被中断)");
    }

    @Test
    void terminalBeforeStartEmitsNothing() {
        FakeToolCallback a = new FakeToolCallback("alpha", "A");
        Run run = new Run();
        run.terminal.set(true);
        Built built = built(manager(new ToolEventInterceptor()), run, a);

        built.tcm.executeToolCalls(
                built.prompt(List.of(new org.springframework.ai.chat.messages.UserMessage("go"))),
                built.response(toolCallMessage("c1", "alpha")));

        assertTrue(run.emitter.events.isEmpty(), "收口后不发任何工具事件");
    }

    @Test
    void withoutRunContextBehavesAsPlainFrameworkPath() {
        // 单测/裸用:ToolContext map 无 RUN_CONTEXT_KEY → 纯框架路径,行为与
        // DefaultToolCallingManager 一致(链不执行,事件不发)
        RecordingInterceptor recorder = new RecordingInterceptor();
        FakeToolCallback a = new FakeToolCallback("alpha", "A");
        ToolCallingManager tcm = manager(recorder);
        Run run = new Run();
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .toolCallbacks(a).build(); // 无 toolContext
        Prompt prompt = new Prompt(List.of(new org.springframework.ai.chat.messages.UserMessage("go")), options);

        ToolExecutionResult result = tcm.executeToolCalls(prompt,
                new ChatResponse(List.of(new Generation(toolCallMessage("c1", "alpha")))));

        assertEquals("A", lastToolResponse(result).getResponses().get(0).responseData());
        assertEquals(0, recorder.order.size(), "无 run 上下文不执行链");
        assertEquals(1, a.calls);
    }

    @Test
    void noToolCallRequestedThrowsIllegalState() {
        Built built = built(manager(), new Run());
        assertThrows(IllegalStateException.class, () -> built.tcm.executeToolCalls(
                built.prompt(List.of(new org.springframework.ai.chat.messages.UserMessage("go"))),
                built.response(new AssistantMessage("plain text"))));
    }

    // ── 辅助 ──

    private static ToolResponseMessage toolResponseMessage(String id, String name, String data) {
        return ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(id, name, data))).build();
    }
}
