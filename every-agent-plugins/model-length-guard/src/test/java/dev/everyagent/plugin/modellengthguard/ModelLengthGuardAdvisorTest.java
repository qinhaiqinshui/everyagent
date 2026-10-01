package dev.everyagent.plugin.modellengthguard;

import dev.everyagent.plugin.api.agent.AgentActivity;
import dev.everyagent.plugin.api.agent.AgentContext;
import dev.everyagent.plugin.api.config.WorkerConfig;
import dev.everyagent.plugin.api.event.EventLogReader;
import dev.everyagent.plugin.api.event.EventRecord;
import dev.everyagent.plugin.api.event.Usage;
import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.plugin.api.model.EventEmitter;
import dev.everyagent.plugin.api.model.ModelConfig;
import dev.everyagent.plugin.api.spi.TokenEstimator;
import dev.everyagent.plugin.api.task.FileChangesCollector;
import dev.everyagent.plugin.api.task.TaskRuntime;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.SocketException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ModelLengthGuardAdvisor} 单测:
 * 「输出≈maxTokens」粗估判定(nearMax)、网络级错误判定(isNetworkError)的纯函数验证;
 * 以及流式收口路径(断流/远未满额断流)的 StepVerifier 验证——provider 在输出预算
 * 耗尽处粗暴断开 SSE(不发 finish_reason=length)时应先下发合成 finish_reason=length 帧,
 * 再转换为非重试的 {@link ModelLengthExhaustedException},避免外层瞬时重试
 * 重新整段长思考再次占满预算的循环。
 *
 * <p>约束(§14.9):插件对 worker 任何 scope 零依赖,测试桩在本类内自建
 * (实现 plugin-api 接口的等价类),不引用 worker 的 AgentEntity/TaskEntry/WorkerProperties。
 */
class ModelLengthGuardAdvisorTest {

    /** 测试桩:使用与原静态方法完全相同的 CJK 粗估公式,factor 恒 1.0。 */
    private static final TokenEstimator STUB_ESTIMATOR = new TokenEstimator() {
        @Override
        public long estimate(String text, String configId) {
            return rawEstimate(text);
        }

        @Override
        public void calibrate(String configId, long estimatedTokens, long actualTokens) {
            // 测试不校准
        }

        @Override
        public double factorOf(String configId) {
            return 1.0;
        }

        @Override
        public long sampleCountOf(String configId) {
            return 0;
        }

        private long rawEstimate(String s) {
            if (s == null || s.isEmpty()) {
                return 0;
            }
            long cjk = 0;
            long other = 0;
            for (int i = 0; i < s.length(); ) {
                int cp = s.codePointAt(i);
                i += Character.charCount(cp);
                Character.UnicodeScript sc = Character.UnicodeScript.of(cp);
                boolean isCjk = sc == Character.UnicodeScript.HAN
                        || sc == Character.UnicodeScript.HIRAGANA
                        || sc == Character.UnicodeScript.KATAKANA
                        || sc == Character.UnicodeScript.HANGUL;
                if (isCjk) {
                    cjk++;
                } else if (!Character.isWhitespace(cp) && !Character.isISOControl(cp)) {
                    other++;
                }
            }
            return cjk + (other + 3) / 4;
        }
    };

    @Test
    void nearMaxBounds() {
        long max = 65536;
        // 80%~140% 区间内判耗尽
        assertTrue(ModelLengthGuardAdvisor.nearMax(max * 4 / 5, max), "80% 下边界");
        assertTrue(ModelLengthGuardAdvisor.nearMax(max, max), "100%");
        assertTrue(ModelLengthGuardAdvisor.nearMax(max * 7 / 5, max), "140% 上边界");
        // 区间外不判耗尽
        assertFalse(ModelLengthGuardAdvisor.nearMax(max / 2, max), "50% 远未耗尽");
        assertFalse(ModelLengthGuardAdvisor.nearMax(max * 2, max), "200% 估算偏差过大");
        assertFalse(ModelLengthGuardAdvisor.nearMax(0, max), "0 输出");
        assertFalse(ModelLengthGuardAdvisor.nearMax(max, 0), "maxTokens 未知(0)关闭判定");
    }

    @Test
    void networkErrorDetection() {
        // 直接 IO/超时/SDK IO 信封
        assertTrue(ModelLengthGuardAdvisor.isNetworkError(new IOException("Stream was closed")));
        assertTrue(ModelLengthGuardAdvisor.isNetworkError(new SocketException("Socket closed")));
        assertTrue(ModelLengthGuardAdvisor.isNetworkError(new TimeoutException("timed out")));
        // 沿 cause 链下沉(reactor/框架多层包装后真实根因在链上)
        assertTrue(ModelLengthGuardAdvisor.isNetworkError(
                new RuntimeException("wrapper",
                        new IllegalStateException("inner",
                                new IOException("Stream was closed")))));
        // 非 IO 错误(取消/解析/参数类)不判网络
        assertFalse(ModelLengthGuardAdvisor.isNetworkError(new IllegalArgumentException("bad args")));
        assertFalse(ModelLengthGuardAdvisor.isNetworkError(
                new RuntimeException("wrapper", new IllegalArgumentException("bad args"))));
        // cause 链深度超过 8 层不再下沉(防环)
        Throwable deep = new IOException("root");
        for (int i = 0; i < 10; i++) {
            deep = new RuntimeException("layer" + i, deep);
        }
        assertFalse(ModelLengthGuardAdvisor.isNetworkError(deep));
    }

    /**
     * 场景:长思考持续输出后流被网络级错误粗暴中断(无 finish_reason=length 帧),
     * 且累计输出已≈maxTokens → 应先收到合成 finish_reason=length 帧,再收到
     * ModelLengthExhaustedException(非重试,快速收口)。
     */
    @Test
    void abruptDisconnectNearMaxConvertsToLengthExhausted() {
        Flux<ChatClientResponse> source = Flux.<ChatClientResponse>just(
                        thinkingChunk("思".repeat(8000), null))
                .concatWith(Flux.error(new IOException("Stream was closed")));
        ModelLengthGuardAdvisor advisor = advisor(source);

        StepVerifier.create(advisor.adviseStream(request(maxTokens(10_000)), chain(source)))
                .expectNextCount(2)   // 原始思考 chunk + 合成 length 帧
                .expectError(ModelLengthExhaustedException.class)
                .verify();
    }

    /**
     * 场景(t_vvk3 实测):模型配置<b>未设置 maxTokens</b>(provider 按服务端默认预算截断,
     * 客户端不可见)时,长思考断流后原判定链断裂(maxTokens=null → nearMax 恒 false),
     * IOException 穿透到外层瞬时重试反复退避重跑。兜底:断流且自估输出 ≥
     * length-disconnect-min-tokens(默认 32768)即判定等价 length,快速收口。
     */
    @Test
    void disconnectWithoutMaxTokensFallsBackToAbsoluteThreshold() {
        Flux<ChatClientResponse> source = Flux.<ChatClientResponse>just(
                        thinkingChunk("思".repeat(40_000), null))   // 4 万 CJK ≈ 4 万 tokens ≥ 32768
                .concatWith(Flux.error(new IOException("Stream was closed")));
        ModelLengthGuardAdvisor advisor = advisor(source);
        OpenAiChatOptions noMaxTokens = OpenAiChatOptions.builder().build(); // 未设置 maxTokens

        StepVerifier.create(advisor.adviseStream(request(noMaxTokens), chain(source)))
                .expectNextCount(2)   // 原始思考 chunk + 合成 length 帧
                .expectError(ModelLengthExhaustedException.class)
                .verify();
    }

    /**
     * 场景:未配置 maxTokens 且输出远未达绝对阈值(短输出+断流=真网络抖动)
     * → 原样上抛 IOException 交瞬时重试,兜底不误伤。
     */
    @Test
    void shortDisconnectWithoutMaxTokensStillRetries() {
        Flux<ChatClientResponse> source = Flux.<ChatClientResponse>just(
                        thinkingChunk("短思考", null))
                .concatWith(Flux.error(new IOException("Stream was closed")));
        ModelLengthGuardAdvisor advisor = advisor(source);
        OpenAiChatOptions noMaxTokens = OpenAiChatOptions.builder().build();

        StepVerifier.create(advisor.adviseStream(request(noMaxTokens), chain(source)))
                .expectNextCount(1)
                .expectErrorMatches(e -> e instanceof IOException
                        && !(e instanceof ModelLengthExhaustedException))
                .verify();
    }

    /**
     * 场景:网络级错误中断但累计输出远未达 maxTokens(真·网络抖动)→ 原样上抛 IOException,
     * 交外层瞬时重试 advisor 退避重调。
     */
    @Test
    void networkDisconnectFarFromMaxPassesThrough() {
        Flux<ChatClientResponse> source = Flux.<ChatClientResponse>just(
                        thinkingChunk("短思考", null))
                .concatWith(Flux.error(new IOException("Stream was closed")));
        ModelLengthGuardAdvisor advisor = advisor(source);

        StepVerifier.create(advisor.adviseStream(request(maxTokens(100_000)), chain(source)))
                .expectNextCount(1)
                .expectErrorMatches(e -> e instanceof IOException
                        && !(e instanceof ModelLengthExhaustedException))
                .verify();
    }

    /**
     * 场景:收到真实 finish_reason=length 帧时,帧先透传到外层(expectNext),
     * 然后流 complete 时抛 ModelLengthExhaustedException。
     */
    @Test
    void realLengthFramePassesThroughThenCompleteThrows() {
        // 构造带 finish_reason=length 的帧
        ChatClientResponse lengthFrame = lengthFinishChunk("思".repeat(8000));
        Flux<ChatClientResponse> source = Flux.just(lengthFrame);
        ModelLengthGuardAdvisor advisor = advisor(source);

        StepVerifier.create(advisor.adviseStream(request(maxTokens(10_000)), chain(source)))
                .expectNextCount(1)   // 真实 length 帧透传
                .expectError(ModelLengthExhaustedException.class)
                .verify();
    }

    /**
     * 场景:合成帧先于异常到达外层——stall/断流路径触发时,
     * 先收到合成 finish_reason=length 帧,再收到 ModelLengthExhaustedException。
     * 验证合成帧确实携带 finish_reason=length 元数据。
     */
    @Test
    void syntheticFrameArrivesBeforeException() {
        Flux<ChatClientResponse> source = Flux.<ChatClientResponse>just(
                        thinkingChunk("思".repeat(8000), null))
                .concatWith(Flux.error(new IOException("Stream was closed")));
        ModelLengthGuardAdvisor advisor = advisor(source);

        StepVerifier.create(advisor.adviseStream(request(maxTokens(10_000)), chain(source)))
                .expectNextMatches(chunk -> {
                    // 第一个帧是原始思考 chunk
                    return chunk.chatResponse() != null;
                })
                .expectNextMatches(chunk -> {
                    // 第二个帧是合成 length 帧
                    ChatResponse cr = chunk.chatResponse();
                    return cr != null && cr.hasFinishReasons(Set.of("length"));
                })
                .expectError(ModelLengthExhaustedException.class)
                .verify();
    }

    // ---- 测试脚手架(§14.9:自建桩,不依赖 worker) ----

    /** 构造 advisor(nextStream 返回 source 的桩链 + 轻量 AgentContext 桩;advisor 不发事件)。 */
    private static ModelLengthGuardAdvisor advisor(Flux<ChatClientResponse> source) {
        TaskRuntime task = new StubTaskRuntime("t_test");
        AgentContext a = new StubAgentContext("a_test", "test", task);
        return new ModelLengthGuardAdvisor(a, new StubWorkerConfig(), STUB_ESTIMATOR);
    }

    /** 手写桩链(免 Mockito,受限环境可跑)。 */
    private static StreamAdvisorChain chain(Flux<ChatClientResponse> flux) {
        return new StreamAdvisorChain() {
            @Override
            public Flux<ChatClientResponse> nextStream(ChatClientRequest request) {
                return flux;
            }

            @Override
            public List<StreamAdvisor> getStreamAdvisors() {
                return List.of(); // 桩:本 advisor 不读链成员
            }

            @Override
            public StreamAdvisorChain copy(StreamAdvisor advisor) {
                return this; // 桩:本 advisor 不走重试 copy 路径
            }
        };
    }

    private static OpenAiChatOptions maxTokens(int max) {
        return OpenAiChatOptions.builder().maxTokens(max).build();
    }

    private static ChatClientRequest request(OpenAiChatOptions options) {
        return new ChatClientRequest(new Prompt(List.of(), options), Map.of());
    }

    /** 构造带 reasoningContent(累积值)的思考 chunk(text 为 null)或正文 chunk。 */
    private static ChatClientResponse thinkingChunk(String thinking, String text) {
        AssistantMessage.Builder<?> builder = AssistantMessage.builder()
                .content(text == null ? "" : text);
        if (thinking != null) {
            builder.properties(Map.of("reasoningContent", thinking));
        }
        AssistantMessage msg = builder.build();
        Generation gen = new Generation(msg,
                ChatGenerationMetadata.builder().finishReason("STOP").build());
        return new ChatClientResponse(new ChatResponse(List.of(gen)), Map.of());
    }

    /** 构造带 finish_reason=length 的帧(模拟 provider 真实返回的 length 帧)。 */
    private static ChatClientResponse lengthFinishChunk(String thinking) {
        AssistantMessage.Builder<?> builder = AssistantMessage.builder().content("");
        if (thinking != null) {
            builder.properties(Map.of("reasoningContent", thinking));
        }
        AssistantMessage msg = builder.build();
        Generation gen = new Generation(msg,
                ChatGenerationMetadata.builder().finishReason("length").build());
        return new ChatClientResponse(new ChatResponse(List.of(gen)), Map.of());
    }

    // ---- 自建等价桩(实现 plugin-api 接口;§14.9) ----

    /**
     * WorkerConfig 等价桩(原借 worker WorkerProperties 默认值):
     * Guard 只读 limits().modelLengthStallMs()(默认 120_000)与
     * limits().lengthDisconnectMinTokens()(默认 32_768),其余域不触达。
     */
    private static final class StubWorkerConfig implements WorkerConfig {
        @Override public Limits limits() { return new StubLimits(); }
        @Override public Retry retry() { return null; }
        @Override public Sandbox sandbox() { return null; }
        @Override public Permissions permissions() { return null; }
        @Override public Git git() { return null; }
        @Override public Path resolveHomeDir() { return null; }
        @Override public Path resolveSandboxPersistentRoot() { return null; }
        @Override public Path resolveSkillsDir() { return null; }
    }

    /** Limits 桩:关键两项取 worker WorkerProperties 默认值。 */
    private static final class StubLimits implements WorkerConfig.Limits {
        @Override public AdaptiveMaxTokens adaptiveMaxTokens() { return null; }
        @Override public int maxConcurrentTasks() { return 20; }
        @Override public long modelLengthStallMs() { return 120_000; }
        @Override public long lengthDisconnectMinTokens() { return 32_768; }
        @Override public ModelRate modelRate() { return null; }
        @Override public boolean contextCompressionEnabled() { return true; }
        @Override public double contextTriggerRatio() { return 0.9; }
        @Override public double contextTargetRatio() { return 0.6; }
        @Override public double contextSafetyRatio() { return 0.1; }
        @Override public long contextToolReserveTokens() { return 16_384; }
        @Override public int contextMaxToolResultChars() { return 20_000; }
        @Override public boolean contextOffsetEnabled() { return true; }
        @Override public boolean contextSummaryEnabled() { return true; }
        @Override public int contextSummaryMaxTokens() { return 8_192; }
        @Override public double tokenEstimatorConvergenceThreshold() { return 0.05; }
        @Override public int tokenEstimatorConvergenceSamples() { return 20; }
        @Override public double tokenEstimatorDriftThreshold() { return 0.3; }
    }

    /** TaskRuntime 最小桩(原借 worker TaskEntry):Guard 构造时只读 snapshot().configId()。 */
    private static final class StubTaskRuntime implements TaskRuntime {
        private final String taskId;

        StubTaskRuntime(String taskId) {
            this.taskId = taskId;
        }

        @Override public String taskId() { return taskId; }
        @Override public String status() { return "running"; }
        @Override public boolean terminal() { return false; }
        @Override public Map<String, Object> metadata() { return new HashMap<>(); }
        @Override public Path taskDir() { return Path.of("/tmp", taskId); }
        @Override public String workspaceRoot() { return "/tmp"; }
        @Override public String workspaceId() { return "w_1"; }
        @Override public String mainAgentId() { return "main-agent"; }
        @Override public ModelConfig snapshot() {
            return new ModelConfig("cfg", "openai", "http://localhost", "test-model", null);
        }
        @Override public EventEmitter events() { return e -> e.id(); }
        @Override public Map<String, AgentContext> agents() { return new HashMap<>(); }
        @Override public dev.everyagent.plugin.api.agent.AgentFactory agentFactory() { return null; }
        @Override public dev.everyagent.plugin.api.interaction.InteractionService interaction() { return null; }
        @Override public AgentContext main() { return null; }
        @Override public EventLogReader log() {
            return new EventLogReader() {
                @Override public List<EventRecord> readFrom(int from, int max) { return List.of(); }
                @Override public List<EventRecord> readAfterSeq(long afterSeq, int max) { return List.of(); }
                @Override public void addListener(Listener listener) { }
                @Override public void removeListener(Listener listener) { }
            };
        }
        @Override public FileChangesCollector fileChanges() { return null; }
        @Override public void fileChanges(FileChangesCollector collector) { }
        @Override public JsonNode fileChangesLight() { return null; }
        @Override public void fileChangesLight(JsonNode light) { }
        @Override public JsonNode fileChangesFull() { return null; }
        @Override public void fileChangesFull(JsonNode full) { }
        @Override public long startedAt() { return 0; }
        @Override public long endedAt() { return 0; }
        @Override public void touch() { }
        @Override public tools.jackson.databind.node.ObjectNode summaryJson() { return null; }
        @Override public void truncateLogAfter(long targetSeq) { }
    }

    /** AgentContext 最小桩(原借 worker AgentEntity):Guard 只读 agentId/execution。 */
    private static final class StubAgentContext implements AgentContext {
        private final String agentId;
        private final String title;
        private final TaskRuntime task;

        StubAgentContext(String agentId, String title, TaskRuntime task) {
            this.agentId = agentId;
            this.title = title;
            this.task = task;
        }

        @Override public String agentId() { return agentId; }
        @Override public String title() { return title; }
        @Override public long createdAt() { return 0; }
        @Override public ExecContext execution() { return task; }
        @Override public EventEmitter emitter() { return e -> e.id(); }
        @Override public String status() { return "running"; }
        @Override public boolean finished() { return false; }
        @Override public void finished(boolean finished) { }
        @Override public String lastText() { return ""; }
        @Override public List<Message> conversation() { return new ArrayList<>(); }
        @Override public ChatModel chatModel() { return null; }
        @Override public String currentModel() { return null; }
        @Override public Usage lastRound() { return null; }
        @Override public String lastModel() { return ""; }
        @Override public Usage usage() { return null; }
        @Override public AgentActivity activity() { return null; }
        @Override public void updateActivity(String reasoning, String content, String error) { }
        @Override public void resetForRerun() { }
        @Override public boolean claimTerminal(String status) { return true; }
    }
}
