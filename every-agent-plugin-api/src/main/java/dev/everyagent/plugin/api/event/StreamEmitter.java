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
     * @param channelNamer ownerKey → 频道名(如 {@code k -> Channels.tasks(k, eventSink.workerId())})
     * @param event        事件类型
     * @param seq          序号(stream 频道携带,可 null)
     * @param payload      事件数据
     * @param ext          扩展字段(可 null;ext.target=sessionId 时 hub 定向投递)
     */
    void fanout(Function<String, String> channelNamer, String event, Long seq, JsonNode payload, JsonNode ext);

    /**
     * 本发布者(worker)自己的 workerId —— 即它与 hub 握手 hello 携带的 {@code clientId}。
     *
     * <p>这是<b>发布者身份</b>,不是任何业务域概念:构造带 worker 段的频道
     * ({@code Channels.tasks(k, workerId())} / {@code Channels.taskStream(k, workerId(), taskId)})
     * 时必须用它,这样每个接收方都能从频道名判定「这条数据属于哪台 worker」(架构 §4.3/§5.2)。
     * 同一 apiKey 下多台 worker 若无 worker 段,一台的任务会混进另一台的前端列表。
     *
     * <p>插件不得为此去依赖 worker 的 {@code WorkerProperties}(§14.9 红线):身份由本接口提供。
     *
     * @return 本机 workerId(非空)
     */
    String workerId();
}
