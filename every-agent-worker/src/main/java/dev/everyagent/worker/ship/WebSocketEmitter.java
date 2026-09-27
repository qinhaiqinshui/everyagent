package dev.everyagent.worker.ship;

import dev.everyagent.worker.hub.HubLink;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * WebSocket 被动推送管道：只做背压控制 + 定向推送到前端 websocket。
 * 不读 EventLog、不回扫、不对账——这些职责留在 DataPusher。
 * 背压逻辑从 DataPusher 原样搬来（CREDIT_WINDOW / nextPushIndex / ackedIndex /
 * acquireCredit / onAck），帧的组装（ext 定向、operate 标记等）由调用方（DataPusher）
 * 完成后传入。
 *
 * <p>线程安全：{@code acquireCredit} / {@code onAck} / {@code stop} 经 creditLock
 * synchronized 保护；DataPusher 回扫路径与 EventEmitter 实时路径可并发调 {@link #push}。
 */
public class WebSocketEmitter {

    /**
     * 背压窗口：未确认帧上限；保证 N 个同屏满速任务总积压 N×128 &lt; hub 前端连接出口队列
     * 1000(§4.2.2 防线 2)——前端冻结时每路最多积压 128 帧即阻塞,不会塞爆队列强制断连;
     * 前端活着时 ack 滚动,窗口不触顶,128 对正常吞吐无感。
     */
    private static final int CREDIT_WINDOW = 128;
    /** 阻塞轮询步长(ms)。 */
    private static final long ACK_WAIT_STEP_MS = 250;

    private final String sessionId;
    private final String taskId;
    private final HubLink conn;

    private final Object creditLock = new Object();
    /** 已推送帧序号(每推一帧 +1;credit 语义自增,不用 seq——同轮流式帧共享 seq 无法逐帧计数)。 */
    private long nextPushIndex = 0;
    /** 前端已确认的最大 pushIndex(初始 -1)。 */
    private volatile long ackedIndex = -1;
    /** 管道存活标志(stop 后置 false,acquireCredit 不再阻塞)。 */
    private volatile boolean running = true;

    public WebSocketEmitter(String sessionId, String taskId, HubLink conn) {
        this.sessionId = sessionId;
        this.taskId = taskId;
        this.conn = conn;
    }

    /**
     * 被动回调：接收组装好的帧，背压 + conn.pub。
     * ext 的 target/operate/initial 等定向与展示字段由调用方组装，
     * 本方法只负责 credit 标记与实际推送。
     */
    public void push(String channel, String event, Long seq, long ts,
            JsonNode payload, ObjectNode ext) {
        long creditIndex = acquireCredit();
        ext.put("credit", true);
        ext.put("creditIndex", creditIndex);
        conn.pub(channel, event, seq, ts, payload, ext);
    }

    /**
     * 申请一帧推送额度:未确认窗口满({@code nextPushIndex - ackedIndex >= CREDIT_WINDOW})
     * 即持续阻塞,直到前端 ack / 管道销毁(stop);全链端统一升级,无老前端兼容负担(§4.2.2 防线 1)。
     * 返回本帧 creditIndex(该帧推送后自增)。
     */
    private long acquireCredit() {
        synchronized (creditLock) {
            while (running && nextPushIndex - ackedIndex >= CREDIT_WINDOW) {
                try {
                    creditLock.wait(ACK_WAIT_STEP_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
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
            creditLock.notifyAll();
        }
    }

    /** 幂等停止:置 running=false,唤醒可能阻塞在 acquireCredit 的推送线程。 */
    public void stop() {
        running = false;
        synchronized (creditLock) {
            creditLock.notifyAll();
        }
    }
}
