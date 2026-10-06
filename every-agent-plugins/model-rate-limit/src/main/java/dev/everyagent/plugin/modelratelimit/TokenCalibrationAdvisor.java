package dev.everyagent.plugin.modelratelimit;

import dev.everyagent.plugin.api.spi.TokenEstimator;
import dev.everyagent.plugin.api.spi.AdvisorContext;
import dev.everyagent.plugin.api.spi.AdvisorProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Token 估算校准 Advisor（红线：一个 advisor 只负责一个功能）。
 *
 * <p>职责（单一）：在每次模型调用完成后，用真实 {@code completionTokens} 校准
 * {@link TokenEstimator} 的估算系数。逐 chunk 累计估算输出 token（正文 + 思考差分），
 * 检测到 usage 帧（{@code completionTokens > 0}）时调 {@link TokenEstimator#calibrate}
 * 并重置累加器，为下一轮模型调用做准备。
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
 * <p>去重：{@link TokenEstimator#calibrate} 内部用样本指纹去重，同一轮若
 * {@code RateLimitNode}（限流路径）也调了 calibrate 不会重复校准。
 */
public class TokenCalibrationAdvisor implements CallAdvisor, StreamAdvisor {

    private static final Logger log = LoggerFactory.getLogger(TokenCalibrationAdvisor.class);

    private final TokenEstimator estimator;
    private final String configId;

    public TokenCalibrationAdvisor(String configId, TokenEstimator estimator) {
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
            AtomicLong estAcc = new AtomicLong();
            AtomicReference<String> thinkingAcc = new AtomicReference<>("");
            return chain.nextStream(request)
                    .doOnNext(chunk -> {
                        accumulate(chunk, estAcc, thinkingAcc);
                        long actual = actualOutputTokens(chunk.chatResponse());
                        if (actual > 0 && estAcc.get() > 0) {
                            estimator.calibrate(configId, estAcc.get(), actual);
                            estAcc.set(0);
                        }
                    });
        });
    }

    /** 估算一次 call 响应的输出 token（正文 + 思考，单次非流式）。 */
    private long estimateResponse(ChatResponse cr) {
        if (cr == null || cr.getResult() == null || cr.getResult().getOutput() == null) {
            return 0;
        }
        AssistantMessage out = cr.getResult().getOutput();
        long tokens = 0;
        String text = out.getText();
        if (text != null && !text.isEmpty()) {
            tokens += estimator.estimate(text, configId);
        }
        String thinking = reasoningOf(out);
        if (thinking != null && !thinking.isEmpty()) {
            tokens += estimator.estimate(thinking, configId);
        }
        return tokens;
    }

    /** 逐 chunk 累加估算输出 token（正文增量 + 思考累积值差分）。 */
    private void accumulate(ChatClientResponse chunk, AtomicLong estAcc,
            AtomicReference<String> thinkingAcc) {
        ChatResponse cr = chunk.chatResponse();
        if (cr == null || cr.getResult() == null || cr.getResult().getOutput() == null) {
            return;
        }
        AssistantMessage out = cr.getResult().getOutput();
        // 正文增量
        String text = out.getText();
        if (text != null && !text.isEmpty()) {
            estAcc.addAndGet(estimator.estimate(text, configId));
        }
        // 思考累积值差分
        String thinking = reasoningOf(out);
        if (thinking != null && !thinking.isEmpty()) {
            String cur = thinkingAcc.get();
            if (!thinking.equals(cur)) {
                String diff = diff(thinkingAcc, thinking, cur);
                if (!diff.isEmpty()) {
                    estAcc.addAndGet(estimator.estimate(diff, configId));
                }
            }
        }
    }

    /** 思考累积值 → 新增段（非前缀增长时重置追踪，防串轮）。 */
    private static String diff(AtomicReference<String> acc, String accumulated, String cur) {
        if (accumulated.length() > cur.length() && accumulated.startsWith(cur)) {
            acc.set(accumulated);
            return accumulated.substring(cur.length());
        }
        acc.set(accumulated);
        return accumulated;
    }

    private static String reasoningOf(AssistantMessage out) {
        if (out.getMetadata() == null) {
            return "";
        }
        Object rc = out.getMetadata().get("reasoningContent");
        return rc instanceof String s ? s : "";
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

        private final TokenEstimator estimator;

        public Provider(TokenEstimator estimator) {
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
