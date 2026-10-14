package dev.everyagent.plugin.tokenEstimator;

import dev.everyagent.plugin.api.spi.AdvisorContext;
import dev.everyagent.plugin.api.spi.AdvisorProvider;
import dev.everyagent.plugin.api.util.OutputTokenAccumulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;

/**
 * Token 估算校准 Advisor（红线：一个 advisor 只负责一个功能）。
 *
 * <p>职责（单一）：在每次模型调用完成后，用真实 {@code completionTokens} 校准
 * {@link BuiltinTokenEstimator} 的估算系数。逐 chunk 累计估算输出 token，
 * 检测到 usage 帧（{@code completionTokens > 0}）时调 {@link BuiltinTokenEstimator#calibrate}
 * 并重置累加器，为下一轮模型调用做准备。
 *
 * <p>估算口径含<b>工具调用参数</b>（与真实 {@code completionTokens} 对齐），统一走
 * {@link OutputTokenAccumulator}（正文增量 + 思考累积值差分 + 工具调用参数按 callId 累积）——
 * 与限流侧同一套实现，避免两处口径再次分叉。
 *
 * <p>位置：order = 0 是绝对值、不在 HIGHEST_PRECEDENCE+N 体系内——数值上大于全链
 * 所有 HP+N，实际排在 advisor 链最内层（紧贴模型调用，比 RateLimitAdvisor 的 HP+800
 * 更内）。响应方向最先看到每轮模型调用的原始 chunk（包括工具循环递归各轮），不受外层
 * advisor 的 filter/聚合影响，校准目的仍达成；请求方向则最晚进入，看不到最外层的入参
 * 改写。插件新增 advisor 勿模仿此绝对值写法，应一律用 HP+N / TCA+N 表达式对齐坐标系
 * （全链真实顺序见插件指南 advisors 篇 §2.2 的 order 表）。
 *
 * <p>思考差分：Spring AI 2.0.1 的 OpenAiChatModel 在每个 chunk 的 metadata 里带
 * reasoningContent 累积值，需做前缀差分取增量（与 {@code WorkerToolEventAdvisor} 同纪律）。
 *
 * <p>校准入口唯一：本 advisor 是 {@link BuiltinTokenEstimator#calibrate} 的唯一调用方
 * （限流器的 {@code Permit.complete} 不再自行校准）。
 */
public class TokenCalibrationAdvisor implements CallAdvisor, StreamAdvisor {

    private static final Logger log = LoggerFactory.getLogger(TokenCalibrationAdvisor.class);

    private final BuiltinTokenEstimator estimator;
    private final String configId;

    public TokenCalibrationAdvisor(String configId, BuiltinTokenEstimator estimator) {
        this.configId = configId;
        this.estimator = estimator;
    }

    @Override
    public String getName() {
        return "Token Calibration Advisor";
    }

    @Override
    public int getOrder() {
        // 绝对值 0（非 HP+N 系）：数值大于全链所有 HP+N，实际位于链最内层；
        // 响应侧最先看到每轮模型调用的原始 chunk（详见类注释「位置」段）。
        return 0;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        ChatClientResponse response = chain.nextCall(request);
        ChatResponse cr = response.chatResponse();
        long estimated = estimateResponse(cr);
        long actual = actualOutputTokens(cr);
        if (actual > 0 && estimated > 0) {
            estimator.calibrate(configId, estimated, actual);
        }
        return response;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return Flux.defer(() -> {
            // 每次订阅（每轮模型调用）新建累加器，检测到 usage 帧后 reset 复用。
            OutputTokenAccumulator acc = new OutputTokenAccumulator(estimator, configId);
            return chain.nextStream(request)
                    .doOnNext(chunk -> {
                        accept(chunk, acc);
                        long actual = actualOutputTokens(chunk.chatResponse());
                        if (actual > 0 && acc.tokens() > 0) {
                            estimator.calibrate(configId, acc.tokens(), actual);
                            acc.reset();
                        }
                    });
        });
    }

    /** 估算一次 call 响应的输出 token（正文 + 思考 + 工具调用参数，单次非流式）。 */
    private long estimateResponse(ChatResponse cr) {
        if (cr == null || cr.getResult() == null || cr.getResult().getOutput() == null) {
            return 0;
        }
        OutputTokenAccumulator acc = new OutputTokenAccumulator(estimator, configId);
        acc.accept(cr.getResult().getOutput());
        return acc.tokens();
    }

    /** 逐 chunk 纳入估算（经 {@link OutputTokenAccumulator} 统一口径）。 */
    private static void accept(ChatClientResponse chunk, OutputTokenAccumulator acc) {
        ChatResponse cr = chunk == null ? null : chunk.chatResponse();
        if (cr == null || cr.getResult() == null || cr.getResult().getOutput() == null) {
            return;
        }
        acc.accept(cr.getResult().getOutput());
    }

    private static long actualOutputTokens(ChatResponse cr) {
        if (cr == null || cr.getMetadata() == null || cr.getMetadata().getUsage() == null) {
            return 0;
        }
        Integer out = cr.getMetadata().getUsage().getCompletionTokens();
        return out == null ? 0 : out;
    }

    /**
     * Provider 适配器。
     *
     * <p>order = 0（绝对值，实际位于链最内层，见类注释「位置」段），每 run 新建实例。经 {@link AdvisorContext#configId()} 获取 configId，
     * 不再依赖 worker 内部类型（{@code AgentEntity} / {@code AdvisorContextImpl}）。
     */
    public static class Provider implements AdvisorProvider {

        private final BuiltinTokenEstimator estimator;

        public Provider(BuiltinTokenEstimator estimator) {
            this.estimator = estimator;
        }

        @Override
        public String pluginId() {
            return "builtin.token-calibration";
        }

        @Override
        public int order() {
            return 0;
        }

        @Override
        public org.springframework.ai.chat.client.advisor.api.Advisor create(AdvisorContext ctx) {
            return new TokenCalibrationAdvisor(ctx.configId(), estimator);
        }
    }
}
