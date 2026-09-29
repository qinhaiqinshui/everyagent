package dev.everyagent.plugin.adaptivemaxtokens;

import dev.everyagent.plugin.api.model.ModelConfig;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.agent.AgentEntity;
import dev.everyagent.worker.task.TaskEntry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AdaptiveMaxTokensAdvisor} 单测:覆盖 error/complete 双路径升级、length 帧吞帧、
 * budget 持续生效、ceiling 放弃、400 回退、低水位回落、直通场景与非 length 错误透传。
 *
 * <p>测试脚手架风格参考 {@code ModelLengthGuardAdvisorTest}:手写桩链(免 Mockito),
 * 用计数器控制每次 nextStream 返回不同的 Flux。
 */
class AdaptiveMaxTokensAdvisorTest {

    // ---- 场景 1: error 路径(Guard 帧+异常)触发升级重试 ----

    @Test
    void errorPathTriggersUpgradeAndRetrySuccess() {
        // 第一次:length 帧后 error;第二次:正常完成。
        List<Flux<ChatClientResponse>> sources = new ArrayList<>();
        sources.add(Flux.<ChatClientResponse>just(lengthChunk())
                .concatWith(Flux.error(new RuntimeException("Guard: length exhausted"))));
        sources.add(Flux.<ChatClientResponse>just(normalChunk("OK")).concatWith(Flux.empty()));

        CountingChain chain = new CountingChain(sources);
        AdaptiveMaxTokensAdvisor advisor = advisor(1000, 10000, 2, 2);

        StepVerifier.create(advisor.adviseStream(request(1000), chain))
                .expectNextMatches(r -> {
                    String text = r.chatResponse().getResult().getOutput().getText();
                    return "OK".equals(text);
                })
                .verifyComplete();
    }

    // ---- 场景 2: complete 路径(无 Guard 真实帧)触发升级 ----

    @Test
    void completePathTriggersUpgradeAndRetrySuccess() {
        // 第一次:length 帧后正常 complete;第二次:正常完成。
        List<Flux<ChatClientResponse>> sources = new ArrayList<>();
        sources.add(Flux.<ChatClientResponse>just(lengthChunk()));
        sources.add(Flux.<ChatClientResponse>just(normalChunk("OK")).concatWith(Flux.empty()));

        CountingChain chain = new CountingChain(sources);
        AdaptiveMaxTokensAdvisor advisor = advisor(1000, 10000, 2, 2);

        StepVerifier.create(advisor.adviseStream(request(1000), chain))
                .expectNextMatches(r -> "OK".equals(r.chatResponse().getResult().getOutput().getText()))
                .verifyComplete();
    }

    // ---- 场景 3: length 帧被吞不上抛 ----

    @Test
    void lengthFrameIsSwallowed() {
        // 第一次:length 帧 + complete;第二次:正常完成。
        List<Flux<ChatClientResponse>> sources = new ArrayList<>();
        sources.add(Flux.<ChatClientResponse>just(lengthChunk()));
        sources.add(Flux.<ChatClientResponse>just(normalChunk("OK")).concatWith(Flux.empty()));

        CountingChain chain = new CountingChain(sources);
        AdaptiveMaxTokensAdvisor advisor = advisor(1000, 10000, 2, 2);

        StepVerifier.create(advisor.adviseStream(request(1000), chain))
                // 外层只看到 "OK" 帧,看不到 length 帧。
                .expectNextMatches(r -> {
                    // 确保不是 length 帧。
                    ChatResponse cr = r.chatResponse();
                    return !cr.hasFinishReasons(java.util.Set.of("length"))
                            && "OK".equals(cr.getResult().getOutput().getText());
                })
                .verifyComplete();
    }

    // ---- 场景 4: budget 任务内持续生效 ----

    @Test
    void budgetPersistsAcrossRetries() {
        // 第一次:length → 升级到 2000;第二次:length → 升级到 4000;第三次:正常完成。
        List<Flux<ChatClientResponse>> sources = new ArrayList<>();
        sources.add(Flux.<ChatClientResponse>just(lengthChunk()));
        sources.add(Flux.<ChatClientResponse>just(lengthChunk()));
        sources.add(Flux.<ChatClientResponse>just(normalChunk("OK")).concatWith(Flux.empty()));

        CountingChain chain = new CountingChain(sources);
        AdaptiveMaxTokensAdvisor advisor = advisor(1000, 100000, 2.0, 2);

        StepVerifier.create(advisor.adviseStream(request(1000), chain))
                .expectNextMatches(r -> "OK".equals(r.chatResponse().getResult().getOutput().getText()))
                .verifyComplete();

        // 验证第三次调用的 request 的 maxTokens 应为 4000(1000 × 2^2)。
        ChatClientRequest lastReq = chain.lastRequest.get();
        int mt = lastReq.prompt().getOptions().getMaxTokens();
        assertTrue(mt == 4000, "第三轮 maxTokens 应为 4000(升级后持续生效),实际=" + mt);
    }

    // ---- 场景 5: 达 ceiling 放弃抛错 ----

    @Test
    void ceilingReachedThrowsExhausted() {
        // base=1000, ceiling=2000, multiplier=2.0, maxRetries=2
        // attempt 1: 2000 (== ceiling); attempt 2: 仍 length → 达 ceiling 放弃。
        List<Flux<ChatClientResponse>> sources = new ArrayList<>();
        sources.add(Flux.<ChatClientResponse>just(lengthChunk()).concatWith(Flux.error(new RuntimeException("length1"))));
        sources.add(Flux.<ChatClientResponse>just(lengthChunk()).concatWith(Flux.error(new RuntimeException("length2"))));
        sources.add(Flux.<ChatClientResponse>just(lengthChunk()).concatWith(Flux.error(new RuntimeException("length3"))));

        CountingChain chain = new CountingChain(sources);
        AdaptiveMaxTokensAdvisor advisor = advisor(1000, 2000, 2.0, 2);

        StepVerifier.create(advisor.adviseStream(request(1000), chain))
                .expectError(AdaptiveBudgetExhaustedException.class)
                .verify();
    }

    // ---- 场景 6: 400 回退上一档 ----

    @Test
    void error400TriggersRollback() {
        // 第一次:length → 升级到 2000;第二次:400 错误 → 回退到 1000,重试;第三次:正常完成。
        List<Flux<ChatClientResponse>> sources = new ArrayList<>();
        sources.add(Flux.<ChatClientResponse>just(lengthChunk()).concatWith(Flux.error(new RuntimeException("length1"))));
        sources.add(Flux.error(new Fake400Exception(400, "max_tokens too large")));
        sources.add(Flux.<ChatClientResponse>just(normalChunk("OK")).concatWith(Flux.empty()));

        CountingChain chain = new CountingChain(sources);
        AdaptiveMaxTokensAdvisor advisor = advisor(1000, 100000, 2.0, 2);

        StepVerifier.create(advisor.adviseStream(request(1000), chain))
                .expectNextMatches(r -> "OK".equals(r.chatResponse().getResult().getOutput().getText()))
                .verifyComplete();

        // 第三次调用的 maxTokens 应为 1000(回退后)。
        ChatClientRequest lastReq = chain.lastRequest.get();
        int mt = lastReq.prompt().getOptions().getMaxTokens();
        assertTrue(mt == 1000, "400 回退后 maxTokens 应为 1000,实际=" + mt);
    }

    // ---- 场景 7: 低水位回落 ----

    @Test
    void lowWatermarkFallsBackToBase() {
        // base=1000, ceiling=100000, multiplier=2.0, maxRetries=5
        // fallbackRatio=0.5, fallbackRounds=3
        //
        // 连续 3 轮低输出(completionTokens=0,length 帧无 usage)后 budget 衰减回 base。
        // 验证:衰减后再次升级时 maxTokens 从 base=1000 重新计算(2000)而非从历史高位继续。
        //
        // 第 1 轮:length → 升级到 2000, attempt=1
        // 第 2 轮:length → lowWater=1, 升级到 4000, attempt=2
        // 第 3 轮:length → lowWater=2, 升级到 8000, attempt=3
        // 第 4 轮:length → lowWater=3 → 衰减回 base(attempt=0, budget=1000),
        //           随后 upgradeAndRetry 重新升级到 2000, attempt=1
        // 第 5 轮:正常完成
        List<Flux<ChatClientResponse>> sources = new ArrayList<>();
        sources.add(Flux.<ChatClientResponse>just(lengthChunk()).concatWith(Flux.error(new RuntimeException("length1"))));
        sources.add(Flux.<ChatClientResponse>just(lengthChunk()).concatWith(Flux.error(new RuntimeException("length2"))));
        sources.add(Flux.<ChatClientResponse>just(lengthChunk()).concatWith(Flux.error(new RuntimeException("length3"))));
        sources.add(Flux.<ChatClientResponse>just(lengthChunk()).concatWith(Flux.error(new RuntimeException("length4"))));
        sources.add(Flux.<ChatClientResponse>just(normalChunk("OK")).concatWith(Flux.empty()));

        CountingChain chain = new CountingChain(sources);
        AdaptiveMaxTokensAdvisor advisor = advisor(1000, 100000, 2.0, 5, 0.5, 3);

        StepVerifier.create(advisor.adviseStream(request(1000), chain))
                // 所有 length 帧被吞掉,外层只看到最终正常帧 "OK"。
                .expectNextMatches(r -> "OK".equals(r.chatResponse().getResult().getOutput().getText()))
                .verifyComplete();

        // 最后一次请求的 maxTokens 应为 2000(从 base=1000 重新升级),
        // 而非 16000(若未衰减,1000×2^4=16000)。
        ChatClientRequest lastReq = chain.lastRequest.get();
        int mt = lastReq.prompt().getOptions().getMaxTokens();
        assertTrue(mt == 2000, "低水位回落后重新升级,maxTokens 应为 2000,实际=" + mt);
    }

    // ---- 场景 8: 未配 base maxTokens 直通 ----

    @Test
    void noBaseMaxTokensPassesThrough() {
        List<Flux<ChatClientResponse>> sources = new ArrayList<>();
        sources.add(Flux.<ChatClientResponse>just(normalChunk("OK")).concatWith(Flux.empty()));

        CountingChain chain = new CountingChain(sources);
        AdaptiveMaxTokensAdvisor advisor = advisor(1000, 10000, 2, 2);

        OpenAiChatOptions noMaxTokens = OpenAiChatOptions.builder().build();
        StepVerifier.create(advisor.adviseStream(request(noMaxTokens), chain))
                .expectNextMatches(r -> "OK".equals(r.chatResponse().getResult().getOutput().getText()))
                .verifyComplete();
    }

    // ---- 场景 9: enabled=false 直通 ----

    @Test
    void disabledPassesThrough() {
        List<Flux<ChatClientResponse>> sources = new ArrayList<>();
        // 即使有 length 帧,disabled 时也应直通(不吞帧、不升级)。
        sources.add(Flux.<ChatClientResponse>just(lengthChunk()).concatWith(Flux.empty()));

        CountingChain chain = new CountingChain(sources);
        AdaptiveMaxTokensAdvisor advisor = advisorDisabled(1000, 10000, 2, 2);

        StepVerifier.create(advisor.adviseStream(request(1000), chain))
                // disabled 时 length 帧会透传。
                .expectNextCount(1)
                .verifyComplete();
    }

    // ---- 场景 10: 非 length 错误原样透传 ----

    @Test
    void nonLengthErrorPassesThrough() {
        RuntimeException original = new RuntimeException("some other error");
        List<Flux<ChatClientResponse>> sources = new ArrayList<>();
        sources.add(Flux.error(original));

        CountingChain chain = new CountingChain(sources);
        AdaptiveMaxTokensAdvisor advisor = advisor(1000, 10000, 2, 2);

        StepVerifier.create(advisor.adviseStream(request(1000), chain))
                .expectErrorMatches(e -> e == original || e.getMessage().equals("some other error"))
                .verify();
    }

    // ---- 测试脚手架 ----

    /**
     * 计数桩链:每次 nextStream 返回 sources 列表中下一个 Flux。
     * 支持重订阅(copy 返回 this,因为测试中所有 source 已预置)。
     */
    private static class CountingChain implements StreamAdvisorChain {
        private final List<Flux<ChatClientResponse>> sources;
        private final AtomicInteger index = new AtomicInteger(0);
        final AtomicReference<ChatClientRequest> lastRequest = new AtomicReference<>();

        CountingChain(List<Flux<ChatClientResponse>> sources) {
            this.sources = sources;
        }

        @Override
        public Flux<ChatClientResponse> nextStream(ChatClientRequest request) {
            lastRequest.set(request);
            int i = index.getAndIncrement();
            if (i < sources.size()) {
                return sources.get(i);
            }
            return Flux.error(new IllegalStateException("No more sources"));
        }

        @Override
        public List<StreamAdvisor> getStreamAdvisors() {
            return List.of();
        }

        @Override
        public StreamAdvisorChain copy(StreamAdvisor advisor) {
            return this;
        }
    }

    private static AdaptiveMaxTokensAdvisor advisor(int base, int ceiling, double multiplier, int maxRetries) {
        return advisor(base, ceiling, multiplier, maxRetries, 0.5, 3);
    }

    private static AdaptiveMaxTokensAdvisor advisor(int base, int ceiling, double multiplier,
            int maxRetries, double fallbackRatio, int fallbackRounds) {
        WorkerProperties.Limits.AdaptiveMaxTokens cfg = new WorkerProperties.Limits.AdaptiveMaxTokens();
        cfg.setEnabled(true);
        cfg.setCeiling(ceiling);
        cfg.setMultiplier(multiplier);
        cfg.setMaxRetries(maxRetries);
        cfg.setFallbackRatio(fallbackRatio);
        cfg.setFallbackRounds(fallbackRounds);
        return new AdaptiveMaxTokensAdvisor(testAgent(), cfg, ceiling);
    }

    private static AdaptiveMaxTokensAdvisor advisorDisabled(int base, int ceiling, double multiplier, int maxRetries) {
        WorkerProperties.Limits.AdaptiveMaxTokens cfg = new WorkerProperties.Limits.AdaptiveMaxTokens();
        cfg.setEnabled(false);
        cfg.setCeiling(ceiling);
        cfg.setMultiplier(multiplier);
        cfg.setMaxRetries(maxRetries);
        return new AdaptiveMaxTokensAdvisor(testAgent(), cfg, ceiling);
    }

    private static AgentEntity testAgent() {
        TaskEntry task = new TaskEntry("t_test", "测试",
                new ModelConfig("cfg", "openai", "http://localhost", "test-model", null),
                "/tmp", "w_1", "a_test", 1000);
        return new AgentEntity(task, "a_test", AgentEntity.Kind.MAIN, "test", null,
                OpenAiChatOptions.builder().build(), List.of());
    }

    private static ChatClientRequest request(int maxTokens) {
        return request(OpenAiChatOptions.builder().maxTokens(maxTokens).build());
    }

    private static ChatClientRequest request(OpenAiChatOptions options) {
        return new ChatClientRequest(new Prompt(List.of(), options), Map.of());
    }

    /** 构造 finish_reason=length 的 chunk。 */
    private static ChatClientResponse lengthChunk() {
        AssistantMessage msg = AssistantMessage.builder().content("").build();
        Generation gen = new Generation(msg,
                ChatGenerationMetadata.builder().finishReason("length").build());
        return new ChatClientResponse(new ChatResponse(List.of(gen)), Map.of());
    }

    /** 构造正常完成的 chunk(finish_reason=STOP,带正文)。 */
    private static ChatClientResponse normalChunk(String text) {
        AssistantMessage msg = AssistantMessage.builder().content(text).build();
        Generation gen = new Generation(msg,
                ChatGenerationMetadata.builder().finishReason("stop").build());
        return new ChatClientResponse(new ChatResponse(List.of(gen)), Map.of());
    }

    /** 构造正常完成的 chunk,带低 completionTokens 的 usage(低水位回落用)。 */
    private static Flux<ChatClientResponse> lowUsageNormalChunk(String text, int completionTokens) {
        AssistantMessage msg = AssistantMessage.builder().content(text).build();
        Generation gen = new Generation(msg,
                ChatGenerationMetadata.builder().finishReason("stop").build());
        int total = 100 + completionTokens;
        ChatResponse cr = new ChatResponse(List.of(gen),
                ChatResponseMetadata.builder()
                        .usage(new DefaultUsage(100, completionTokens, total))
                        .build());
        return Flux.<ChatClientResponse>just(new ChatClientResponse(cr, Map.of())).concatWith(Flux.empty());
    }

    /**
     * 模拟 OpenAIServiceException 400(provider 拒绝超出模型真实上限)。
     * 直接构造匿名子类,避免依赖 openai-java SDK 的具体构造器。
     */
    private static class Fake400Exception extends RuntimeException {
        private final int statusCode;

        Fake400Exception(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }

        public int statusCode() {
            return statusCode;
        }
    }
}
