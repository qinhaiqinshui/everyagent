package dev.everyagent.worker.hub;

import dev.everyagent.plugin.api.event.StreamEmitter;

/**
 * 事件出口（基础设施·通信）：对全部连接扇出发布。
 * <p>调用者用 channelNamer 给出「ownerKey → 频道名」的构造规则；
 * hub 侧只投递给已订阅该频道的连接（订阅簿在 hub），未订阅不推。
 * <p>接口不带任何调用者语义（无 task/flow 字样）——
 * task 层传 {@code k -> "u."+k+".tasks"}，工作流层传自己的频道名。
 *
 * <p>继承 {@link StreamEmitter}，插件经 {@code WorkerServices.stream()} 获取此接口，
 * 不直接依赖 worker 的 EventSink。
 */
public interface EventSink extends StreamEmitter {
}
