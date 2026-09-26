package dev.everyagent.plugin.taskqueue;

import dev.everyagent.plugin.api.task.TaskChain;
import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.plugin.api.task.TaskOutcome;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.hub.EventSink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * QueueAdmissionNode 单测：验证准入节点的 acquire/release 配对语义与排队阻塞行为。
 */
class QueueAdmissionNodeTest {

    private WorkerProperties props;
    private EventSink eventSink;

    @BeforeEach
    void setUp() {
        props = new WorkerProperties();
        eventSink = mock(EventSink.class);
    }

    private TaskLifecycleContext ctx(String taskId) {
        TaskLifecycleContext ctx = mock(TaskLifecycleContext.class);
        when(ctx.taskId()).thenReturn(taskId);
        return ctx;
    }

    // ── 1. 无排队时直接通过 ──────────────────────────────────
    @Test
    void noQueueing_passesThrough() throws Exception {
        props.getLimits().setMaxConcurrentTasks(2);
        TaskQueue queue = new TaskQueue(props, eventSink);
        QueueAdmissionNode node = new QueueAdmissionNode(queue);

        TaskLifecycleContext ctx = ctx("task-1");
        TaskOutcome expected = TaskOutcome.done(0, 0);
        AtomicInteger nextCalled = new AtomicInteger(0);
        TaskChain next = c -> {
            nextCalled.incrementAndGet();
            return expected;
        };

        TaskOutcome result = (TaskOutcome) node.invoke(ctx, next);

        assertSame(expected, result, "应原样返回 next 的结果");
        assertEquals(1, nextCalled.get(), "next 应被调用一次");
        assertEquals(2, queue.availablePermits(), "release 后许可应恢复");
    }

    // ── 2. 并发满时阻塞 ──────────────────────────────────────
    @Test
    void blocksWhenPermitsExhausted() throws Exception {
        props.getLimits().setMaxConcurrentTasks(1);
        TaskQueue queue = new TaskQueue(props, eventSink);
        QueueAdmissionNode node = new QueueAdmissionNode(queue);

        // 先占用唯一的许可
        queue.acquire("task-A");

        TaskLifecycleContext ctxB = ctx("task-B");
        AtomicInteger nextCalled = new AtomicInteger(0);
        TaskChain next = c -> {
            nextCalled.incrementAndGet();
            return TaskOutcome.done(0, 0);
        };

        Thread vt = Thread.ofVirtual().start(() -> {
            try {
                node.invoke(ctxB, next);
            } catch (Exception ignored) {
            }
        });

        // 等待 200ms，验证第二个任务仍被阻塞
        Thread.sleep(200);
        assertTrue(vt.isAlive(), "许可耗尽时第二个任务应被阻塞");
        assertEquals(0, nextCalled.get(), "被阻塞时 next 不应被调用");

        // 清理：释放许可让虚拟线程退出
        queue.release("task-A");
        vt.join(3000);
        assertFalse(vt.isAlive(), "释放许可后任务应完成");
        assertEquals(1, nextCalled.get(), "释放后 next 应被调用");
    }

    // ── 3. 任务完成后释放并唤醒等待者 ────────────────────────
    @Test
    void releaseWakesWaitingTask() throws Exception {
        props.getLimits().setMaxConcurrentTasks(1);
        TaskQueue queue = new TaskQueue(props, eventSink);
        QueueAdmissionNode node = new QueueAdmissionNode(queue);

        // 任务 A 占用许可
        queue.acquire("task-A");

        TaskLifecycleContext ctxB = ctx("task-B");
        AtomicBoolean nextCalled = new AtomicBoolean(false);
        TaskChain next = c -> {
            nextCalled.set(true);
            return TaskOutcome.done(0, 0);
        };

        // 任务 B 阻塞
        Thread vt = Thread.ofVirtual().start(() -> {
            try {
                node.invoke(ctxB, next);
            } catch (Exception ignored) {
            }
        });

        Thread.sleep(200);
        assertTrue(vt.isAlive(), "任务 B 应被阻塞");
        assertFalse(nextCalled.get(), "阻塞期间 next 不应被调用");

        // 释放任务 A → 任务 B 应被唤醒
        queue.release("task-A");
        vt.join(3000);
        assertFalse(vt.isAlive(), "任务 B 应已完成");
        assertTrue(nextCalled.get(), "任务 B 的 next 应被调用");
    }

    // ── 4. 下行异常时不 release ─────────────────────────────
    @Test
    void acquireThrows_doesNotRelease() throws Exception {
        // 用 mock 让 acquire 抛异常，验证 release 未被调用（未进入 try 块）
        TaskQueue mockQueue = mock(TaskQueue.class);
        doThrow(new InterruptedException("test-acquire-fail"))
                .when(mockQueue).acquire(anyString());

        QueueAdmissionNode node = new QueueAdmissionNode(mockQueue);
        TaskLifecycleContext ctx = ctx("task-1");
        TaskChain next = c -> TaskOutcome.done(0, 0);

        assertThrows(InterruptedException.class, () -> node.invoke(ctx, next));
        verify(mockQueue, never()).release(anyString());
    }

    // ── 5. 队列事件广播 ─────────────────────────────────────
    @Test
    void acquireBroadcastsQueueEvent() throws Exception {
        props.getLimits().setMaxConcurrentTasks(1);
        TaskQueue queue = new TaskQueue(props, eventSink);
        QueueAdmissionNode node = new QueueAdmissionNode(queue);

        // 占用唯一许可
        queue.acquire("task-A");

        TaskLifecycleContext ctxB = ctx("task-B");
        TaskChain next = c -> TaskOutcome.done(0, 0);

        Thread vt = Thread.ofVirtual().start(() -> {
            try {
                node.invoke(ctxB, next);
            } catch (Exception ignored) {
            }
        });

        // 等待排队任务加入队列并广播
        Thread.sleep(200);
        assertTrue(vt.isAlive(), "任务 B 应处于阻塞排队状态");

        // 验证 task.queued 事件被广播
        verify(eventSink, atLeastOnce())
                .fanout(any(), eq("task.queued"), any(), any(), any());

        // 清理
        queue.release("task-A");
        vt.join(3000);
    }
}
