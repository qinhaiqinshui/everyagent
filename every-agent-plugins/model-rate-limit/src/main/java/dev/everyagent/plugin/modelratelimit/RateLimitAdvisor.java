package dev.everyagent.plugin.modelratelimit;

import dev.everyagent.plugin.api.model.EmitEvent;
import dev.everyagent.worker.proto.SnowflakeId;
import dev.everyagent.worker.agent.AgentEntity;
import dev.everyagent.worker.task.TaskEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;

import java.util.Optional;

/**
 * 限流 Advisor（红线：一个 advisor 只负责一个功能）。
 *
 * <p>原 {@code RateLimitNode}（洋葱链）改造为 Advisor 体系。
 * 按 configId 从 {@link ModelRateLimiterRegistry} 获取限流器;无限流配置则直通。
 *
 * <p>call 路径：{@code limiter.acquire()} 排队等待放行，成功后调
 * {@code chain.nextCall(request)}，从响应中提取 {@code completionTokens} 调
 * {@code permit.complete(actualTokens)}；异常时 {@code permit.cancel()}。
 *
 * <p>stream 路径：用 {@code Flux.defer} 包裹，先 acquire，再在
 * {@code chain.nextStream} 上挂 {@code doOnNext}（chunk 估算 + usage 记账）、
 * {@code doOnCancel}（cancel permit）、{@code doOnError}（cancel permit）、
 * {@code doOnComplete}（complete permit 以 0 兜底）。
 *
 * <p>等待期间经 {@code a.emitter.emit(EmitEvent.transientOf(...))}
 * 发射 {@code system.notice} 瞬态事件（不落盘）。
 *
 * <p>位置：{@code getOrder()} = {@link ToolCallingAdvisor#DEFAULT_ORDER} + 500
 * （最内层，在 ContextCompression +400 之后）。
 */
public class RateLimitAdvisor implements CallAdvisor, StreamAdvisor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitAdvisor.class);

    private final AgentEntity a;
    private final ModelRateLimiterRegistry registry;

    public RateLimitAdvisor(AgentEntity a, ModelRateLimiterRegistry registry) {
        this.a = a;
        this.registry = registry;
    }

    @Override
    public String getName() {
        return "Rate Limit Advisor";
    }

    @Override
    public int getOrder() {
        // 最内层，在 ToolCallingAdvisor + ContextCompression (+400) 之后
        return ToolCallingAdvisor.DEFAULT_ORDER + 500;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        TaskEntry t = (TaskEntry) a.properties.get("taskEntry");
        String configId = t.snapshot.configId();
        Optional<ModelRateLimiter> limiter = registry.of(configId, t.snapshot.params());
        if (limiter.isEmpty()) {
            return chain.nextCall(request);
        }

        long[] noticeId = {0};
        ModelRateLimiter.Permit permit;
        try {
            permit = limiter.get().acquire((waitInfo, waitMs) -> {
                    if (noticeId[0] == 0) {
                        noticeId[0] = SnowflakeId.next();
                    }
                    a.emitter.emit(EmitEvent.transientOf(noticeId[0], "system.notice", null, null,
                            null,
                            "模型「" + configId + "」正在排队(在飞 " + waitInfo.inFlight()
                                    + " / 排队 " + waitInfo.waiters() + ")",
                            "waiting", null, EmitEvent.Mode.REPLACE));
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (ModelRateLimitException e) {
            throw e;
        }

        try {
            ChatClientResponse resp = chain.nextCall(request);
            long outputTokens = outputTokensOf(resp);
            permit.complete(outputTokens);
            return resp;
        } catch (RuntimeException e) {
            permit.cancel();
            throw e;
        }
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        TaskEntry t = (TaskEntry) a.properties.get("taskEntry");
        String configId = t.snapshot.configId();
        Optional<ModelRateLimiter> limiter = registry.of(configId, t.snapshot.params());
        if (limiter.isEmpty()) {
            return chain.nextStream(request);
        }

        ModelRateLimiter l = limiter.get();
        return Flux.defer(() -> {
            long[] noticeId = {0};
            ModelRateLimiter.Permit permit;
            try {
                permit = l.acquire((waitInfo, waitMs) -> {
                        if (noticeId[0] == 0) {
                            noticeId[0] = SnowflakeId.next();
                        }
                        a.emitter.emit(EmitEvent.transientOf(noticeId[0], "system.notice", null, null,
                                null,
                                "模型「" + configId + "」正在排队(在飞 " + waitInfo.inFlight()
                                        + " / 排队 " + waitInfo.waiters() + ")",
                                "waiting", null, EmitEvent.Mode.REPLACE));
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Flux.error(new RuntimeException(e));
            } catch (ModelRateLimitException e) {
                return Flux.error(e);
            }

            return chain.nextStream(request)
                    .doOnNext(chunk -> {
                        // chunk 估算：逐 chunk 累计估算输出 token
                        String text = chunkText(chunk);
                        if (text != null && !text.isEmpty()) {
                            permit.onChunk(text);
                        }
                        // usage 记账：检测到 usage 帧时用真实 completionTokens complete
                        long actual = outputTokensOf(chunk);
                        if (actual > 0) {
                            permit.complete(actual);
                        }
                    })
                    .doOnCancel(permit::cancel)
                    .doOnError(e -> permit.cancel())
                    .doOnComplete(() -> permit.complete(0));
        });
    }

    /** 从 ChatClientResponse 提取 completionTokens（usage 帧）。 */
    private static long outputTokensOf(ChatClientResponse resp) {
        if (resp == null) {
            return 0;
        }
        ChatResponse cr = resp.chatResponse();
        if (cr == null || cr.getMetadata() == null) {
            return 0;
        }
        Usage usage = cr.getMetadata().getUsage();
        if (usage == null || usage.getCompletionTokens() == null) {
            return 0;
        }
        return usage.getCompletionTokens();
    }

    /** 从 chunk 的 AssistantMessage 提取文本（正文 + 思考）用于估算。 */
    private static String chunkText(ChatClientResponse chunk) {
        if (chunk == null || chunk.chatResponse() == null
                || chunk.chatResponse().getResult() == null
                || chunk.chatResponse().getResult().getOutput() == null) {
            return null;
        }
        return chunk.chatResponse().getResult().getOutput().getText();
    }
}
