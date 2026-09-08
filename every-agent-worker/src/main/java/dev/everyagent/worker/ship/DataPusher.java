package dev.everyagent.worker.ship;

import dev.everyagent.contract.json.Json;
import dev.everyagent.worker.hub.HubLink;
import dev.everyagent.worker.proto.Channels;
import dev.everyagent.worker.proto.Events;
import dev.everyagent.worker.task.EventLog;
import dev.everyagent.worker.task.EventRecord;
import dev.everyagent.worker.task.TaskEntry;
import dev.everyagent.worker.task.TaskManager;
import dev.everyagent.worker.task.TaskEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 按需定向推送器(混合模型:实时增量推送 + task.poll 拉取,架构 §5.6):
 * 前端 sub 到 u.&lt;K&gt;.task.&lt;taskId&gt;.stream 频道时(hub 发 subscriber.join 通知),
 * DataPusherManager 为该 sessionId 建一个推送器,只推「运行中任务」的内存日志增量
 * (含瞬态 delta/thinking);磁盘历史/终态任务/断线补齐一律走 task.poll 拉取路径。
 *
 * <p>推送循环 = 事件驱动 + 1s 兜底对账:每拍按 taskId 重查内存 TaskEntry——任务在运行就
 * 挂其 EventLog 监听(onAppend 唤醒)并按位置游标补推增量;任务未创建/已驱逐(终态后
 * finish 销毁)则空转等待,不推任何东西。对账的原因:终态任务会在会话保持期间被再运行
 * (冷启动续跑),再运行 = 同 taskId 全新 TaskEntry/EventLog,若只挂首个日志,新一轮事件
 * 只落盘、不推前端;换挂新日志时游标归零从头推,前端按 seq 去重,重复无害。
 *
 * <p>定向与降级:用来源连接 pub,ext.target=sessionId 让 hub 只投递给该前端会话(天然定向
 * 到该 hub);pub 非阻塞,出站队列满即丢帧(事件日志是事实源,前端 3s 兜底轮询/重连 resync
 * 自愈)。前端 unsub/断线(leave)时销毁。
 */
public class DataPusher implements EventLog.Listener {

    private static final Logger log = LoggerFactory.getLogger(DataPusher.class);
    /** 单批推送记录数(与 task.poll 批上限同量级)。 */
    private static final int BATCH = 500;
    /**
     * 首挂回扫上限:订阅时已在内存日志里的存量最多回扫这么多条(标 initial=true 回放语义)。
     * 防长任务全量回扫冲爆出站队列(1 万,满则丢最新帧)与 hub 前端连接 sink(1000,溢出断连);
     * 2000 条必与前端首拉尾段(先 sub 后拉)重叠,重叠由前端 seq 去重吸收,不重不漏。
     */
    private static final int FIRST_SWEEP_MAX = 2000;
    private static final String EXT_TARGET = "target";
    private static final String EXT_OPERATE = "operate";
    private static final String EXT_INITIAL = "initial";
    private static final String OPERATE_REPLACE = "replace";
    private static final String OPERATE_APPEND = "append";
    /** 背压窗口:未确认帧上限(小于 hub outbound-queue-limit=1000,留余量)。 */
    private static final int CREDIT_WINDOW = 512;
    /** ack 超时(无前端回报)→ 降级为无背压继续推,不卡死不推。 */
    private static final long ACK_TIMEOUT_MS = 5000;
    /** 阻塞轮询步长(ms)。 */
    private static final long ACK_WAIT_STEP_MS = 250;

    private final String sessionId;
    private final String taskId;
    private final HubLink conn;
    private final TaskManager tasks;

    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Semaphore wake = new Semaphore(0);
    private final Object creditLock = new Object();
    /** 已推送帧序号(每推一帧 +1;credit 语义自增,不用 seq——同轮流式帧共享 seq 无法逐帧计数)。 */
    private long nextPushIndex = 0;
    /** 前端已确认的最大 pushIndex(初始 -1)。 */
    private volatile long ackedIndex = -1;
    /** 最近一次收到 ack 的时间戳(超时降级判断)。 */
    private volatile long lastAckAt = System.currentTimeMillis();
    private volatile Thread thread;
    /** 内存日志已消费的记录位置游标(readFrom 位置口径:共享 seq 轮组内 seq 无法区分组内帧)。 */
    private volatile int cursor;
    /** 首挂回扫边界:位置 &lt; replayEnd 的帧标 initial=true(前端 ask 卡片静默);换挂(再运行)归零。 */
    private volatile int replayEnd;
    /** 当前挂接监听的日志(停止时摘除;再运行换挂新 TaskEntry 的日志时先摘旧)。 */
    private volatile EventLog liveLog;
    /** 挂接时记主 agentId(payload 组装用,wireEvent 同 task.poll 口径)。 */
    private volatile String mainAgentId;

    public DataPusher(String sessionId, String taskId, HubLink conn, TaskManager tasks) {
        this.sessionId = sessionId;
        this.taskId = taskId;
        this.conn = conn;
        this.tasks = tasks;
    }

    public void start() {
        thread = Thread.ofVirtual().name("pusher-" + taskId + "-" + sessionId).start(this::run);
    }

    /** 来源连接(供 DataPusherManager 断线清理识别;多 hub 定向到该 hub 的前端会话)。 */
    HubLink conn() {
        return conn;
    }

    /** 推送器所属任务(DataPusherManager 按任务唤醒)。 */
    String taskId() {
        return taskId;
    }

    /** 立即唤醒对账循环(任务被再运行时由 DataPusherManager 调用,不等 1s 轮询)。 */
    void wake() {
        wake.release();
    }

    /** 幂等停止:摘监听、唤醒循环、打断线程。 */
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        detach();
        wake.release();
        synchronized (creditLock) {
            creditLock.notifyAll(); // 唤醒可能阻塞在 acquireCredit 的推送线程
        }
        Thread th = thread;
        if (th != null) {
            th.interrupt();
        }
    }

    private void run() {
        try {
            runLive();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("定向推送异常 task={} session={}: {}", taskId, sessionId, e.toString());
        } finally {
            detach();
            synchronized (creditLock) {
                creditLock.notifyAll(); // 收口/异常退出时释放阻塞线程
            }
        }
    }

    /**
     * 推送主循环(单虚拟线程):每拍对账 tasks 表,确保挂在「当前」TaskEntry 的 EventLog 上
     * 推送增量,并等 onAppend 唤醒(带 1s 兜底轮询,保证无人唤醒也最终对账)。
     */
    private void runLive() throws InterruptedException {
        while (running.get()) {
            reconcileLive();
            wake.tryAcquire(1, TimeUnit.SECONDS);
        }
    }

    /**
     * 对账一次:任务在内存且非终态 → 确保挂在其日志上并推送增量;任务已终态但日志还挂着 →
     * 收尾排水一次(finish 先置终态、agent.status 等尾事件后追加,不排会丢尾);任务不在内存
     * (终态已驱逐/尚未创建)→ 空转等待(历史与终态数据走 task.poll 拉取,本推送器不负责)。
     */
    private void reconcileLive() {
        TaskEntry t = tasks.get(taskId);
        if (t == null) {
            return;
        }
        if (t.status.terminal()) {
            if (liveLog == t.log) {
                cursor = pushFrom(t.log, cursor);
            }
            return;
        }
        if (liveLog != t.log) {
            attachLog(t);
        }
        cursor = pushFrom(t.log, cursor);
    }

    /**
     * 挂接到指定 TaskEntry 的日志:首次挂接回扫尾部存量(上限 {@link #FIRST_SWEEP_MAX},
     * 标 initial 回放);换挂(再运行)从头推——新日志本推送器从未消费过,已落盘部分前端
     * 按 seq 去重,重复推送无害,不从头推则会漏掉本推送器未推送的记录。
     */
    private void attachLog(TaskEntry t) {
        EventLog old = liveLog;
        if (old != null) {
            old.removeListener(this);
            log.debug("换挂任务日志 task={} (再运行续跑)", taskId);
        }
        EventLog elog = t.log;
        liveLog = elog;
        mainAgentId = t.mainAgentId;
        elog.addListener(this);
        if (old == null) {
            // 首挂:回扫订阅时已在内存的尾部存量(与前端首拉重叠,seq 去重吸收)
            int size = elog.size();
            replayEnd = size;
            cursor = Math.max(0, size - FIRST_SWEEP_MAX);
        } else {
            replayEnd = 0;
            cursor = 0;
        }
    }

    /** 摘除当前日志监听(run 收口/stop/换挂共用)。 */
    private void detach() {
        EventLog l = liveLog;
        if (l != null) {
            l.removeListener(this);
        }
        liveLog = null;
    }

    /** 从记录位置 from 起把日志推一段,返回新的已消费位置。 */
    private int pushFrom(EventLog log, int from) {
        int pos = from;
        while (running.get()) {
            List<EventRecord> batch = log.readFrom(pos, BATCH);
            if (batch.isEmpty()) {
                break;
            }
            for (EventRecord r : batch) {
                push(r, pos < replayEnd);
                pos++;
            }
            if (batch.size() < BATCH) {
                break;
            }
        }
        return pos;
    }

    /**
     * 推送一条内存记录:ext = target(定向) + operate(delta/thinking=append,其余=replace)
     * + 回放帧 initial=true(前端 askStore 静默依赖)+ 原事件 ext 字段合并(如 persist=false);
     * payload 走 {@link TaskEvents#wireEvent} 同 task.poll wire 口径(子 agent 事件把
     * agentId 注入 payload,前端折叠器据以分流主/子线程)。
     */
    private void push(EventRecord r, boolean replay) {
        String operate = Events.DELTA.equals(r.event()) || Events.THINKING.equals(r.event())
                ? OPERATE_APPEND : OPERATE_REPLACE;
        ObjectNode ext = Json.obj();
        ext.put(EXT_TARGET, sessionId);
        ext.put(EXT_OPERATE, operate);
        if (replay) {
            ext.put(EXT_INITIAL, true);
        }
        if (r.ext() != null && r.ext().isObject()) {
            ObjectNode src = (ObjectNode) r.ext();
            for (String k : src.propertyNames()) {
                ext.set(k, src.get(k));
            }
        }
        JsonNode payload = TaskEvents.wireEvent(r, mainAgentId).path("payload");
        long creditIndex = acquireCredit();
        ext.put("credit", true);
        ext.put("creditIndex", creditIndex);
        conn.pub(Channels.taskStream(conn.k(), taskId), r.event(), r.seq(), r.ts(), payload, ext);
    }

    /**
     * 申请一帧推送额度:未确认窗口满({@code nextPushIndex - ackedIndex >= CREDIT_WINDOW})
     * 且未超时则阻塞等待前端 ack;超过 {@link #ACK_TIMEOUT_MS} 降级为无背压继续推。
     * 返回本帧 creditIndex(该帧推送后自增)。
     */
    private long acquireCredit() {
        synchronized (creditLock) {
            long now = System.currentTimeMillis();
            while (running.get()
                    && nextPushIndex - ackedIndex >= CREDIT_WINDOW
                    && now - lastAckAt < ACK_TIMEOUT_MS) {
                try {
                    creditLock.wait(ACK_WAIT_STEP_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                now = System.currentTimeMillis();
            }
            return nextPushIndex++;
        }
    }

    /** 前端回报消费进度:推进已确认游标并唤醒等待中的推送线程。 */
    public void onAck(long creditIndex) {
        synchronized (creditLock) {
            if (creditIndex > ackedIndex) {
                ackedIndex = creditIndex;
            }
            lastAckAt = System.currentTimeMillis();
            creditLock.notifyAll();
        }
    }

    @Override
    public void onAppend() {
        wake.release();
    }
}
