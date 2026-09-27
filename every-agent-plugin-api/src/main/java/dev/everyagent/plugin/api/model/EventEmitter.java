package dev.everyagent.plugin.api.model;

import tools.jackson.databind.JsonNode;

/**
 * 通用事件发射器（plugin-api 接口）。
 *
 * <p>作为插件 / 底层组件发射事件的统一出口。插件只管发语义事件名 + payload，
 * 不知道 wire 格式（task.trace / wf.trace）。语义 → wire 映射在 task 层完成。
 *
 * <ul>
 *   <li>{@code eventName}：语义事件名（如 {@code "model_rate_wait"}），不是 wire 格式。</li>
 *   <li>{@code payload}：事件载荷（Jackson {@link JsonNode}）。</li>
 *   <li>{@code persist}：是否落盘。{@code false} = 瞬态事件（如限流等待通知），
 *       不写入本地事件日志磁盘；{@code true} = 持久化事件。</li>
 * </ul>
 */
public interface EventEmitter {
    void emit(String eventName, JsonNode payload, boolean persist);
}
