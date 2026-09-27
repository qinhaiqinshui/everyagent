package dev.everyagent.plugin.modelratelimit;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.model.ModelRequestChain;
import dev.everyagent.plugin.api.model.ModelRequestContext;
import dev.everyagent.plugin.api.model.ModelRequestNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Optional;

/**
 * 限流节点（模型请求洋葱链）—— 原 worker 的 {@code RateLimitedChatModel} 改造为
 * {@link ModelRequestNode}（方案文档步骤 4）。
 *
 * <p>按 configId 从 {@link ModelRateLimiterRegistry} 获取限流器;无限流配置则直通。
 * 下行段阻塞等待放行（虚拟线程 park），放行后注册 Permit 生命周期回调
 * （{@code onChunk} / {@code onComplete} / {@code onError}），再 {@code next.proceed(ctx)}
 * 进入内核真实模型调用。
 *
 * <p>经 {@link ModelRequestContext#events()} 发语义事件 {@code model_rate_wait}
 * （{@code persist=false} 瞬态不落盘），task 层（{@code TaskEvents.emit()}）映射为
 * wire 格式 {@code task.trace} + kind。插件不直接写 wire 格式。
 *
 * <p>不耦合 {@code ChatModel} / {@code Prompt} / {@code ChatResponse} / {@code Flux} /
 * {@code TaskEvents} / {@code agentId} / {@code taskId} / {@code DataPusher}。
 */
public class RateLimitNode implements ModelRequestNode {

    private final ModelRateLimiterRegistry registry;

    public RateLimitNode(ModelRateLimiterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String id() {
        return "model-rate-limit";
    }

    @Override
    public float order() {
        // 最外层（0），限流在任何其他节点之前
        return 0;
    }

    @Override
    public Object invoke(ModelRequestContext ctx, ModelRequestChain next) throws Exception {
        String configId = ctx.config().configId();
        Optional<ModelRateLimiter> limiter = registry.of(configId, ctx.config().params());
        if (limiter.isEmpty()) {
            // 无限流配置 → 直通
            return next.proceed(ctx);
        }

        // 下行：限流排队（阻塞等待放行，虚拟线程 park）
        ModelRateLimiter.Permit permit = limiter.get().acquire((waitInfo, waitMs) -> {
            // 经 EventEmitter 发语义事件（不知道 wire 格式/task.trace/wf.trace）
            ObjectNode payload = Json.obj()
                    .put("configId", waitInfo.configId())
                    .put("waiters", waitInfo.waiters())
                    .put("inFlight", waitInfo.inFlight())
                    .put("tpmPressure", waitInfo.tpmPressure());
            ctx.events().emit("model_rate_wait", payload, false); // false = 瞬态不落盘
        });

        // 注册回调：绑定 Permit 生命周期到 Flux 实际执行
        ctx.onChunk(permit::onChunk);       // 流中逐 chunk 累计估算
        ctx.onComplete(permit::complete);   // usage 帧到达时记账
        ctx.onError(e -> permit.cancel()); // 异常/取消时释放

        // 继续（stream 路径返回 Flux，尚未订阅；call 路径同步返回）
        return next.proceed(ctx);
    }
}
