package dev.everyagent.plugin.api.event;

import java.util.function.Function;

import tools.jackson.databind.JsonNode;

/**
 * 事件扇出接口(供插件向 hub 连接广播事件)。
 *
 * <p>worker 的 {@code EventSink} 继承此接口,插件只依赖此接口,
 * 不直接依赖 worker 的 EventSink。
 *
 * <p>调用者用 {@code channelNamer} 给出「ownerKey → 频道名」的构造规则;
 * hub 侧只投递给已订阅该频道的连接。
 */
public interface StreamEmitter {

    /**
     * 扇出发布:对每条连接,用其 ownerKey 经 channelNamer 构造频道名后发送。
     *
     * @param channelNamer ownerKey → 频道名(如 {@code k -> "u." + k + ".tasks"})
     * @param event        事件类型
     * @param seq          序号(stream 频道携带,可 null)
     * @param payload      事件数据
     * @param ext          扩展字段(可 null;ext.target=sessionId 时 hub 定向投递)
     */
    void fanout(Function<String, String> channelNamer, String event, Long seq, JsonNode payload, JsonNode ext);
}
