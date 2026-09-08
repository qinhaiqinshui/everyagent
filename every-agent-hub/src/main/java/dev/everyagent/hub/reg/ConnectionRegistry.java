package dev.everyagent.hub.reg;

import dev.everyagent.hub.ws.HubConnection;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 已认证连接注册表 + worker 抢占索引(ownerKey:clientId → 连接)。
 */
@Component
public class ConnectionRegistry {

    private final ConcurrentHashMap<String, HubConnection> bySession = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, HubConnection> workersByOwnerAndClient = new ConcurrentHashMap<>();

    /**
     * 注册已认证连接。worker 角色做连接抢占:同 ownerKey + clientId 的旧连接先被移除并关闭
     * (presence 由 PresenceService 负责:offline → online 依次发出)。
     */
    public String register(HubConnection conn) {
        String sessionId = "s-" + UUID.randomUUID();
        if (conn.isWorker()) {
            String key = workerKey(conn.ownerKey(), conn.clientId());
            synchronized (this) {
                HubConnection old = workersByOwnerAndClient.put(key, conn);
                if (old != null && old != conn) {
                    old.preempted();
                }
            }
        }
        bySession.put(sessionId, conn);
        return sessionId;
    }

    public void deregister(HubConnection conn) {
        bySession.values().removeIf(c -> c == conn);
        if (conn.isWorker()) {
            workersByOwnerAndClient.remove(workerKey(conn.ownerKey(), conn.clientId()), conn);
        }
    }

    public HubConnection findWorker(String ownerKey, String clientId) {
        return workersByOwnerAndClient.get(workerKey(ownerKey, clientId));
    }

    /** 按 sessionId 查找已认证连接(定向投递用);不存在返回 null。 */
    public HubConnection findBySession(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        return bySession.get(sessionId);
    }

    /** 当前 owner 名下在线的 worker 连接(每个 clientId 至多一条,已被抢占的旧连接不在内)。 */
    public java.util.List<HubConnection> onlineWorkers(String ownerKey) {
        String prefix = ownerKey + ":";
        java.util.List<HubConnection> result = new java.util.ArrayList<>();
        for (var entry : workersByOwnerAndClient.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                result.add(entry.getValue());
            }
        }
        return result;
    }

    /** 全部在线 worker(跨 ownerKey),管理连接全量目录快照用。 */
    public java.util.List<HubConnection> onlineWorkersAll() {
        return new java.util.ArrayList<>(workersByOwnerAndClient.values());
    }

    public int authenticatedCount() {
        return bySession.size();
    }

    private static String workerKey(String ownerKey, String clientId) {
        return ownerKey + ":" + clientId;
    }
}
