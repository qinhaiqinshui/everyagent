package dev.everyagent.plugin.api.model;

/**
 * ChatModel 增强器 SPI——允许插件在 {@code ChatModelFactory} 构建阶段介入。
 *
 * <p>与 {@link ModelRequestNode}（请求层增强）不同，本接口用于模型构建层替换：
 * 插件检测特定 provider（如 "model-pool"）时，用自身逻辑构建组合 ChatModel。
 *
 * <p>{@code ChatModelFactory} 通过注册表查找匹配的 enhancer，委托构建。
 * enhancer context 提供 {@code buildMember()} 回调，让插件复用工厂构建单个成员模型。
 */
public interface ChatModelEnhancer {

    /** 增强器 id（用于日志/调试）。 */
    String id();

    /** 是否支持给定 provider。 */
    boolean supports(String provider);

    /** 增强构建。 */
    EnhancedChatModel enhance(EnhancerContext ctx);
}
