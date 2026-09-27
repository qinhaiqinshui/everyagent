package dev.everyagent.plugin.api.model;

/**
 * 模型请求洋葱链的「下一层」句柄。
 *
 * <p>节点在 {@link ModelRequestNode#invoke} 中调用 {@code next.proceed(ctx)}
 * 进入下一层（或最终到达内核真实模型调用）。
 */
@FunctionalInterface
public interface ModelRequestChain {
    Object proceed(ModelRequestContext ctx) throws Exception;
}
