package dev.everyagent.worker.task;

import com.openai.errors.OpenAIIoException;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.proto.TaskDtos.ModelSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.net.SocketException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ModelLengthGuardAdvisor} 单测:
 * 「输出≈maxTokens」粗估判定(nearMax)、网络级错误判定(isNetworkError)的纯函数验证;
 * 以及流式收口路径(断流/远未满额断流)的 StepVerifier 验证——provider 在输出预算
 * 耗尽处粗暴断开 SSE(不发 finish_reason=length)时应转换为非重试的
 * {@link ModelLengthGuardAdvisor.ModelLengthExhaustedException},避免外层瞬时重试
 * 重新整段长思考再次占满预算的循环。
 */
class ModelLengthGuardAdvisorTest {

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
     * 且累计输出已≈maxTokens → 应转换为 ModelLengthExhaustedException(非重试,快速收口)。
     */
    @Test
    void abruptDisconnectNearMaxConvertsToLengthExhausted() {
        Flux<ChatClientResponse> source = Flux.<ChatClientResponse>just(
                        thinkingChunk("思".repeat(8000), null))
                .concatWith(Flux.error(new IOException("Stream was closed")));
        ModelLengthGuardAdvisor advisor = advisor(source);

        StepVerifier.create(advisor.adviseStream(request(maxTokens(10_000)), chain(source)))
                .expectNextCount(1)
                .expectError(ModelLengthGuardAdvisor.ModelLengthExhaustedException.class)
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
                .expectNextCount(1)
                .expectError(ModelLengthGuardAdvisor.ModelLengthExhaustedException.class)
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
                        && !(e instanceof ModelLengthGuardAdvisor.ModelLengthExhaustedException))
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
                        && !(e instanceof ModelLengthGuardAdvisor.ModelLengthExhaustedException))
                .verify();
    }

    // ---- 测试脚手架 ----

    /** 构造 advisor(nextStream 返回 source 的桩链 + 轻量 AgentEntity;advisor 不发事件)。 */
    private static ModelLengthGuardAdvisor advisor(Flux<ChatClientResponse> source) {
        TaskEntry task = new TaskEntry("t_test", "测试",
                new ModelSnapshot("cfg", "openai", "http://localhost", "test-model", null),
                "key", "/tmp", "w_1", "a_test", 1000);
        AgentEntity a = new AgentEntity(task, "a_test", AgentEntity.Kind.MAIN, "test", null,
                OpenAiChatOptions.builder().build(), List.of());
        return new ModelLengthGuardAdvisor(a, new WorkerProperties());
    }

    /** 手写桩链(免 Mockito,受限环境可跑)。 */
    private static StreamAdvisorChain chain(Flux<ChatClientResponse> flux) {
        return new StreamAdvisorChain() {
            @Override
            public Flux<ChatClientResponse> nextStream(ChatClientRequest request) {
                return flux;
            }

            @Override
            public java.util.List<org.springframework.ai.chat.client.advisor.api.StreamAdvisor> getStreamAdvisors() {
                return List.of(); // 桩:本 advisor 不读链成员
            }

            @Override
            public StreamAdvisorChain copy(org.springframework.ai.chat.client.advisor.api.StreamAdvisor advisor) {
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
}

