package dev.everyagent.plugin.inputqueue;

import dev.everyagent.plugin.api.permission.TaskInfo;
import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * InputQueue 纯单测:offer/poll 语义(与 LinkedBlockingQueue 兼容)、clear、
 * 按下标 removeAt/move、快照只读且独立。
 */
class InputQueueTest {

    /** 创建以 input 文本为唯一有值字段的 TaskLifecycleContext 测试替身。 */
    private static TaskLifecycleContext ctx(String input) {
        return new TaskLifecycleContext() {
            @Override public String taskId() { return "test"; }
            @Override public String title() { return ""; }
            @Override public String workspaceRoot() { return ""; }
            @Override public String workspaceId() { return "defaultworkspace"; }
            @Override public String mainAgentId() { return "main"; }
            @Override public String status() { return "running"; }
            @Override public TaskInfo taskInfo() { return null; }
            @Override public Object taskLock() { return new Object(); }
            @Override public long startedAt() { return 0; }
            @Override public void startedAt(long ms) { }
            @Override public void onUsageBroadcast(Runnable hook) { }
            @Override public void agentStatus(String agentId, String status) { }
            @Override public String input() { return input; }
            @Override public String rawContent() { return null; }
            @Override public Map<String, Object> runParams() { return Map.of(); }
            @Override public Object rpcContext() { return null; }
        };
    }

    @Test
    void offerPollFifoAndEmptyPollNull() {
        InputQueue q = new InputQueue();
        assertTrue(q.isEmpty());
        assertEquals(0, q.size());
        q.offer(ctx("a"));
        q.offer(ctx("b"));
        q.offer(ctx("c"));
        assertEquals(3, q.size());
        assertEquals(List.of("a", "b", "c"), q.snapshot(), "快照顺序 = 队首在前");
        assertEquals("a", q.poll().input(), "poll 取队首");
        assertEquals("b", q.poll().input());
        assertEquals("c", q.poll().input());
        assertNull(q.poll(), "空队列 poll 返回 null(与 LinkedBlockingQueue 一致)");
        assertTrue(q.isEmpty());
    }

    @Test
    void clearEmptiesQueue() {
        InputQueue q = new InputQueue();
        q.offer(ctx("a"));
        q.offer(ctx("b"));
        q.clear();
        assertTrue(q.isEmpty());
        assertNull(q.poll());
        assertEquals(List.of(), q.snapshot());
    }

    @Test
    void removeAtByIndex() {
        InputQueue q = new InputQueue();
        q.offer(ctx("a"));
        q.offer(ctx("b"));
        q.offer(ctx("c"));
        assertEquals("b", q.removeAt(1).input(), "按下标删除并返回被删元素");
        assertEquals(List.of("a", "c"), q.snapshot());
        assertEquals("a", q.removeAt(0).input());
        assertEquals(List.of("c"), q.snapshot());
        assertThrows(IndexOutOfBoundsException.class, () -> q.removeAt(5), "越界抛异常");
        assertThrows(IndexOutOfBoundsException.class, () -> q.removeAt(-1), "负下标抛异常");
    }

    @Test
    void moveRearranges() {
        InputQueue q = new InputQueue();
        q.offer(ctx("a"));
        q.offer(ctx("b"));
        q.offer(ctx("c"));
        q.move(2, 0); // c 移到队首
        assertEquals(List.of("c", "a", "b"), q.snapshot());
        q.move(0, 2); // c 移回队尾
        assertEquals(List.of("a", "b", "c"), q.snapshot());
        q.move(1, 2); // b 下移一位(相邻交换)
        assertEquals(List.of("a", "c", "b"), q.snapshot());
        q.move(2, 1); // b 上移一位
        assertEquals(List.of("a", "b", "c"), q.snapshot());
        assertThrows(IndexOutOfBoundsException.class, () -> q.move(0, 9), "越界抛异常");
    }

    @Test
    void snapshotIsIndependentImmutableCopy() {
        InputQueue q = new InputQueue();
        q.offer(ctx("a"));
        List<String> snap = q.snapshot();
        q.offer(ctx("b"));
        q.removeAt(0);
        assertEquals(List.of("a"), snap, "快照不受后续入队/删除影响");
        assertThrows(UnsupportedOperationException.class, () -> snap.add("x"), "快照只读");
    }
}
