package dev.everyagent.plugin.api.model;

import java.util.function.Consumer;
import java.util.function.LongConsumer;

/**
 * 模型请求上下文（洋葱链节点只读参考 + 回调注册）。
 *
 * <p>由内核在构建链时创建并传入每个节点。节点可：
 * <ul>
 *   <li>读取 {@link #config()} 做限流参数解析、日志、自适应决策等；</li>
 *   <li>经 {@link #events()} 发射语义事件（不知道 wire 格式）；</li>
 *   <li>注册 chunk / complete / error 回调，内核在对应时机逐个调用。</li>
 * </ul>
 *
 * <p>回调注册模式：节点在 {@code next.proceed()} 之前注册回调，proceed 对于
 * stream 路径返回 Flux（deferred，尚未订阅）。节点的阻塞等待（如 limiter.acquire）
 * 发生在 Flux.defer 内（订阅时执行），注册回调在 acquire 返回后、proceed 之前 ——
 * 顺序正确。
 */
public interface ModelRequestContext {

    /**
     * 模型配置快照（只读参考）。
     * 含 configId / provider / baseUrl / model / params。
     * 插件可据此做限流参数解析、日志、自适应决策等。
     * 注意：下游内核使用缓存的 ChatModel（按 configId 缓存 OpenAiChatModel），
     * 不消费此配置。修改此配置不影响实际请求。
     * 未来支持动态配置时，内核改为从 context 取模型（版本化缓存），接口不变。
     */
    ModelConfig config();

    /** 通用事件发射器（语义事件名 + payload，不知道 wire 格式）。 */
    EventEmitter events();

    /** 注册 chunk 回调（内核 doOnNext 时调用）。 */
    void onChunk(Consumer<String> callback);

    /** 注册完成回调（内核检测到 usage 帧或 call 返回时调用）。 */
    void onComplete(LongConsumer callback);

    /** 注册错误 / 取消回调（内核 doOnError / doOnCancel 时调用）。 */
    void onError(Consumer<Throwable> callback);
}
