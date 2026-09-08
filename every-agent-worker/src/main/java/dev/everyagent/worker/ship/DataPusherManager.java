package dev.everyagent.worker.ship;

import dev.everyagent.contract.frame.Frames;
import dev.everyagent.worker.hub.HubLink;
import dev.everyagent.worker.hub.HubPool;
import dev.everyagent.worker.proto.Events;
import dev.everyagent.worker.task.TaskManager;
import dev.everyagent.worker.task.TaskStore;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 定向推送管理器(混合模型:实时增量推送 + task.poll 拉取,架构 §5.6):
 * 监听 hub 的 stream 频道订阅通知(subscriber.join / subscriber.leave),按 sessionId 定向
 * 建/销 DataPusher(只推运行中任务的内存日志增量)。key=sessionId+"|"+taskId,重复 join
 * 幂等不重复创建;多 hub 时通知来自哪条连接就用哪条连接推送(ext.target=sessionId 定向到
 * 该 hub 上的前端会话)。连接断开时清理该连接的推送器。
 *
 * <p>兜底路径:worker 重启后内存推送器表清空,而前端页面未刷新(WS 未断、仍订阅着 stream
 * 频道)就不会再收到 subscriber.join 通知——若前端继续发 task.input,任务照跑但该前端永远
 * 收不到增量。因此在收到前端 task.input 时也按 sessionId|taskId 兜底补建推送器,与
 * subscriber.join 同源幂等(sessionId 由 hub 在转发帧的 from.sessionId 附上)。
 */
@Component
public class DataPusherManager implements HubPool.Listener, TaskManager.TaskResumeListener {

    private static final Logger log = LoggerFactory.getLogger(DataPusherManager.class);

    private final HubPool pool;
    private final TaskManager tasks;
    private final TaskStore store;
    private final Map<String, DataPusher> pushers = new ConcurrentHashMap<>();

    public DataPusherManager(HubPool pool, TaskManager tasks, TaskStore store) {
        this.pool = pool;
        this.tasks = tasks;
        this.store = store;
    }

    @PostConstruct
    void init() {
        pool.addListener(this);
        tasks.addResumeListener(this);
    }

    /**
     * 任务被再运行(冷启动续跑,新 TaskEntry 已入 tasks):唤醒该任务的全部定向推送器立即
     * 对账挂接新日志。纯轮询(1s)在极快续跑(两次轮询间完成并驱逐)时会漏推整轮——即
     * 「终态任务再运行后前端看不到新输出」的根因,这里用事件驱动补上。
     */
    @Override
    public void onTaskResumed(String taskId) {
        for (DataPusher p : pushers.values()) {
            if (taskId.equals(p.taskId())) {
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
        // 兜底建推送器:worker 重启后前端不刷新,内存推送器表为空且不会再收到 subscriber.join;
        // 前端继续发 task.input 时以「消息到达」为触发器补建,否则任务照跑但该前端永远收不到增量。
        if (Events.TASK_INPUT.equals(event)) {
            ensurePusherForTaskInput(conn, frame);
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
            // 校验 taskId 属于本 worker:内存运行中或磁盘目录存在(否则可能是别的 worker 的任务)
            if (tasks.get(taskId) == null && !Files.isDirectory(store.dirOf(taskId))) {
                log.debug("忽略非本 worker 任务的订阅通知 channel={}", channel);
                return;
            }
            pushers.computeIfAbsent(key, k -> {
                DataPusher p = new DataPusher(sessionId, taskId, conn, tasks);
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
            p.onAck(creditIndex);
        } else {
            log.debug("stream.ack 无对应推送器 session={} task={}", sessionId, taskId);
        }
    }

    /**
     * 前端在某任务页发送输入(task.input)时兜底补建推送器:worker 重启后前端未刷新、不再有
     * subscriber.join,但任务增量仍须推到该前端。sessionId 由 hub 在转发帧的 from.sessionId
     * 附上(前端 pub 原帧无 sessionId,否则无法定向)。与 subscriber.join 同源幂等去重
     * (key=sessionId|taskId);任务不在本 worker(内存/磁盘均无)则忽略。
     */
    private void ensurePusherForTaskInput(HubLink conn, JsonNode frame) {
        JsonNode from = frame.path("from");
        if (!"frontend".equals(from.path("role").asString(""))) {
            return; // 只对前端消息兜底(worker 自身不会发 task.input 到自己的输入频道)
        }
        String sessionId = from.path("sessionId").asString("");
        String taskId = frame.path("payload").path("taskId").asString("");
        if (sessionId.isEmpty() || taskId.isEmpty()) {
            return;
        }
        if (tasks.get(taskId) == null && !Files.isDirectory(store.dirOf(taskId))) {
            log.debug("忽略非本 worker 任务的任务输入通知 task={}", taskId);
            return;
        }
        String key = sessionId + "|" + taskId;
        pushers.computeIfAbsent(key, k -> {
            DataPusher p = new DataPusher(sessionId, taskId, conn, tasks);
            p.start();
            log.debug("定向推送器已建立(任务输入兜底:worker 重启后前端未刷新) session={} task={}",
                    sessionId, taskId);
            return p;
        });
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
