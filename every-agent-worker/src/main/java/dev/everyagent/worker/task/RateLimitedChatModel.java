package dev.everyagent.worker.task;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * 模型请求限流装饰器(docs/design-model-rate-limit.md §8.1):包住真实 ChatModel,
 * 在请求起步前经 {@link ModelRateLimiter#acquire} 排队等 rpm/并发/tpm 放行,流中累计
 * 估算输出,完成/取消时归还并发额度并做 tpm 记账与系数校准。
 *
 * <p>实现纪律:只包一层限流,<b>不重写工具循环/响应聚合</b>(红线合规)。委托模型的
 * call/stream 语义完全不变;失败/取消走 {@code Permit.cancel} 防并发泄漏。
 *
 * <p>stream 的 usage:OpenAI 兼容流式在末帧 metadata 带 usage({@code streamUsage(true)}),
 * 检测到 {@code ChatResponse.getMetadata().getUsage()} 非空即视为完成,用真实 outputTokens
 * 记账校准;未带 usage 的流(异常/取消)走 cancel,只释放不记账。
 */
public final class RateLimitedChatModel implements ChatModel {

    private final ChatModel delegate;
    private final ModelRateLimiter limiter;

    public RateLimitedChatModel(ChatModel delegate, ModelRateLimiter limiter) {
        this.delegate = delegate;
        this.limiter = limiter;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        ModelRateLimiter.Permit permit;
        try {
            permit = limiter.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentCancelledException("interrupted");
        }
        try {
            ChatResponse response = delegate.call(prompt);
            permit.complete(outputTokensOf(response));
            return response;
        } catch (RuntimeException e) {
            permit.cancel();
            throw e;
        }
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.defer(() -> {
            ModelRateLimiter.Permit permit;
            try {
                permit = limiter.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Flux.error(new AgentCancelledException("interrupted"));
            }
            return delegate.stream(prompt)
                    .doOnNext(chunk -> {
                        // 流中:正文增量累计估算(thinking 由 reasoningContent 走 usage 记账,
                        // 正文 chunk 已能覆盖长思考时的输出方向;估算只需近似)。
                        if (chunk != null && chunk.getResult() != null
                                && chunk.getResult().getOutput() != null) {
                            String text = chunk.getResult().getOutput().getText();
                            if (text != null && !text.isEmpty()) {
                                permit.onChunk(text);
                            }
                        }
                        // 末帧 usage:视为完成,真实记账校准。
                        org.springframework.ai.chat.metadata.Usage usage =
                                chunk.getMetadata() == null ? null : chunk.getMetadata().getUsage();
                        if (usage != null && usage.getCompletionTokens() != null
                                && usage.getCompletionTokens() > 0) {
                            permit.complete(usage.getCompletionTokens());
                        }
                    })
                    .doOnError(e -> permit.cancel())
                    .doOnComplete(() -> permit.complete(0)); // 无 usage 帧时兜底释放(不记账)
        });
    }

    @Override
    public ChatOptions getOptions() {
        return delegate.getOptions();
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }

    private static long outputTokensOf(ChatResponse response) {
        if (response == null || response.getMetadata() == null
                || response.getMetadata().getUsage() == null) {
            return 0;
        }
        Integer out = response.getMetadata().getUsage().getCompletionTokens();
        return out == null ? 0 : out;
    }
}