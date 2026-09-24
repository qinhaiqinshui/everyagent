package dev.everyagent.plugin.taskqueue;

import dev.everyagent.contract.json.Json;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.hub.EventSink;
import dev.everyagent.worker.proto.Channels;
import dev.everyagent.worker.proto.Events;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 任务队列核心：Semaphore 控制实际并发运行数，ConcurrentLinkedQueue 跟踪排队任务。
 * <p>acquire 阻塞 = 虚拟线程 park（零线程开销）；release 唤醒下一个等待者。
 * 队列状态变化时广播 task.queued 事件到 tasks 频道（前端据此渲染排队状态）。
 */
@Component
public class TaskQueue {

    private static final Logger log = LoggerFactory.getLogger(TaskQueue.class);

    private final Semaphore semaphore;
    private final EventSink eventSink;
    private final ConcurrentLinkedQueue<String> waitingQueue = new ConcurrentLinkedQueue<>();
    /** 用于生成排队序号（1-based）。 */
    private final AtomicInteger positionCounter = new AtomicInteger(0);

    public TaskQueue(WorkerProperties props, EventSink eventSink) {
        this.semaphore = new Semaphore(props.getLimits().getMaxConcurrentTasks(), true); // 公平模式：FIFO
        this.eventSink = eventSink;
    }

    /**
     * 获取运行许可（下行段调用）。若并发满则阻塞等待（虚拟线程 park）。
     * @param taskId 任务 ID
     * @throws InterruptedException 被中断时抛出（洋葱执行器译为 CANCELLED）
     */
    public void acquire(String taskId) throws InterruptedException {
        if (semaphore.availablePermits() <= 0) {
            // 加入等待队列并广播
            waitingQueue.add(taskId);
            broadcastQueueState();
            log.debug("任务排队等待 taskId={} queueLength={}", taskId, waitingQueue.size());
        }
        semaphore.acquire();
        // 获取到许可后从等待队列移除
        waitingQueue.remove(taskId);
        if (!waitingQueue.isEmpty()) {
            broadcastQueueState();
        }
    }

    /**
     * 释放运行许可（上行段 finally 调用）。
     * @param taskId 任务 ID
     */
    public void release(String taskId) {
        semaphore.release();
        log.debug("任务释放许可 taskId={} availablePermits={}", taskId, semaphore.availablePermits());
    }

    /**
     * 当前排队中的任务列表快照。
     * @return 排队 taskId 数组
     */
    public String[] waitingTaskIds() {
        return waitingQueue.toArray(String[]::new);
    }

    /**
     * 队列长度。
     */
    public int queueLength() {
        return waitingQueue.size();
    }

    /**
     * 可用许可数。
     */
    public int availablePermits() {
        return semaphore.availablePermits();
    }

    /**
     * 广播队列状态到 tasks 频道。
     */
    private void broadcastQueueState() {
        try {
            ObjectNode payload = Json.obj();
            payload.put("queueLength", waitingQueue.size());
            ArrayNode arr = Json.arr();
            for (String tid : waitingQueue) {
                arr.add(tid);
            }
            payload.set("queue", arr);
            eventSink.fanout(k -> Channels.tasks(k), Events.TASK_QUEUED, null, payload, null);
        } catch (Exception e) {
            log.warn("广播队列状态失败", e);
        }
    }
}
