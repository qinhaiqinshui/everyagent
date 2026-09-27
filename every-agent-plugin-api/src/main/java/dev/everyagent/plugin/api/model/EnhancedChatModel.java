package dev.everyagent.plugin.api.model;

import org.springframework.ai.chat.prompt.ChatOptions;

/**
 * ChatModelEnhancer 构建结果。
 *
 * <p>使用 {@link ChatOptions}（而非 OpenAiChatOptions），避免 plugin-api 依赖 spring-ai-openai。
 *
 * @param chatModel     增强后的 ChatModel（如组合容灾模型）
 * @param primaryOptions 主成员的 ChatOptions（用于 AgentModel 提取默认参数）
 */
public record EnhancedChatModel(
    org.springframework.ai.chat.model.ChatModel chatModel,
    ChatOptions primaryOptions
) {
}
