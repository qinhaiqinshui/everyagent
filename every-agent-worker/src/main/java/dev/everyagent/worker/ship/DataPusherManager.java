package dev.everyagent.worker.ship;

import dev.everyagent.contract.frame.Frames;
import dev.everyagent.worker.hub.HubLink;
import dev.everyagent.worker.hub.HubPool;
import dev.everyagent.plugin.api.event.EventLogReader;
import dev.everyagent.plugin.api.event.Events;
import dev.everyagent.plugin.api.event.StreamSourceListener;
import dev.everyagent.worker.task.TaskStore;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 定向推送管理器(混合模型:实时增量推送 + task.poll 拉取,架构 §5.6):
 * 监听 hub 的 stream 频道订阅通知(subscriber.join / subscriber.leave),按 sessionId 定向
 * 建/销 DataPusher(只推运行中任务的内存日志增量)。key=sessionId+"|"+taskId,重复 join
 * 幂等不重复创建;多 hub 时通知来自哪条连接就用哪条连接推送(ext.target=sessionId 定向到
 * 该 hub 上的前端会话)。连接断开时清理该连接的推送器。
 *
 * <p>流源通知:实现 {@link StreamSourceListener},经 {@link StreamSourceRegistry}
 * 感知流源挂接/摘除,不再反向依赖 {@code TaskManager}。
 */
@Component
public class DataPusherManager implements HubPool.Listener, StreamSourceListener {

    private static final Logger log = LoggerFactory.getLogger(DataPusherManager.class);

    private final HubPool pool;
    private final TaskStore store;
    private final StreamSourceRegistry streamSources;
    private final Map<String, DataPusher> pushers = new ConcurrentHashMap<>();

    public DataPusherManager(HubPool pool, TaskStore store,
                             StreamSourceRegistry streamSources) {
        this.pool = pool;
        this.store = store;
        this.streamSources = streamSources;
    }

    @PostConstruct
    void init() {
        pool.addListener(this);
        streamSources.addListener(this);
    }

    /**
     * 流源挂接（新任务 track 或再运行）：唤醒该任务的全部定向推送器立即对账挂接新日志。
     */
    @Override
    public void onAttach(String streamKey, EventLogReader log) {
        for (DataPusher p : pushers.values()) {
            if (streamKey.equals(p.taskId())) {
                p.wake();
            }
        }
    }

    /**
     * 流源摘除（终态 untrack）：唤醒该任务的推送器排水并 detach。
     */
    @Override
    public void onDetach(String streamKey) {
        for (DataPusher p : pushers.values()) {
            if (streamKey.equals(p.taskId())) {
                p.wake();
            }
        }
    }

    @Override
    public void onHubMessage(HubLink conn, JsonNode frame) {
        String channel = frame.path("channel").asString("");
        String event = frame.path("event").asString("");
        // 前端流消费进度回报(stream.ack):只路由释放背压窗口,不建推送器。
        if (Events.STREAM_ACK.equals(event)) {
            routeAck(frame);
            return;
        }
        String taskId = taskIdOf(channel);
        if (taskId == null) {
            return; // 非 stream 频道通知,与本管理器无关
        }
        String sessionId = frame.path("payload").path("sessionId").asString("");
        if (sessionId.isEmpty()) {
            return;
        }
        String key = sessionId + "|" + taskId;
        if (Frames.SUBSCRIBER_JOIN.equals(event)) {
            // 校验 taskId 属于本 worker:流源注册表有此任务或磁盘目录存在
            // (否则可能是别的 worker 的任务)
            if (streamSources.getReader(taskId) == null && !store.taskDirExists(taskId)) {
                log.debug("忽略非本 worker 任务的订阅通知 channel={}", channel);
                return;
            }
            pushers.computeIfAbsent(key, k -> {
                DataPusher p = new DataPusher(sessionId, taskId, conn, streamSources);
                p.start();
                log.debug("定向推送器已建立 session={} task={}", sessionId, taskId);
                return p;
            });
        } else if (Frames.SUBSCRIBER_LEAVE.equals(event)) {
            DataPusher p = pushers.remove(key);
            if (p != null) {
                p.stop();
                log.debug("定向推送器已销毁 session={} task={}", sessionId, taskId);
            }
        }
    }

    /**
     * 路由前端流消费进度(stream.ack):按 sessionId|taskId 找到对应推送器释放背压窗口。
     * sessionId 由 hub 在转发帧的 from.sessionId 附上;找不到推送器静默忽略(不抛、不建)。
     */
    private void routeAck(JsonNode frame) {
        String sessionId = frame.path("from").path("sessionId").asString("");
        String taskId = frame.path("payload").path("taskId").asString("");
        long creditIndex = frame.path("payload").path("creditIndex").asLong(-1);
        if (sessionId.isEmpty() || taskId.isEmpty() || creditIndex < 0) {
            return;
        }
        DataPusher p = pushers.get(sessionId + "|" + taskId);
        if (p != null) {
            p.wsEmitter().onAck(creditIndex);
        } else {
            log.debug("stream.ack 无对应推送器 session={} task={}", sessionId, taskId);
        }
    }

    /** 来源连接断开:其订阅通知(leave)不会再到达,就地清理该连接上的推送器防泄漏。 */
    @Override
    public void onHubDisconnected(HubLink conn) {
        pushers.entrySet().removeIf(e -> {
            if (e.getValue().conn() == conn) {
                e.getValue().stop();
                return true;
            }
            return false;
        });
    }

    /** 活跃推送器数(测试可观测)。 */
    public int pusherCount() {
        return pushers.size();
    }

    /** 从 stream 频道解析 taskId;非 stream 频道返回 null。 */
    private static String taskIdOf(String channel) {
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
}
