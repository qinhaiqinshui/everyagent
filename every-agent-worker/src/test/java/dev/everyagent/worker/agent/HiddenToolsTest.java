package dev.everyagent.worker.agent;

import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.plugin.api.spi.ToolContext;
import dev.everyagent.plugin.api.spi.ToolProvider;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.plugin.registry.AdvisorProviderRegistry;
import dev.everyagent.worker.plugin.registry.ToolProviderRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * hidden 工具机制(步骤 3)单测:
 * <ul>
 *   <li><b>聚合与请求组装</b>:AgentBuilder 聚合 createHiddenTools 为独立池(键=工具名),
 *       不进请求 tool 定义;AgentRunner 把池放 prompt options 框架 ToolContext map 独立键
 *       {@code everyagent.hiddenTools}(与 RUN_CONTEXT_KEY 同 map 共存、互不可见);</li>
 *   <li><b>解析无串扰</b>:共享单例 PerCallToolCallingManager 上并发两请求各自 hidden 池,
 *       解析查本请求池;他请求池的工具名不命中;</li>
 *   <li><b>soft-fail 回归</b>:hidden 池在位时未知工具名仍走 MissingToolCallbackResolver 兜底。</li>
 * </ul>
 */
class HiddenToolsTest {

    // ── 测试设施 ──

    /** 名字可控的最小工具回调(hidden 池断言只关心名字与身份)。 */
    private static final class NamedCallback implements ToolCallback {

        final String name;
        final String returns;
        int calls;
        /** 并发用:非 null 时先在屏障会合再返回(证明两请求同时 in-flight)。 */
        CyclicBarrier rendezvous;

        NamedCallback(String name, String returns) {
            this.name = name;
            this.returns = returns;
        }

        @Override public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder().name(name).description("test")
                    .inputSchema("{\"type\":\"object\"}").build();
        }

        @Override public ToolMetadata getToolMetadata() {
            return ToolMetadata.builder().build();
        }

        @Override public String call(String toolInput) {
            throw new UnsupportedOperationException();
        }

        @Override public String call(String toolInput,
                org.springframework.ai.chat.model.ToolContext toolContext) {
            calls++;
            if (rendezvous != null) {
                try {
                    // 超时即抛异常(证明两请求未能同时 in-flight),带异常信息冒泡
                    rendezvous.await(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
            return returns;
        }
    }

    /** 一个常规工具 + 两个 hidden 工具的 provider。 */
    private static final class MixedProvider implements ToolProvider {

        final NamedCallback regular = new NamedCallback("fs_read", "R");
        final NamedCallback hiddenA = new NamedCallback("run_agent", "A-pool");
        final NamedCallback hiddenB = new NamedCallback("list_agents", "B-pool");

        @Override public String pluginId() { return "test"; }

        @Override public List<ToolCallback> createTools(ToolContext ctx) {
            return List.of(regular);
        }

        @Override public List<ToolCallback> createHiddenTools(ToolContext ctx) {
            return List.of(hiddenA, hiddenB);
        }
    }

    private static AgentBuilder agentBuilder(ToolProviderRegistry tools, WorkerProperties props) {
        return new AgentBuilder(tools, new AdvisorProviderRegistry(),
                mock(ToolCallingManager.class), props,
                null, null, null, null, null, null, mock(AgentRunner.class), null);
    }

    private static ExecContext exec() {
        ExecContext exec = mock(ExecContext.class);
        when(exec.workspaceRoot()).thenReturn(null); // 跳过沙箱路径注册
        when(exec.agents()).thenReturn(new HashMap<>());
        when(exec.emitter()).thenReturn(e -> 1L);
        return exec;
    }

    /** ChatModel 桩:getOptions 返回 OpenAiChatOptions(避免 ChatClient 装配期拿到 null)。 */
    private static ChatModel chatModel() {
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(OpenAiChatOptions.builder().build());
        return model;
    }

    /** ChatClient 深桩:捕获 prompt 并返回立即完成的流(同 AgentRunnerWatchdogTest 形态)。 */
    private static ChatClient capturingChatClient(List<Prompt> captured) {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec stream = mock(ChatClient.StreamResponseSpec.class);
        when(chatClient.prompt(any(Prompt.class))).thenAnswer(inv -> {
            captured.add(inv.getArgument(0));
            return spec;
        });
        when(spec.stream()).thenReturn(stream);
        when(stream.chatClientResponse()).thenReturn(Flux.empty());
        return chatClient;
    }

    private static List<String> requestToolNames(Prompt prompt) {
        OpenAiChatOptions options = (OpenAiChatOptions) prompt.getOptions();
        return options.getToolCallbacks().stream()
                .map(tc -> tc.getToolDefinition().name()).toList();
    }

    // ── ① 聚合 + 请求组装:hidden 不进请求 tool 定义,随独立键下行 ──

    @Test
    void hiddenPoolStaysOutOfRequestToolsAndRidesPromptOptions() throws Exception {
        MixedProvider provider = new MixedProvider();
        ToolProviderRegistry registry = new ToolProviderRegistry();
        registry.register(provider);
        WorkerProperties props = new WorkerProperties();

        AgentEntity entity = agentBuilder(registry, props)
                .create("a_main", chatModel(), OpenAiChatOptions.builder().build(),
                        exec(), "cfg1")
                .build();

        // 聚合产物:常规池只含常规工具;hidden 池独立、键=工具名
        assertThat(entity.tools).extracting(tc -> tc.getToolDefinition().name())
                .containsExactly("fs_read");
        assertThat(entity.hiddenTools.keySet())
                .containsExactlyInAnyOrder("run_agent", "list_agents");

        // 请求组装:捕获 AgentRunner 下发的 Prompt
        List<Prompt> captured = new ArrayList<>();
        entity.chatClient = capturingChatClient(captured);
        new AgentRunner(props).run(entity);

        assertThat(captured).hasSize(1);
        OpenAiChatOptions options = (OpenAiChatOptions) captured.get(0).getOptions();
        // hidden 工具名不进请求 tool 定义(schema 无从下发,模型按 skill 文档认知)
        assertThat(requestToolNames(captured.get(0))).containsExactly("fs_read");
        // 两键同 map 共存、互不可见:hidden 池在独立键,值为键=工具名的 map
        Map<String, Object> ctxMap = options.getToolContext();
        assertThat(ctxMap).containsKey(PerCallToolCallingManager.RUN_CONTEXT_KEY);
        assertThat(ctxMap).containsKey(PerCallToolCallingManager.HIDDEN_TOOLS_KEY);
        assertThat(ctxMap.get(PerCallToolCallingManager.HIDDEN_TOOLS_KEY))
                .isEqualTo(entity.hiddenTools);
        assertThat(provider.hiddenA.calls).isZero(); // 只组装未调用
    }

    // ── ② 并发两请求 hidden 池无串扰(解析查本请求池) ──

    @Test
    void concurrentRequestsResolveTheirOwnHiddenPools() throws Exception {
        // 共享单例 TCM(生产形态:全 worker 一个实例),两个请求各带自己的 hidden 池
        var interceptorRegistry = new dev.everyagent.worker.plugin.registry.ToolExecutionInterceptorRegistry();
        ToolCallingManager tcm = new PerCallToolCallingManager(
                name -> new dev.everyagent.worker.tools.MissingToolCallbackResolver().resolve(name),
                ex -> "[工具执行失败] " + ex.getMessage(),
                new ToolCallLimits(200, Map.of(), java.util.Set.of(), 500,
                        org.springframework.ai.model.tool.ToolCallLimitBehavior.THROW),
                true, interceptorRegistry);

        NamedCallback aOnly = new NamedCallback("run_agent", "A-pool");
        NamedCallback bOnly = new NamedCallback("stop_agent", "B-pool");
        // 屏障(2):两回调都 in-flight 才放行——证明并发期解析也各查各池
        CyclicBarrier barrier = new CyclicBarrier(2);
        aOnly.rendezvous = barrier;
        bOnly.rendezvous = barrier;

        Prompt promptA = promptWithHidden(Map.of("run_agent", aOnly));
        Prompt promptB = promptWithHidden(Map.of("stop_agent", bOnly));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> resultA = pool.submit(() -> executeHiddenCall(tcm, promptA, "run_agent"));
            Future<String> resultB = pool.submit(() -> executeHiddenCall(tcm, promptB, "stop_agent"));
            // 各自池命中:A 拿 A-pool、B 拿 B-pool;屏障保证两调用确曾同时 in-flight
            assertThat(resultA.get(10, TimeUnit.SECONDS)).isEqualTo("A-pool");
            assertThat(resultB.get(10, TimeUnit.SECONDS)).isEqualTo("B-pool");

            // 交叉:promptA 里调用只存在于 promptB 池的工具名 → 不串扰,soft-fail 兜底
            String cross = executeHiddenCall(tcm, promptA, "stop_agent");
            assertThat(cross).startsWith("[工具执行失败]")
                    .contains("No ToolCallback found for tool name: stop_agent");
            assertThat(bOnly.calls).isEqualTo(1); // 只被本池请求执行过一次(并发那次)
        } finally {
            pool.shutdownNow();
        }
    }

    // ── ③ 未知工具名仍 soft-fail(hidden 池在位也不改变 MissingToolCallbackResolver 语义) ──

    @Test
    void unknownToolStillSoftFailsWhenHiddenPoolPresent() {
        var interceptorRegistry = new dev.everyagent.worker.plugin.registry.ToolExecutionInterceptorRegistry();
        ToolCallingManager tcm = new PerCallToolCallingManager(
                name -> new dev.everyagent.worker.tools.MissingToolCallbackResolver().resolve(name),
                ex -> "[工具执行失败] " + ex.getMessage(),
                new ToolCallLimits(200, Map.of(), java.util.Set.of(), 500,
                        org.springframework.ai.model.tool.ToolCallLimitBehavior.THROW),
                true, interceptorRegistry);

        NamedCallback hidden = new NamedCallback("run_agent", "A-pool");
        Prompt prompt = promptWithHidden(Map.of("run_agent", hidden));

        String data = executeHiddenCall(tcm, prompt, "nope");
        assertThat(data).startsWith("[工具执行失败]")
                .contains("No ToolCallback found for tool name: nope");
        assertThat(hidden.calls).isZero();
    }

    // ── TCM 级辅助 ──

    private static Prompt promptWithHidden(Map<String, ToolCallback> hiddenPool) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .toolCallbacks(new ToolCallback[0])
                .toolContext(PerCallToolCallingManager.HIDDEN_TOOLS_KEY, hiddenPool)
                .build();
        return new Prompt(List.of(new org.springframework.ai.chat.messages.UserMessage("go")), options);
    }

    /** 在给定 prompt 上执行一次指定名的工具调用,返回结果串(异常冒泡为断言材料)。 */
    private static String executeHiddenCall(ToolCallingManager tcm, Prompt prompt, String toolName) {
        AssistantMessage assistant = AssistantMessage.builder().content("calls")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-" + toolName, "function", toolName, "{}")))
                .build();
        ChatResponse response = new ChatResponse(List.of(new Generation(assistant)));
        var result = tcm.executeToolCalls(prompt, response);
        List<Message> history = result.conversationHistory();
        org.springframework.ai.chat.messages.ToolResponseMessage trm =
                (org.springframework.ai.chat.messages.ToolResponseMessage) history.get(history.size() - 1);
        return trm.getResponses().get(0).responseData();
    }
}
