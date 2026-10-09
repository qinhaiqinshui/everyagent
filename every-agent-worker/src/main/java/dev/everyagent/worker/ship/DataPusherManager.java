package dev.everyagent.worker.ship;

import dev.everyagent.contract.frame.Frames;
import dev.everyagent.contract.frame.StreamChannelParser;
import dev.everyagent.worker.hub.HubLink;
import dev.everyagent.worker.hub.HubPool;
import dev.everyagent.plugin.api.event.EventLogReader;
import dev.everyagent.plugin.api.event.Events;
import dev.everyagent.plugin.api.event.StreamSourceListener;
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
 *
 * <p>归属自检:stream 频道名带 worker 段(§5.2),通知里点名的是别的 worker 时直接丢弃,
 * 不为非自身任务建推送器——否则该推送器收不到前端 ack,背压窗口永不释放而永久阻塞(§7.13)。
 */
@Component
public class DataPusherManager implements HubPool.Listener, StreamSourceListener {

    private static final Logger log = LoggerFactory.getLogger(DataPusherManager.class);

    private final HubPool pool;
    private final StreamSourceRegistry streamSources;
    /** 出网单点投影器:建 DataPusher 时传入(事件 wire + 出网过滤链的唯一收敛点)。 */
    private final EgressProjector projector;
    /** 归属查询口(task 层实现);ObjectProvider 延迟解析,避免与 TaskManager 构造循环依赖。 */
    private final org.springframework.beans.factory.ObjectProvider<TaskOwnership> ownership;
    private final Map<String, DataPusher> pushers = new ConcurrentHashMap<>();

    public DataPusherManager(HubPool pool,
                             StreamSourceRegistry streamSources,
                             EgressProjector projector,
                             org.springframework.beans.factory.ObjectProvider<TaskOwnership> ownership) {
        this.pool = pool;
        this.streamSources = streamSources;
        this.projector = projector;
        this.ownership = ownership;
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
        // 频道解析统一委托 contract 的 StreamChannelParser(与 hub 同一份频道命名知识,不分叉)。
        StreamChannelParser.StreamRef ref = StreamChannelParser.parse(channel);
        if (ref == null) {
            return; // 非任务流频道通知,与本管理器无关
        }
        // 归属自检:频道名带 worker 段且不是本机 worker → 一律丢弃。
        // 同 apiKey 两台 worker 时,hub 若仍把 subscriber.join 广播给全部在线 worker,
        // 非寻址那台一旦建起推送器就永远收不到前端 ack(ack 只发到寻址那台的 input 频道),
        // credit 窗口 128 永不释放 → beginTurn 永久阻塞,白占出站队列与虚拟线程(§7.13)。
        String addressee = ref.workerId();
        if (addressee != null && !addressee.equals(pool.workerId())) {
            log.debug("subscriber 通知归属非本机 worker,丢弃:worker={} task={} event={}",
                    addressee, ref.taskId(), event);
            return;
        }
        String taskId = ref.taskId();
        String sessionId = frame.path("payload").path("sessionId").asString("");
        if (sessionId.isEmpty()) {
            return;
        }
        String key = sessionId + "|" + taskId;
        if (Frames.SUBSCRIBER_JOIN.equals(event)) {
            // 归属校验(第二道,按任务):任务不属于本 worker(内存/磁盘索引/任务目录均无)时
            // 不建推送器 —— 否则一次指向不存在任务的订阅就永久占住一个虚拟线程推送器,
            // 且它的背压窗口等不到 ack(前端不会再为这个任务发帧)。
            // leave 分支不过滤:移除不存在的键本就是幂等空操作。
            TaskOwnership index = ownership.getIfAvailable();
            if (index != null && !index.ownsTask(taskId)) {
                log.debug("subscriber.join 的任务不属于本 worker,忽略:task={} session={}", taskId, sessionId);
                return;
            }
            pushers.computeIfAbsent(key, k -> {
                DataPusher p = new DataPusher(sessionId, taskId, channel, conn, streamSources, projector);
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
}
