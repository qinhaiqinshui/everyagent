package dev.everyagent.worker.task;

import dev.everyagent.plugin.api.spi.TokenEstimator;
import dev.everyagent.worker.plugin.AdvisorContextImpl;
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
 * <p>位置：order = 0（核心基础设施层），在 advisor 链最外层——能看到所有模型调用轮的
 * chunk（包括工具循环递归各轮），不受下游 advisor 的 filter/聚合影响。
 *
 * <p>思考差分：Spring AI 2.0.1 的 OpenAiChatModel 在每个 chunk 的 metadata 里带
 * reasoningContent 累积值，需做前缀差分取增量（与 {@link WorkerToolEventAdvisor} 同纪律）。
 *
 * <p>去重：{@link TokenEstimator#calibrate} 内部用样本指纹去重，同一轮若
 * {@code RateLimitedChatModel}（限流路径）也调了 calibrate 不会重复校准。
 */
public class TokenCalibrationAdvisor implements CallAdvisor, StreamAdvisor {

    private static final Logger log = LoggerFactory.getLogger(TokenCalibrationAdvisor.class);

    private final AgentEntity a;
    private final TokenEstimator estimator;
    private final String configId;

    public TokenCalibrationAdvisor(AgentEntity a, TokenEstimator estimator) {
        this.a = a;
        this.estimator = estimator;
        this.configId = a.task.snapshot.configId();
    }

    @Override
    public String getName() {
        return "Token Calibration Advisor";
    }

    @Override
    public int getOrder() {
        // 核心基础设施层（0~99），最外层，看到所有模型调用轮。
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
     * <p>order = 0（核心基础设施层），scope = BOTH（主/子 agent 同挂）。
     * 每 run 新建实例。
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
        public Scope scope() {
            return Scope.BOTH;
        }

        @Override
        public int order() {
            return 0;
        }

        @Override
        public org.springframework.ai.chat.client.advisor.api.Advisor create(AdvisorContext ctx) {
            AgentEntity a = ((AdvisorContextImpl) ctx).agentEntity();
            return new TokenCalibrationAdvisor(a, estimator);
        }
    }
}
