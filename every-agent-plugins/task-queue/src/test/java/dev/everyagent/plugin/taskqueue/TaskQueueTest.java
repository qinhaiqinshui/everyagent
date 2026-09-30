package dev.everyagent.plugin.taskqueue;

import dev.everyagent.plugin.api.config.WorkerConfig;
import dev.everyagent.plugin.api.event.StreamEmitter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * TaskQueue 单测：验证 Semaphore 许可计数、等待队列跟踪与 task.queued 事件广播。
 */
class TaskQueueTest {

    private WorkerConfig config;
    private WorkerConfig.Limits limits;
    private StreamEmitter eventSink;

    @BeforeEach
    void setUp() {
        config = mock(WorkerConfig.class);
        limits = mock(WorkerConfig.Limits.class);
        when(config.limits()).thenReturn(limits);
        eventSink = mock(StreamEmitter.class);
    }

    @Test
    void acquireDecrementsAndReleaseRestoresPermits() throws InterruptedException {
        when(limits.maxConcurrentTasks()).thenReturn(3);
        TaskQueue queue = new TaskQueue(config, eventSink);

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
        when(limits.maxConcurrentTasks()).thenReturn(1);
        TaskQueue queue = new TaskQueue(config, eventSink);

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
        when(limits.maxConcurrentTasks()).thenReturn(1);
        TaskQueue queue = new TaskQueue(config, eventSink);

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
        when(limits.maxConcurrentTasks()).thenReturn(2);
        TaskQueue queue = new TaskQueue(config, eventSink);

        queue.acquire("t1"); // 仍有剩余许可，无需排队
        verifyNoInteractions(eventSink);

        queue.release("t1");
    }
}
