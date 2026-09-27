package dev.everyagent.plugin.api.model;

/**
 * 模型池成员规格。
 *
 * @param config 成员模型配置
 * @param apiKey 成员 API Key
 */
public record MemberSpec(ModelConfig config, String apiKey) {
}
