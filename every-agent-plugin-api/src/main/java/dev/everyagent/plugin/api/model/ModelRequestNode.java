package dev.everyagent.plugin.api.model;

/**
 * 模型请求洋葱链节点（仿 {@code TaskLifecycleNode}，Servlet Filter 风格）。
 *
 * <p>{@link #invoke} 内调用 {@code next.proceed(ctx)} 之前的代码 = 下行；之后的代码 = 上行。
 * 链上位置由 {@link #order()} 决定：升序 = 外 → 内（洋葱下行序）。
 *
 * <p>典型用法：限流插件节点在下行段 {@code limiter.acquire()}（阻塞等待放行，
 * 虚拟线程 park），放行后注册 {@link ModelRequestContext#onChunk} /
 * {@link ModelRequestContext#onComplete} / {@link ModelRequestContext#onError} 回调，
 * 再 {@code next.proceed(ctx)} 进入内核真实模型调用。
 */
public interface ModelRequestNode {
    /** 节点 id（唯一标识）。 */
    String id();

    /** 链上位置：升序 = 外 → 内（洋葱下行序）。float 允许任意插位；同 order 按注册顺序（稳定排序）。 */
    float order();

    /**
     * 执行本节点逻辑，必要时调 {@code next.proceed(ctx)} 进入下一层。
     *
     * @param ctx  模型请求上下文（只读参考 + 回调注册）
     * @param next 下一层链
     * @return 内核返回值（stream 路径为 {@code Flux<ChatResponse>}，call 路径为 {@code ChatResponse}）
     */
    Object invoke(ModelRequestContext ctx, ModelRequestChain next) throws Exception;
}
