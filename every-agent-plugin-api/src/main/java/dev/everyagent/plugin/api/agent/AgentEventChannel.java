package dev.everyagent.plugin.api.agent;

import dev.everyagent.plugin.api.model.EventEmitter;

/**
 * 事件出口端口（TaskEvents 实现此接口）。
 *
 * <p>agent 层通过此端口发射事件，不直接引用 TaskEvents。
 * 统一为 {@link EventEmitter} 的子接口——所有事件经 {@link EventEmitter#emit} 单一入口，
 * 不再有 25 个专用方法签名。
 */
public interface AgentEventChannel extends EventEmitter {
}
