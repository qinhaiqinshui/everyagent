package dev.everyagent.worker.hub;

import tools.jackson.databind.JsonNode;

import java.util.function.Function;

/**
 * 事件出口（基础设施·通信）：对全部连接扇出发布。
 * <p>调用者用 channelNamer 给出「ownerKey → 频道名」的构造规则；
 * hub 侧只投递给已订阅该频道的连接（订阅簿在 hub），未订阅不推。
 * <p>接口不带任何调用者语义（无 task/flow 字样）——
 * task 层传 {@code k -> "u."+k+".tasks"}，工作流层传自己的频道名。
 */
public interface EventSink {

    /**
     * 扇出发布：对每条连接，用其 ownerKey 经 channelNamer 构造频道名后发送。
     * @param channelNamer ownerKey → 频道名（如 {@code k -> "u." + k + ".tasks"}）
     * @param event 事件类型
     * @param seq 序号（stream 频道携带，可 null）
     * @param payload 事件数据
     * @param ext 扩展字段（可 null；ext.target=sessionId 时 hub 定向投递）
     */
    void fanout(Function<String, String> channelNamer, String event, Long seq, JsonNode payload, JsonNode ext);
}
