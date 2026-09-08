package dev.everyagent.worker;

import dev.everyagent.contract.frame.Frames;
import dev.everyagent.contract.json.Json;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 测试用最小 hub(JSR-356,寄宿在 worker 测试应用的 Tomcat 上):
 * 实现 hello/welcome/sub/unsub/pub→msg 扇出 + 数据包协议支撑——stream 频道的
 * 前端订阅/退订通知(subscriber.join/leave 发给 worker 连接)+ ext.target 定向投递,
 * 足够驱动 worker 的完整协议路径(DataPusher 按需定向推送)。
 * 不实现命名空间校验/presence/限速 —— 那些已在 hub 模块自己的测试里覆盖
 * (worker 侧的归属校验由 TaskManager 完成,与 hub 无关)。
 */
@ServerEndpoint("/fakehub")
public class FakeHub {

    private static final Map<String, Set<Session>> subs = new ConcurrentHashMap<>();
    /** session → role:clientId(hello 时登记;frontend/worker)。 */
    private static final Map<Session, String> identities = new ConcurrentHashMap<>();
    /** session → 已订阅的频道(leave 通知与断线清理用)。 */
    private static final Map<Session, Set<String>> sessionSubs = new ConcurrentHashMap<>();

    @OnOpen
    public void onOpen(Session session) {
        // 大帧测试(700KB 文件 base64 分批/写入)需要放开默认 8KB 缓冲
        session.setMaxTextMessageBufferSize(16 * 1024 * 1024);
        session.setMaxBinaryMessageBufferSize(16 * 1024 * 1024);
        sessionSubs.put(session, ConcurrentHashMap.newKeySet());
    }

    @OnMessage
    public void onMessage(Session session, String text) {
        JsonNode n;
        try {
            n = Json.parse(text);
        } catch (RuntimeException e) {
            return;
        }
        switch (n.path("type").asString("")) {
            case "hello" -> {
                String role = n.path("role").asString("frontend");
                identities.put(session, role + ":" + n.path("clientId").asString("?"));
                ObjectNode welcome = Json.obj();
                welcome.put("type", "welcome");
                welcome.put("ver", Frames.PROTOCOL_VERSION);
                welcome.put("sessionId", session.getId());
                welcome.put("serverTs", System.currentTimeMillis());
                send(session, welcome.toString());
            }
            case "sub" -> {
                String channel = n.path("channel").asString("");
                subs.computeIfAbsent(channel, k -> ConcurrentHashMap.newKeySet()).add(session);
                sessionSubs.computeIfAbsent(session, k -> ConcurrentHashMap.newKeySet()).add(channel);
                // 前端订阅 stream 频道 → 通知该 owner 的 worker 连接(subscriber.join)
                String taskId = streamTaskId(channel);
                if (taskId != null && isFrontend(session)) {
                    notifyWorkers(subscriberFrame(channel, "subscriber.join",
                            session.getId(), taskId));
                }
            }
            case "unsub" -> {
                String channel = n.path("channel").asString("");
                Set<Session> s = subs.get(channel);
                if (s != null) {
                    s.remove(session);
                }
                Set<String> mine = sessionSubs.get(session);
                if (mine != null) {
                    mine.remove(channel);
                }
                String taskId = streamTaskId(channel);
                if (taskId != null && isFrontend(session)) {
                    notifyWorkers(subscriberFrame(channel, "subscriber.leave",
                            session.getId(), taskId));
                }
            }
            case "pub" -> {
                ObjectNode msg = Json.obj();
                msg.put("type", "msg");
                msg.put("channel", n.path("channel").asString());
                msg.put("event", n.path("event").asString());
                if (n.has("seq") && !n.path("seq").isNull()) {
                    // seq 原样转发(真实 hub 逐字转发;雪花 ID 超 2^53,wire 保持字符串形态)
                    msg.set("seq", n.path("seq"));
                }
                msg.put("ts", n.path("ts").asLong());
                ObjectNode from = Json.obj();
                String[] id = identities.getOrDefault(session, "frontend:?").split(":", 2);
                from.put("clientId", id.length > 1 ? id[1] : "?");
                from.put("role", id[0]);
                from.put("sessionId", session.getId());
                msg.set("from", from);
                msg.set("payload", n.path("payload"));
                // 转发 ext(定向投递与 operate/initial/persist 标记依赖它)
                if (n.has("ext") && !n.path("ext").isNull()) {
                    msg.set("ext", n.path("ext"));
                }
                publish(n.path("channel").asString(), msg);
            }
            default -> {
            }
        }
    }

    @OnClose
    public void onClose(Session session) {
        identities.remove(session);
        for (Set<Session> s : subs.values()) {
            s.remove(session);
        }
        // 前端断开:对每个已订阅的 stream 频道发 subscriber.leave(DataPusher 随销毁)
        Set<String> mine = sessionSubs.remove(session);
        if (mine != null && isFrontend(session)) {
            for (String channel : mine) {
                String taskId = streamTaskId(channel);
                if (taskId != null) {
                    notifyWorkers(subscriberFrame(channel, "subscriber.leave",
                            session.getId(), taskId));
                }
            }
        }
    }

    /** 频道投递:带 ext.target 的帧只定向投给该 sessionId 连接,否则广播给订阅者。 */
    private static void publish(String channel, ObjectNode msg) {
        String wire = msg.toString();
        String target = msg.has("ext") ? msg.path("ext").path("target").asString("") : "";
        if (!target.isEmpty()) {
            for (Session s : subs.getOrDefault(channel, Set.of())) {
                if (s.isOpen() && s.getId().equals(target)) {
                    send(s, wire);
                }
            }
            return;
        }
        Set<Session> receivers = subs.get(channel);
        if (receivers == null) {
            return;
        }
        for (Session s : receivers) {
            if (s.isOpen()) {
                send(s, wire);
            }
        }
    }

    /** 向全部 worker 连接投递一帧(定向推送的 hub 通知侧)。 */
    private static void notifyWorkers(ObjectNode frame) {
        String wire = frame.toString();
        for (Map.Entry<Session, String> e : identities.entrySet()) {
            if (e.getValue().startsWith("worker:") && e.getKey().isOpen()) {
                send(e.getKey(), wire);
            }
        }
    }

    private static ObjectNode subscriberFrame(String channel, String event,
            String sessionId, String taskId) {
        ObjectNode msg = Json.obj();
        msg.put("type", "msg");
        msg.put("channel", channel);
        msg.put("event", event);
        msg.put("ts", System.currentTimeMillis());
        ObjectNode from = Json.obj();
        from.put("clientId", "hub");
        from.put("role", "hub");
        msg.set("from", from);
        ObjectNode payload = Json.obj();
        payload.put("sessionId", sessionId);
        payload.put("taskId", taskId);
        msg.set("payload", payload);
        return msg;
    }

    /** stream 频道 u.&lt;k&gt;.task.&lt;taskId&gt;.stream → taskId;非 stream 频道返回 null。 */
    private static String streamTaskId(String channel) {
        if (channel == null || !channel.startsWith("u.")) {
            return null;
        }
        int ownerEnd = channel.indexOf('.', 2);
        if (ownerEnd < 0) {
            return null;
        }
        String rest = channel.substring(ownerEnd + 1);
        if (!rest.startsWith("task.") || !rest.endsWith(".stream")) {
            return null;
        }
        String taskId = rest.substring("task.".length(), rest.length() - ".stream".length());
        return taskId.isEmpty() ? null : taskId;
    }

    private static boolean isFrontend(Session session) {
        String id = identities.get(session);
        return id == null || id.startsWith("frontend:");
    }

    private static void broadcast(String channel, String frame) {
        Set<Session> receivers = subs.get(channel);
        if (receivers == null) {
            return;
        }
        for (Session s : receivers) {
            if (s.isOpen()) {
                send(s, frame);
            }
        }
    }

    private static void send(Session session, String frame) {
        synchronized (session) {
            try {
                session.getBasicRemote().sendText(frame);
            } catch (Exception ignored) {
            }
        }
    }
}
