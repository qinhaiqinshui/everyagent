package dev.everyagent.hub.ws;

import dev.everyagent.contract.frame.Frames;
import dev.everyagent.contract.ids.Ids;
import dev.everyagent.contract.json.Json;
import dev.everyagent.hub.config.HubProperties;
import dev.everyagent.hub.presence.PresenceService;
import dev.everyagent.hub.reg.ChannelRegistry;
import dev.everyagent.hub.reg.ConnectionRegistry;
import dev.everyagent.hub.util.HelloRateLimiter;
import dev.everyagent.hub.util.TokenBucket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.net.InetSocketAddress;

import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * 单个 WS 连接的完整状态机:握手、鉴权、sub/unsub/pub、命名空间校验、限速、心跳、慢消费者、抢占。
 * hub 对业务零理解:只读 type / channel(及 hello 握手字段),event/seq/payload/ext 原样转发;
 * 唯一约束是频道须落在连接自己的命名空间内(身份边界,非业务校验)。
 */
public final class HubConnection {

    private static final Logger log = LoggerFactory.getLogger(HubConnection.class);

    public enum Role {
        FRONTEND, WORKER;

        String wire() {
            return name().toLowerCase();
        }
    }

    private final WebSocketSession session;
    private final HubProperties props;
    private final ConnectionRegistry connections;
    private final ChannelRegistry channels;
    private final PresenceService presence;
    private final HelloRateLimiter helloLimiter;

    private final Sinks.Many<WebSocketMessage> outbound;
    private final Set<String> subs = ConcurrentHashMap.newKeySet();
    private final TokenBucket pubBucket;

    private volatile boolean authenticated;
    private volatile Role role;
    private volatile String ownerKey = "";
    private volatile String clientId = "";
    private volatile String sessionId = "";
    private volatile tools.jackson.databind.JsonNode helloMeta;
    private volatile long lastInboundAt = System.currentTimeMillis();
    private volatile boolean closed;
    private volatile boolean preemptedFlag;
    private volatile boolean cleanedUp;
    private volatile ScheduledFuture<?> pingFuture;

    /** 测试可观测:收到的控制帧(ping/pong)计数——用于经验证 pong 可观测性。 */
    volatile long controlFramesSeen;

    HubConnection(WebSocketSession session, HubProperties props, ConnectionRegistry connections,
                  ChannelRegistry channels, PresenceService presence, HelloRateLimiter helloLimiter) {
        this.session = session;
        this.props = props;
        this.connections = connections;
        this.channels = channels;
        this.presence = presence;
        this.helloLimiter = helloLimiter;
        // 有界出口队列 = 慢消费者保护:队列满时 emit 失败 → 断开该连接(前端重连后 sync 补齐)
        this.outbound = Sinks.many().unicast()
                .onBackpressureBuffer(new ArrayBlockingQueue<>(props.getOutboundQueueLimit()));
        this.pubBucket = new TokenBucket(props.getPubBurst(), props.getPubRatePerSecond());
    }

    // ---- 生命周期 ----

    void startPings(java.util.concurrent.ScheduledExecutorService scheduler) {
        long interval = Math.max(1000, props.getPingIntervalMs());
        pingFuture = scheduler.scheduleAtFixedRate(this::safePingTick, interval, interval,
                java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    Flux<WebSocketMessage> outboundFlux() {
        return outbound.asFlux();
    }

    public Set<String> subscriptions() {
        return subs;
    }

    public boolean isWorker() {
        return role == Role.WORKER;
    }

    public boolean isFrontend() {
        return role == Role.FRONTEND;
    }

    /** 管理连接 = 以 apiKey=hubKey 连接的 frontend(ownerKey == sha256(hubKey)),可见跨 owner 的 worker 全量目录。 */
    public boolean isManager() {
        return isFrontend() && ownerKey.equals(props.getHubKeySha());
    }

    /** hello 时由 connections.register 分配的会话 id(s-xxx),未认证前为空串。 */
    public String sessionId() {
        return sessionId;
    }

    public String ownerKey() {
        return ownerKey;
    }

    public String clientId() {
        return clientId;
    }

    /** hello 携带的 meta(presence 快照重放用),未携带时为 null 节点。 */
    public tools.jackson.databind.JsonNode helloMeta() {
        return helloMeta;
    }

    /** 入站流终止(对端关闭或传输断开)。 */
    void transportGone() {
        closeInternal(CloseStatus.NORMAL);
    }

    /** 同 ownerKey + role=worker + clientId 的新连接抢占旧连接:先 offline 再关(架构 §13.2)。 */
    public void preempted() {
        preemptedFlag = true;
        if (authenticated && isWorker()) {
            presence.workerOffline(ownerKey, clientId, helloMeta);
        }
        closeInternal(new CloseStatus(4000, "preempted"));
    }

    void cleanup() {
        if (cleanedUp) {
            return;
        }
        cleanedUp = true;
        ScheduledFuture<?> f = pingFuture;
        if (f != null) {
            f.cancel(false);
        }
        channels.unsubscribeAll(this);
        connections.deregister(this);
        if (authenticated && isWorker() && !preemptedFlag) {
            presence.workerOffline(ownerKey, clientId, helloMeta);
        }
    }

    // ---- 入站 ----

    void onFrame(WebSocketMessage message) {
        lastInboundAt = System.currentTimeMillis();
        if (message.getType() != WebSocketMessage.Type.TEXT) {
            controlFramesSeen++;
            return;
        }
        var buffer = message.getPayload();
        if (buffer.readableByteCount() > props.getMaxFrameBytes()) {
            sendError(Frames.E_FRAME_TOO_LARGE, "frame exceeds " + props.getMaxFrameBytes() + " bytes");
            return;
        }
        byte[] bytes = new byte[buffer.readableByteCount()];
        buffer.read(bytes);

        tools.jackson.databind.JsonNode node;
        try {
            node = Json.parse(bytes);
        } catch (RuntimeException e) {
            closeProtocol("malformed json");
            return;
        }
        if (!node.isObject()) {
            closeProtocol("frame must be a json object");
            return;
        }
        String type = node.path("type").asString("");
        switch (type) {
            case Frames.HELLO -> handleHello(node);
            case Frames.SUB -> {
                if (requireAuth()) {
                    handleSub(node);
                }
            }
            case Frames.UNSUB -> {
                if (requireAuth()) {
                    handleUnsub(node);
                }
            }
            case Frames.PUB -> {
                if (requireAuth()) {
                    handlePub(node);
                }
            }
            default -> closeProtocol("bad or server-side frame type: '" + type + "'");
        }
    }

    private boolean requireAuth() {
        if (authenticated) {
            return true;
        }
        sendErrorAndClose(Frames.E_NOT_AUTHENTICATED, "hello first");
        return false;
    }

    private void handleHello(tools.jackson.databind.JsonNode node) {
        if (authenticated) {
            sendErrorAndClose(Frames.E_NOT_AUTHENTICATED, "already authenticated");
            return;
        }
        if (!helloLimiter.tryAcquire(remoteIp())) {
            sendErrorAndClose(Frames.E_RATE_LIMITED, "hello rate limit exceeded");
            return;
        }
        var verNode = node.path("ver");
        int ver = 0;
        if (verNode.isNumber()) {
            ver = verNode.asInt();
        } else if (verNode.isString()) {
            try {
                ver = Integer.parseInt(verNode.asString().trim());
            } catch (NumberFormatException ignore) {
                // 落到 VERSION_MISMATCH
            }
        }
        if (ver != Frames.PROTOCOL_VERSION) {
            sendErrorAndClose(Frames.E_VERSION_MISMATCH, "supported protocol version: " + Frames.PROTOCOL_VERSION);
            return;
        }
        String roleStr = node.path("role").asString("");
        String apiKey = node.path("apiKey").asString("");
        String cid = node.path("clientId").asString("");
        String hubKey = node.path("hubKey").asString("");
        boolean roleOk = roleStr.equals("frontend") || roleStr.equals("worker");
        if (!roleOk || apiKey.isEmpty() || !cid.matches("[a-zA-Z0-9._-]{1,64}")) {
            sendErrorAndClose(Frames.E_NOT_AUTHENTICATED, "bad hello fields");
            return;
        }
        String k = Ids.ownerKey(apiKey);
        // hub-key 保护(必填,启动已校验):无论 frontend/worker 都须携带正确 hubKey(缺/错一律拒绝)。
        // apiKey 不做白名单校验:它定义 ownerKey 命名空间(身份即隔离),与 worker 交互须持其 apiKey。
        if (hubKey.isEmpty() || !Ids.ownerKey(hubKey).equals(props.getHubKeySha())) {
            sendErrorAndClose(Frames.E_NOT_AUTHENTICATED, "hub key not allowed");
            return;
        }
        this.role = "worker".equals(roleStr) ? Role.WORKER : Role.FRONTEND;
        this.ownerKey = k;
        this.clientId = cid;
        this.helloMeta = node.path("meta");
        this.authenticated = true;
        this.sessionId = connections.register(this);

        var welcome = Json.obj();
        welcome.put("type", Frames.WELCOME);
        welcome.put("ver", Frames.PROTOCOL_VERSION);
        welcome.put("sessionId", sessionId);
        welcome.put("serverTs", System.currentTimeMillis());
        deliver(Json.write(welcome));

        if (isWorker()) {
            presence.workerOnline(ownerKey, clientId, helloMeta);
        }
        log.info("hello ok: role={} clientId={} sessionId={} remote={}", roleStr, cid, sessionId, remoteIp());
    }

    private void handleSub(tools.jackson.databind.JsonNode node) {
        String channel = node.path("channel").asString("");
        String err = ChannelRules.channelError(channel, ownerKey);
        if (err != null) {
            sendError(Frames.E_ACL_DENIED, err);
            return;
        }
        channels.subscribe(channel, this);
        subs.add(channel);
        // 订阅 presence 通道即补发在线快照:迟到的前端也能发现已连接的 worker(架构 §3.2)。
        if (channel.equals(PresenceService.workersChannel(ownerKey))) {
            if (isManager()) {
                // 管理连接:订阅自己的 u.<managerK>.workers 补发全部在线 worker(跨 ownerKey 全量目录)。
                presence.snapshotAll(this);
            } else {
                presence.snapshot(ownerKey, this);
            }
        }
    }

    private void handleUnsub(tools.jackson.databind.JsonNode node) {
        String channel = node.path("channel").asString("");
        if (ChannelRules.channelError(channel, ownerKey) != null) {
            return; // 退订不存在的非法频道:静默
        }
        channels.unsubscribe(channel, this);
        subs.remove(channel);
    }

    private void handlePub(tools.jackson.databind.JsonNode node) {
        String channel = node.path("channel").asString("");
        String err = ChannelRules.channelError(channel, ownerKey);
        if (err != null) {
            sendError(Frames.E_ACL_DENIED, err);
            return;
        }
        // [临时关闭] pub 限流:本地/单用户部署下 100/s 稳态 + 100 突发过小,单任务流式输出即触发 RATE_LIMITED 丢帧。
        // 已在 AGENTS 复盘记录,后续若遇 hub 被单连接冲垮再恢复限流(或改为每任务/可配置)。
        // if (!pubBucket.tryConsume(1)) {
        //     sendError(Frames.E_RATE_LIMITED, "pub rate limit exceeded");
        //     return;
        // }
        // 原样转发:只改 type 并附加已认证 from,event/seq/payload/ext(含未知字段)全部保留。
        // from 附带 sessionId(除 clientId/role 外):worker 据此识别「是哪条前端会话发的消息」,
        // 用于兜底补建定向推送器(worker 重启后前端不刷新,收不到 subscriber.join 通知)。
        var frame = (tools.jackson.databind.node.ObjectNode) node;
        frame.put("type", Frames.MSG);
        var from = Json.obj();
        from.put("clientId", clientId);
        from.put("role", role.wire());
        from.put("sessionId", sessionId);
        frame.set("from", from);
        channels.publish(channel, Json.write(frame));
    }

    // ---- 出站 ----

    /** 投递一帧文本;出口队列满(慢消费者)→ 断开该连接。 */
    public boolean deliver(String wire) {
        if (closed) {
            return false;
        }
        WebSocketMessage message = session.textMessage(wire);
        synchronized (this) {
            if (closed) {
                return false;
            }
            var result = outbound.tryEmitNext(message);
            if (result.isFailure()) {
                log.warn("outbound emit failed ({}), closing slow/broken connection {}", result, sessionId);
                closeInternal(CloseStatus.GOING_AWAY);
                return false;
            }
        }
        return true;
    }

    private void safePingTick() {
        try {
            pingTick();
        } catch (RuntimeException e) {
            log.debug("ping tick error on {}", sessionId, e);
        }
    }

    private void pingTick() {
        if (closed) {
            return;
        }
        long stale = props.getStaleReadMs();
        if (stale > 0 && System.currentTimeMillis() - lastInboundAt > stale) {
            log.info("closing stale connection {} (no inbound for {}ms)", sessionId, stale);
            closeInternal(new CloseStatus(1001, "stale"));
            return;
        }
        WebSocketMessage ping = session.pingMessage(factory -> factory.wrap(new byte[0]));
        synchronized (this) {
            if (closed) {
                return;
            }
            if (outbound.tryEmitNext(ping).isFailure()) {
                closeInternal(CloseStatus.GOING_AWAY);
            }
        }
    }

    private void sendError(String code, String detail) {
        var frame = Json.obj();
        frame.put("type", Frames.ERROR);
        frame.put("code", code);
        frame.put("detail", detail);
        deliver(Json.write(frame));
    }

    private void sendErrorAndClose(String code, String detail) {
        sendError(code, detail);
        closeProtocol(code + ": " + detail);
    }

    private void closeProtocol(String reason) {
        closeInternal(new CloseStatus(1002, reason));
    }

    private synchronized void closeInternal(CloseStatus status) {
        if (closed) {
            return;
        }
        closed = true;
        outbound.tryEmitComplete();
        session.close(status).subscribe(v -> {
        }, e -> log.debug("close handshake error on {}: {}", sessionId, e.toString()));
    }

    private String remoteIp() {
        InetSocketAddress remote = session.getHandshakeInfo().getRemoteAddress();
        return remote != null && remote.getAddress() != null ? remote.getAddress().getHostAddress() : "unknown";
    }
}
