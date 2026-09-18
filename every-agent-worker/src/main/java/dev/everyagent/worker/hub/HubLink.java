package dev.everyagent.worker.hub;

import dev.everyagent.contract.frame.Frames;
import dev.everyagent.contract.ids.Ids;
import dev.everyagent.contract.json.Json;
import dev.everyagent.worker.proto.ShortIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 与单个 hub 的 WS 连接(架构 §5.2;多 hub 时每 {url, apiKey, hubKey} 一条,由 HubPool 编排):
 * JDK HttpClient 单连接;出站帧经单线程串行发送(JDK WS 禁止并发 sendText);
 * 断线指数退避重连(1s→30s + 抖动);重连后重发 hello 与全部订阅;
 * 空闲时应用层心跳保活(每 5s 探测一次,仅本周期无帧才发 ping;ping 后 15s 无任何帧判死主动断连,§4.2);
 * 事件日志才是事实源,断线期间的出站帧允许丢弃(Shipper 重连即跳尾,前端 sync 补齐)。
 */
public class HubLink {

    public interface Listener {
        default void onHubConnected() {
        }

        default void onHubDisconnected() {
        }

        /** 收到 msg 帧(完整帧,含 channel/event/payload/from/ext)。 */
        default void onHubMessage(JsonNode msgFrame) {
        }
    }

    private static final Logger log = LoggerFactory.getLogger(HubLink.class);

    /** 心跳探测间隔(§4.2:仅本周期无任何帧到达时才发 ping)。 */
    private static final long HEARTBEAT_INTERVAL_MS = 5000;
    /** 判死阈值:发出 ping 后该时长内无任何帧到达即判死(不是"距上帧超时",避免误杀合法空闲连接)。 */
    private static final long HEARTBEAT_DEAD_MS = 15000;

    private final String name;
    private final String url;
    private final String apiKey;
    private final String hubKey;
    private final String workerId;
    private final String k;
    private final long initialBackoffMs;
    private final long maxBackoffMs;

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final LinkedBlockingQueue<String> outbound = new LinkedBlockingQueue<>(10_000);
    private final Set<String> desiredSubs = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final HttpClient http = HttpClient.newHttpClient();
    private volatile WebSocket ws;
    private volatile CompletableFuture<Void> lostFuture;
    private volatile boolean connected;
    private volatile boolean stopped;
    private Thread connectThread;
    private Thread senderThread;
    private Thread heartbeatThread;
    /** 最近一次入站完整帧到达时间(任何帧——welcome/msg/error/pong——都算连接活着)。 */
    private volatile long lastFrameAt = System.currentTimeMillis();
    /** 未应答 ping 的发出时间;0 表示无在途 ping(收到任何帧即清零)。 */
    private volatile long pingSentAt;

    public HubLink(String name, String url, String apiKey, String workerId, String hubKey,
            long initialBackoffMs, long maxBackoffMs) {
        this.name = name;
        this.url = url;
        this.apiKey = apiKey;
        this.hubKey = hubKey == null ? "" : hubKey;
        this.workerId = workerId;
        this.k = Ids.ownerKey(apiKey);
        this.initialBackoffMs = initialBackoffMs;
        this.maxBackoffMs = maxBackoffMs;
    }

    /** 连接名(配置序号),日志与 Shipper 游标键。 */
    public String name() {
        return name;
    }

    public String url() {
        return url;
    }

    /** 本连接的 ownerKey = sha256(apiKey)——频道命名空间/路由的依据(连接层身份,不决定任务归属)。 */
    public String k() {
        return k;
    }

    public String workerId() {
        return workerId;
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void start() {
        senderThread = Thread.ofVirtual().name("hub-out-" + name).start(this::senderLoop);
        connectThread = Thread.ofVirtual().name("hub-connect-" + name).start(this::connectLoop);
        heartbeatThread = Thread.ofVirtual().name("hub-heartbeat-" + name).start(this::heartbeatLoop);
    }

    public void stop() {
        stopped = true;
        WebSocket w = ws;
        if (w != null) {
            w.abort();
        }
        connectThread.interrupt();
        senderThread.interrupt();
        heartbeatThread.interrupt();
    }

    public boolean isConnected() {
        return connected;
    }

    public void sub(String channel) {
        desiredSubs.add(channel);
        if (connected) {
            send(Frames.wireSub(channel));
        }
    }

    public void unsub(String channel) {
        desiredSubs.remove(channel);
        if (connected) {
            send(Frames.wireUnsub(channel));
        }
    }

    /** 发布事件帧;seq 仅 stream 频道携带。非阻塞:入队失败即丢弃(事件日志是事实源)。 */
    public void pub(String channel, String event, Long seq, JsonNode payload, JsonNode ext) {
        send(Frames.wirePub(ShortIds.mid(), channel, event, seq, System.currentTimeMillis(), payload, ext));
    }

    /**
     * 发布事件帧,显式指定 ts(磁盘历史回放保留原始时间戳)。
     * 非阻塞:入队失败即丢弃(事件日志是事实源)。
     */
    public void pub(String channel, String event, Long seq, long ts, JsonNode payload, JsonNode ext) {
        send(Frames.wirePub(ShortIds.mid(), channel, event, seq, ts, payload, ext));
    }

    public void send(String frame) {
        if (!outbound.offer(frame)) {
            log.warn("[{}] 出站队列已满,丢弃帧(事件日志是事实源,重连后会补)", name);
        }
    }

    /** 强制重连(Shipper 停滞 watchdog 调用)。abort 不保证回调监听器,须同时手动唤醒 lost。 */
    public void forceReconnect() {
        CompletableFuture<Void> lost = lostFuture;
        if (lost != null) {
            lost.complete(null);
        }
        WebSocket w = ws;
        if (w != null) {
            log.warn("[{}] 强制断开 hub 连接以触发重连", name);
            w.abort();
        }
    }

    // ---- 连接循环 ----

    private void connectLoop() {
        long backoffMs = initialBackoffMs;
        while (!stopped) {
            try {
                runConnection();
                backoffMs = initialBackoffMs;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("[{}] hub 连接异常: {}", name, e.toString());
            }
            if (stopped) {
                return;
            }
            long sleep = backoffMs + ThreadLocalRandom.current().nextLong(0, backoffMs / 2 + 1);
            log.info("{}ms 后重连 hub {}", sleep, url);
            try {
                Thread.sleep(sleep);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            backoffMs = Math.min(backoffMs * 2, maxBackoffMs);
        }
    }

    private void runConnection() throws Exception {
        CompletableFuture<JsonNode> welcome = new CompletableFuture<>();
        CompletableFuture<Void> lost = new CompletableFuture<>();
        this.lostFuture = lost;
        StringBuilder partial = new StringBuilder();

        WebSocket w = http.newWebSocketBuilder().buildAsync(URI.create(url), new WebSocket.Listener() {
            @Override
            public void onOpen(WebSocket webSocket) {
                webSocket.request(1);
            }

            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                partial.append(data);
                if (last) {
                    String frame = partial.toString();
                    partial.setLength(0);
                    // 任何完整帧到达都证明连接活着:刷新帧时间戳,在途 ping 视为已应答
                    lastFrameAt = System.currentTimeMillis();
                    pingSentAt = 0;
                    dispatch(frame, welcome);
                }
                webSocket.request(1);
                return null;
            }

            @Override
            public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                lost.complete(null);
                return null;
            }

            @Override
            public void onError(WebSocket webSocket, Throwable error) {
                lost.completeExceptionally(error);
            }
        }).get(10, TimeUnit.SECONDS);

        sendDirect(w, Frames.wireHello("worker", apiKey, workerId, hubKey,
                java.util.Map.of("hostname", hostName())));
        JsonNode welcomeFrame = welcome.get(10, TimeUnit.SECONDS);
        JsonNode verNode = welcomeFrame.path("ver");
        if (!verNode.isInt() || verNode.asInt() != Frames.PROTOCOL_VERSION) {
            w.abort();
            throw new IllegalStateException("hub 返回协议版本不兼容: " + welcomeFrame);
        }

        // hello/welcome 通过后才暴露给 sender 线程,避免并发 sendText
        this.ws = w;
        for (String channel : desiredSubs) {
            sendDirect(w, Frames.wireSub(channel));
        }
        connected = true;
        // 新连接:重置心跳状态,避免上一条连接的旧时间戳误判本连接
        lastFrameAt = System.currentTimeMillis();
        pingSentAt = 0;
        log.info("[{}] 已连接 hub,workerId={},hubKey={}", name, workerId,
                hubKey.isEmpty() ? "未配置" : "已配置");
        for (Listener l : listeners) {
            try {
                l.onHubConnected();
            } catch (RuntimeException e) {
                log.error("onHubConnected 监听器异常", e);
            }
        }

        lost.get(); // 阻塞直到连接失效
        connected = false;
        this.ws = null;
        this.lostFuture = null;
        log.info("[{}] hub 连接断开", name);
        for (Listener l : listeners) {
            try {
                l.onHubDisconnected();
            } catch (RuntimeException e) {
                log.error("onHubDisconnected 监听器异常", e);
            }
        }
    }

    private void dispatch(String frame, CompletableFuture<JsonNode> welcome) {
        JsonNode node;
        try {
            node = Json.parse(frame);
        } catch (RuntimeException e) {
            log.warn("忽略无法解析的帧: {}", frame.length() > 200 ? frame.substring(0, 200) : frame);
            return;
        }
        String type = node.path("type").asString("");
        switch (type) {
            case Frames.WELCOME -> welcome.complete(node);
            case Frames.MSG -> {
                for (Listener l : listeners) {
                    try {
                        l.onHubMessage(node);
                    } catch (RuntimeException e) {
                        log.error("onHubMessage 监听器异常", e);
                    }
                }
            }
            case Frames.ERROR -> {
                if (Frames.E_NOT_AUTHENTICATED.equals(node.path("code").asString(""))) {
                    log.warn("[{}] hub 拒绝认证(NOT_AUTHENTICATED),hubKey={}", name,
                            hubKey.isEmpty() ? "未配置" : "已配置");
                } else {
                    log.warn("[{}] hub 错误帧: {}", name, node);
                }
            }
            case Frames.PONG -> { /* 心跳应答:lastFrameAt 已在 onText 更新,无需处理 */ }
            default -> log.debug("忽略帧 type={}", type);
        }
    }

    // ---- 心跳保活(§4.2) ----

    /**
     * 心跳循环——唯一断开检测手段:本周期(最近 5s)内无任何帧到达才发 ping;
     * 判死依据是「发出 ping 后 15s 无任何帧」,而非「距上帧超时」(空闲与死亡必须区分:
     * 探测→秒答=活,探测→超时=死,不误杀合法空闲连接)。
     */
    private void heartbeatLoop() {
        while (!stopped) {
            try {
                Thread.sleep(HEARTBEAT_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (stopped || !connected) {
                pingSentAt = 0; // 未连接/已停止:重置探测状态
                continue;
            }
            long now = System.currentTimeMillis();
            if (now - lastFrameAt < HEARTBEAT_INTERVAL_MS) {
                // 本周期内有帧到达,连接活跃:不发 ping,旧 ping 视为已应答
                pingSentAt = 0;
                continue;
            }
            if (pingSentAt == 0) {
                // 首次空闲:发 ping 探测——经 send() 入 outbound 队列由 senderLoop 串行发送
                send(Frames.wirePing(now));
                pingSentAt = now;
                continue;
            }
            if (now - pingSentAt >= HEARTBEAT_DEAD_MS) {
                log.warn("[{}] 心跳判死:ping 后 {}ms 无任何帧,主动断开重连", name, now - pingSentAt);
                pingSentAt = 0;
                CompletableFuture<Void> lost = lostFuture;
                if (lost != null) {
                    lost.complete(null); // abort 不保证回调监听器,须手动唤醒 lost(同 forceReconnect)
                }
                WebSocket w = ws;
                if (w != null) {
                    w.abort(); // 触发 onClose/onError → runConnection 退出 → connectLoop 按既有退避重连
                }
            }
            // 否则:等待 ping 应答中,下个周期再看
        }
    }

    // ---- 出站发送 ----

    private void senderLoop() {
        while (!stopped) {
            String frame;
            try {
                frame = outbound.poll(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (frame == null) {
                continue;
            }
            WebSocket w = ws;
            if (w == null || !connected) {
                log.debug("[{}] 未连接,丢弃出站帧", name);
                continue;
            }
            try {
                w.sendText(frame, true).get(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("[{}] 发送失败,中止连接触发重连: {}", name, e.toString());
                w.abort();
            }
        }
    }

    private void sendDirect(WebSocket w, String frame) throws Exception {
        w.sendText(frame, true).get(10, TimeUnit.SECONDS);
    }

    private static String hostName() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
