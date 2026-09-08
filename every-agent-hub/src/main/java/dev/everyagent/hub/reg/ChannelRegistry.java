package dev.everyagent.hub.reg;

import dev.everyagent.contract.frame.Frames;
import dev.everyagent.contract.json.Json;
import dev.everyagent.hub.ws.HubConnection;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * channel → 订阅者集合;pub 到来即遍历投递。无缓冲、无持久化。
 *
 * <p>架构演进(混合模型:hub 感知 stream 频道订阅):前端在 u.&lt;ownerKey&gt;.task.&lt;taskId&gt;.stream
 * 频道上 sub/unsub(或连接断开 cleanup)时,向该 owner 下全部在线 worker 连接投递
 * subscriber.join / subscriber.leave 通知帧(msg 帧,channel=原 stream 频道,
 * payload={sessionId, taskId}),worker 据此按 (sessionId,taskId) 建/销定向推送器,
 * 只推运行中任务的实时增量(历史/补齐仍走 task.poll 拉取)。仅「前端 + stream 频道」触发;
 * worker 订阅 stream 频道或前端订阅非 stream 频道均不通知。通知为无状态 fire-and-forget
 * msg,hub 不存任何订阅簿(零状态红线不变)。
 */
@Component
public class ChannelRegistry {

    private final ConcurrentHashMap<String, Set<HubConnection>> byChannel = new ConcurrentHashMap<>();
    private final ConnectionRegistry connections;

    public ChannelRegistry(ConnectionRegistry connections) {
        this.connections = connections;
    }

    public void subscribe(String channel, HubConnection conn) {
        byChannel.computeIfAbsent(channel, k -> ConcurrentHashMap.newKeySet()).add(conn);
        // 前端订阅 stream 频道 → 通知该 owner 下全部在线 worker:subscriber.join
        StreamRef ref = parseStreamChannel(channel);
        if (ref != null && conn.isFrontend()) {
            notifyWorkers(channel, ref, conn.sessionId(), Frames.SUBSCRIBER_JOIN);
        }
    }

    public void unsubscribe(String channel, HubConnection conn) {
        boolean removed = false;
        Set<HubConnection> set = byChannel.get(channel);
        if (set != null) {
            removed = set.remove(conn);
            if (set.isEmpty()) {
                byChannel.remove(channel, set);
            }
        }
        if (!removed) {
            return; // 从未订阅过该频道:不触发 leave 通知
        }
        // 前端退订 stream 频道(含 cleanup 经 unsubscribeAll 收口)→ subscriber.leave
        StreamRef ref = parseStreamChannel(channel);
        if (ref != null && conn.isFrontend()) {
            notifyWorkers(channel, ref, conn.sessionId(), Frames.SUBSCRIBER_LEAVE);
        }
    }

    public void unsubscribeAll(HubConnection conn) {
        for (String channel : conn.subscriptions()) {
            unsubscribe(channel, conn);
        }
    }

    /** 投递给频道全部订阅者;返回送达连接数。 */
    public int publish(String channel, String wire) {
        // 定向投递:msg 帧 ext.target 指定 sessionId 时,只投给该连接,不广播。
        // 解析容错:wire 非对象 / ext 缺失或非对象 / target 缺失或空串 → 一律走原广播。
        String target = readTarget(wire);
        if (target != null) {
            HubConnection conn = connections.findBySession(target);
            if (conn == null) {
                return 0;
            }
            return conn.deliver(wire) ? 1 : 0;
        }
        Set<HubConnection> set = byChannel.get(channel);
        if (set == null || set.isEmpty()) {
            return 0;
        }
        int delivered = 0;
        for (HubConnection conn : set) {
            if (conn.deliver(wire)) {
                delivered++;
            }
        }
        return delivered;
    }

    /** 读取 msg 帧 ext.target(字符串 sessionId);无定向目标时返回 null。解析失败一律返回 null(退化为广播)。 */
    private static String readTarget(String wire) {
        try {
            var node = Json.parse(wire);
            if (!node.isObject()) {
                return null;
            }
            var ext = node.path("ext");
            if (!ext.isObject()) {
                return null;
            }
            var target = ext.path("target");
            if (!target.isTextual()) {
                return null;
            }
            String value = target.asText();
            return value.isEmpty() ? null : value;
        } catch (RuntimeException e) {
            return null; // wire 非法 JSON:保持原广播行为
        }
    }

    public int subscriberCount(String channel) {
        Set<HubConnection> set = byChannel.get(channel);
        return set == null ? 0 : set.size();
    }

    // ---- stream 频道订阅通知(混合模型:worker 定向推送器的建/销触发)----

    /** stream 频道解析结果:ownerKey = 第二个点分隔段,taskId = task. 之后、.stream 之前。 */
    record StreamRef(String ownerKey, String taskId) {
    }

    /** 解析 stream 频道 u.&lt;ownerKey&gt;.task.&lt;taskId&gt;.stream;非 stream 频道返回 null。 */
    static StreamRef parseStreamChannel(String channel) {
        if (channel == null || !channel.startsWith("u.")) {
            return null;
        }
        int ownerEnd = channel.indexOf('.', 2);
        if (ownerEnd < 0) {
            return null;
        }
        String ownerKey = channel.substring(2, ownerEnd);
        if (ownerKey.isEmpty()) {
            return null;
        }
        String rest = channel.substring(ownerEnd + 1);
        if (!rest.startsWith("task.") || !rest.endsWith(".stream")) {
            return null;
        }
        String taskId = rest.substring("task.".length(), rest.length() - ".stream".length());
        if (taskId.isEmpty()) {
            return null;
        }
        return new StreamRef(ownerKey, taskId);
    }

    /** 向该 owner 下全部在线 worker 投递订阅通知;已关闭的 worker 连接 deliver 返回 false,静默跳过。 */
    private void notifyWorkers(String channel, StreamRef ref, String sessionId, String event) {
        String wire = subscriberFrame(channel, ref.taskId(), sessionId, event);
        for (HubConnection worker : connections.onlineWorkers(ref.ownerKey())) {
            worker.deliver(wire);
        }
    }

    /** 构造 subscriber.join / subscriber.leave 通知帧(msg 帧,channel=原 stream 频道,payload={sessionId, taskId})。 */
    private static String subscriberFrame(String channel, String taskId, String sessionId, String event) {
        var payload = Json.obj();
        payload.put("sessionId", sessionId);
        payload.put("taskId", taskId);
        var frame = Json.obj();
        frame.put("type", Frames.MSG);
        frame.put("channel", channel);
        frame.put("event", event);
        frame.put("ts", System.currentTimeMillis());
        frame.set("payload", payload);
        var from = frame.putObject("from");
        from.put("clientId", "hub");
        from.put("role", "hub");
        return Json.write(frame);
    }
}
