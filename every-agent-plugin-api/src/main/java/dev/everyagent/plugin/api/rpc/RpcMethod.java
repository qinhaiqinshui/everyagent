package dev.everyagent.plugin.api.rpc;

/**
 * RPC 方法处理器接口 —— 插件经 {@code WorkerPluginContext.registerRpcMethod} 注册。
 *
 * <p>ctx 参数为 worker 的 {@code RpcContext}，使用时强转为具体类型。
 * 此接口使 plugin-api 不依赖 worker 的 RPC 基础设施类型。
 */
@FunctionalInterface
public interface RpcMethod {

    /**
     * 处理 RPC 请求。
     *
     * @param ctx RPC 上下文（worker 的 RpcContext，使用时强转）
     * @throws Exception 处理失败
     */
    void handle(Object ctx) throws Exception;
}
