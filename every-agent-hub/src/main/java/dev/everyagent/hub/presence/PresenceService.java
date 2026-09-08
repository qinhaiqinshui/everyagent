package dev.everyagent.hub.presence;

import dev.everyagent.contract.frame.Channels;
import dev.everyagent.contract.json.Json;
import dev.everyagent.hub.config.HubProperties;
import dev.everyagent.hub.reg.ChannelRegistry;
import dev.everyagent.hub.reg.ConnectionRegistry;
import dev.everyagent.hub.ws.HubConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * 连接在场广播(hub 内建传输级服务,非业务):worker 会话开合 → u.K.workers 上的
 * worker.online / worker.offline。hub 唯一"主动发布"的通道。
 * 订阅快照:前端订阅 u.K.workers 时补发当前在线集合,迟到订阅者也能发现已连接的 worker。
 * 所有 presence 帧 payload 均携带 ownerFingerprint(= sha256(apiKey) 前 16 位小写),供前端在本机凭证中
 * 匹配应使用哪把 apiKey 认证,不暴露完整 ownerKey。
 * 管理目录:worker 上线/下线时向管理连接(ownerKey==sha256(hubKey) 的 frontend,即以 apiKey=hubKey
 * 连接的前端)的 u.<managerK>.workers 广播同形帧;管理连接订阅该频道时补发跨 ownerKey 的全量 worker 快照。
 * hub-key 必填(启动校验),该目录始终可用;但目录只读——动 worker 数据须另持该 worker 的 apiKey。
 * 频道名与事件名在此本地定义——contract 只留协议,presence 是 hub 自有概念。
 */
@Service
public class PresenceService {

    /** presence 频道后缀(拼在命名空间 u.&lt;K&gt;. 之后)。 */
    public static final String WORKERS_SUFFIX = "workers";
    public static final String WORKER_ONLINE = "worker.online";
    public static final String WORKER_OFFLINE = "worker.offline";

    private static final Logger log = LoggerFactory.getLogger(PresenceService.class);

    private final ChannelRegistry channels;
    private final ConnectionRegistry connections;
    private final HubProperties props;

    public PresenceService(ChannelRegistry channels, ConnectionRegistry connections, HubProperties props) {
        this.channels = channels;
        this.connections = connections;
        this.props = props;
    }

    /** presence 频道全名。 */
    public static String workersChannel(String ownerKey) {
        return Channels.ns(ownerKey) + WORKERS_SUFFIX;
    }

    public void workerOnline(String ownerKey, String workerId, JsonNode meta) {
        publish(ownerKey, WORKER_ONLINE, workerId, meta);
        broadcastToManagers(ownerKey, WORKER_ONLINE, workerId, meta);
        log.info("presence online: worker={} owner={}", workerId, ownerKey.substring(0, 8));
    }

    public void workerOffline(String ownerKey, String workerId, JsonNode meta) {
        publish(ownerKey, WORKER_OFFLINE, workerId, meta);
        broadcastToManagers(ownerKey, WORKER_OFFLINE, workerId, meta);
        log.info("presence offline: worker={} owner={}", workerId, ownerKey.substring(0, 8));
    }

    /**
     * 向刚订阅 u.K.workers 的连接补发在线快照(逐个 worker.online,与广播帧同形)。
     * 前端以 workerId 去重,订阅与快照之间上线的 worker 会收到重复 online,无害。
     */
    public void snapshot(String ownerKey, HubConnection subscriber) {
        for (HubConnection worker : connections.onlineWorkers(ownerKey)) {
            subscriber.deliver(onlineFrame(ownerKey, worker.clientId(), worker.helloMeta()));
        }
    }

    /**
     * 管理连接全量目录快照:向刚订阅 u.<managerK>.workers 的管理连接补发全部在线 worker(跨 ownerKey)。
     * 帧与普通 presence 帧同形(payload{workerId, meta, ownerFingerprint}),仅 channel 落在管理连接自己的命名空间;
     * ownerFingerprint 取各 worker 自己的 ownerKey(sha256(apiKey) 前 16 位)。
     */
    public void snapshotAll(HubConnection subscriber) {
        for (HubConnection worker : connections.onlineWorkersAll()) {
            subscriber.deliver(frameFor(workersChannel(subscriber.ownerKey()), WORKER_ONLINE,
                    worker.ownerKey(), worker.clientId(), worker.helloMeta()));
        }
    }

    /**
     * 管理目录广播:worker 上线/下线时,向管理连接(ownerKey==sha256(hubKey) 的 frontend)
     * 的 u.<managerK>.workers 频道广播同形 worker.online/offline 帧(hub-key 必填,目录始终广播)。
     */
    private void broadcastToManagers(String ownerKey, String event, String workerId, JsonNode meta) {
        String channel = workersChannel(props.getHubKeySha());
        channels.publish(channel, frameFor(channel, event, ownerKey, workerId, meta));
    }

    private void publish(String ownerKey, String event, String workerId, JsonNode meta) {
        channels.publish(workersChannel(ownerKey), frameOf(ownerKey, event, workerId, meta));
    }

    private String onlineFrame(String ownerKey, String workerId, JsonNode meta) {
        return frameOf(ownerKey, WORKER_ONLINE, workerId, meta);
    }

    private String frameOf(String ownerKey, String event, String workerId, JsonNode meta) {
        return frameFor(workersChannel(ownerKey), event, ownerKey, workerId, meta);
    }

    private String frameFor(String channel, String event, String ownerKey, String workerId, JsonNode meta) {
        var payload = Json.obj();
        payload.put("workerId", workerId);
        payload.put("ownerFingerprint", ownerKey == null || ownerKey.isEmpty() ? "" : ownerKey.substring(0, 16));
        if (meta != null && !meta.isNull()) {
            payload.set("meta", meta);
        }
        var frame = Json.obj();
        frame.put("type", "msg");
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
