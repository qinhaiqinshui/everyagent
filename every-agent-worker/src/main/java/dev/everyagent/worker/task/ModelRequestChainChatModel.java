package dev.everyagent.worker.task;

import dev.everyagent.plugin.api.model.EventEmitter;
import dev.everyagent.plugin.api.model.ModelConfig;
import dev.everyagent.plugin.api.model.ModelRequestChain;
import dev.everyagent.plugin.api.model.ModelRequestContext;
import dev.everyagent.plugin.api.model.ModelRequestNode;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.CancellationException;

/**
 * 模型请求洋葱链薄壳：实现 {@link ChatModel}，将请求逐层经 {@link ModelRequestNode}
 * 洋葱链传递，最终到达内核（缓存的 delegate ChatModel）。
 *
 * <p>实现纪律：只做洋葱链调度 + 回调转发，<b>不重写工具循环/响应聚合</b>（红线合规）。
 * 委托模型的 call/stream 语义完全不变。
 *
 * <p>stream 的 usage：OpenAI 兼容流式在末帧 metadata 带 usage（{@code streamUsage(true)}），
 * 检测到 usage 帧即调 {@code ctx.invokeOnComplete(actualTokens)} 记账；
 * {@code doOnComplete} 兜底（无 usage 帧时以 0 释放并发额度）。
 *
 * @param delegate  缓存的 OpenAiChatModel（按 configId 复用，不消费 context）
 * @param nodes     有序模型请求节点列表
 * @param emitter   事件发射器（闭包绑定 agentId/taskId，转发到 TaskEvents.emit）
 * @param config    模型配置快照（只读参考，传给插件节点）
 */
record ModelRequestChainChatModel(
        ChatModel delegate,
        List<ModelRequestNode> nodes,
        EventEmitter emitter,
        ModelConfig config
) implements ChatModel {

    @Override
    public ChatResponse call(Prompt prompt) {
        // 同步执行（无 defer）
        ModelRequestContextImpl ctx = new ModelRequestContextImpl(config, emitter);
        try {
            ChatResponse resp = (ChatResponse) executeChain(nodes, 0, ctx, prompt, false);
            // call 完成后触发 onComplete
            ctx.invokeOnComplete(outputTokensOf(resp));
            return resp;
        } catch (RuntimeException e) {
            ctx.invokeOnError(e);
            throw e;
        } catch (Exception e) {
            ctx.invokeOnError(e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.defer(() -> {
            // 订阅时执行（虚拟线程上，可安全阻塞）
            ModelRequestContextImpl ctx = new ModelRequestContextImpl(config, emitter);
            try {
                @SuppressWarnings("unchecked")
                Flux<ChatResponse> flux = (Flux<ChatResponse>) executeChain(nodes, 0, ctx, prompt, true);
                return flux;
            } catch (Exception e) {
                ctx.invokeOnError(e);
                return Flux.error(e);
            }
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

    /**
     * 递归执行洋葱链：从外到内逐层传递，最终到达内核真实模型调用。
     * stream 路径返回 Flux（deferred，尚未订阅）；call 路径同步返回 ChatResponse。
     */
    private Object executeChain(List<ModelRequestNode> nodes, int index,
            ModelRequestContextImpl ctx, Prompt prompt, boolean isStream) throws Exception {
        if (index >= nodes.size()) {
            if (isStream) {
                return kernelStream(ctx, prompt);
            }
            return delegate.call(prompt);
        }
        ModelRequestNode node = nodes.get(index);
        ModelRequestChain next = c ->
                executeChain(nodes, index + 1, (ModelRequestContextImpl) c, prompt, isStream);
        return node.invoke(ctx, next);
    }

    /**
     * 内核：真实模型流式调用（使用缓存的 delegate，不消费 context）。
     * chunk → invokeOnChunk；usage 帧 → invokeOnComplete（真实记账校准）；
     * 异常/取消 → invokeOnError；doOnComplete 兜底（无 usage 帧时以 0 释放）。
     */
    private Flux<ChatResponse> kernelStream(ModelRequestContextImpl ctx, Prompt prompt) {
        return delegate.stream(prompt)
                .doOnNext(chunk -> {
                    if (chunk != null && chunk.getResult() != null
                            && chunk.getResult().getOutput() != null) {
                        String text = chunk.getResult().getOutput().getText();
                        if (text != null && !text.isEmpty()) {
                            ctx.invokeOnChunk(text);
                        }
                    }
                    // 末帧 usage：视为完成，真实记账校准。
                    Usage usage = chunk == null || chunk.getMetadata() == null
                            ? null : chunk.getMetadata().getUsage();
                    if (usage != null && usage.getCompletionTokens() != null
                            && usage.getCompletionTokens() > 0) {
                        ctx.invokeOnComplete(usage.getCompletionTokens());
                    }
                })
                .doOnError(ctx::invokeOnError)
                .doOnCancel(() -> ctx.invokeOnError(new CancellationException()))
                .doOnComplete(() -> ctx.invokeOnComplete(0)); // 无 usage 帧时兜底释放
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
