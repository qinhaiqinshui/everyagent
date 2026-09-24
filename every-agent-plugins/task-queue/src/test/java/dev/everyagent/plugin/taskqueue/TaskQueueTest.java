package dev.everyagent.plugin.taskqueue;

import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.hub.EventSink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * TaskQueue 单测：验证 Semaphore 许可计数、等待队列跟踪与 task.queued 事件广播。
 */
class TaskQueueTest {

    private WorkerProperties props;
    private EventSink eventSink;

    @BeforeEach
    void setUp() {
        props = new WorkerProperties();
        eventSink = mock(EventSink.class);
    }

    @Test
    void acquireDecrementsAndReleaseRestoresPermits() throws InterruptedException {
        props.getLimits().setMaxConcurrentTasks(3);
        TaskQueue queue = new TaskQueue(props, eventSink);

        assertEquals(3, queue.availablePermits());

        queue.acquire("t1");
        assertEquals(2, queue.availablePermits());

        queue.acquire("t2");
        assertEquals(1, queue.availablePermits());

        queue.release("t1");
        assertEquals(2, queue.availablePermits());

        queue.release("t2");
        assertEquals(3, queue.availablePermits());
    }

    @Test
    void queueLengthAndWaitingTaskIdsTrackBlockedTasks() throws InterruptedException {
        props.getLimits().setMaxConcurrentTasks(1);
        TaskQueue queue = new TaskQueue(props, eventSink);

        queue.acquire("t1"); // occupy
        assertEquals(0, queue.queueLength());

        Thread vt = Thread.ofVirtual().start(() -> {
            try {
                queue.acquire("t2");
            } catch (InterruptedException ignored) {
            }
        });

        Thread.sleep(200);
        assertEquals(1, queue.queueLength(), "排队等待的任务应被跟踪");
        assertArrayEquals(new String[]{"t2"}, queue.waitingTaskIds());

        queue.release("t1");
        vt.join(3000);
        assertEquals(0, queue.queueLength(), "获取许可后应从等待队列移除");
    }

    @Test
    void broadcastsTaskQueuedWhenWaiting() throws InterruptedException {
        props.getLimits().setMaxConcurrentTasks(1);
        TaskQueue queue = new TaskQueue(props, eventSink);

        queue.acquire("t1"); // occupy

        Thread vt = Thread.ofVirtual().start(() -> {
            try {
                queue.acquire("t2");
            } catch (InterruptedException ignored) {
            }
        });

        Thread.sleep(200);
        verify(eventSink, atLeastOnce())
                .fanout(any(), eq("task.queued"), any(), any(), any());

        queue.release("t1");
        vt.join(3000);
    }

    @Test
    void noBroadcastWhenNoWait() throws InterruptedException {
        props.getLimits().setMaxConcurrentTasks(2);
        TaskQueue queue = new TaskQueue(props, eventSink);

        queue.acquire("t1"); // 仍有剩余许可，无需排队
        verifyNoInteractions(eventSink);

        queue.release("t1");
    }
}
