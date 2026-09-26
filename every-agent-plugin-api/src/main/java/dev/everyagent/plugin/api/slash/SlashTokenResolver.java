package dev.everyagent.plugin.api.slash;

import tools.jackson.databind.JsonNode;

/**
 * Slash token 提交解析器接口 —— 插件经 {@code WorkerPluginContext.registerSlashTokenResolver} 注册。
 *
 * <p>每个 kind 对应一种 opaque token 的提交解析逻辑。
 * {@link #resolveSubmissionText} 返回空串表示「从 AI 上下文剥离该 token」。
 *
 * <p>此接口对齐 worker 的 {@code SlashTokenHandler.SlashTokenResolver}，
 * 但不暴露 worker 的 {@code TaskEntry} 类型——
 * 仅支持无任务上下文的解析（插件 resolvers 当前都不需要任务上下文）。
 */
public interface SlashTokenResolver {

    /** 固定 kind（与构造 opaque token 时的 kind 一致）。 */
    String kind();

    /**
     * 把 payload 解析为提交给 AI 的替换文本。
     * 返回空串表示「清空该 token」（从模型上下文剥离）。
     */
    String resolveSubmissionText(JsonNode payload);
}
