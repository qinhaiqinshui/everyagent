package dev.everyagent.worker.task;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task.roundTail 的纯截窗逻辑:取升序事件列表末尾 limit 条,同 seq 组不拆批
 * (头部若把同 seq 组切开,则整体左扩补齐),hasMore 表示返回窗口之前还有更早事件。
 */
class TaskRoundTailWindowTest {

    private static EventRecord rec(long seq, String event) {
        return new EventRecord(seq, 0L, event, "a1", null, null);
    }

    private static List<Long> seqs(List<EventRecord> events) {
        return events.stream().map(EventRecord::seq).toList();
    }

    @Test
    void emptyListYieldsEmptyWindow() {
        TaskManager.TailWindow w = TaskManager.cutTailWindow(List.of(), 50);
        assertTrue(w.events().isEmpty());
        assertFalse(w.hasMore());
    }

    @Test
    void limitLargerThanAllReturnsAllWithoutHasMore() {
        List<EventRecord> all = List.of(rec(1, "a"), rec(2, "b"), rec(3, "c"));
        TaskManager.TailWindow w = TaskManager.cutTailWindow(all, 10);
        assertEquals(seqs(all), seqs(w.events()));
        assertFalse(w.hasMore());
    }

    @Test
    void takesLastLimitAndFlagsHasMore() {
        List<EventRecord> all = List.of(
                rec(1, "a"), rec(2, "b"), rec(3, "c"), rec(4, "d"), rec(5, "e"));
        TaskManager.TailWindow w = TaskManager.cutTailWindow(all, 3);
        assertEquals(List.of(3L, 4L, 5L), seqs(w.events()));
        assertTrue(w.hasMore());
    }

    @Test
    void sameSeqGroupAtHeadIsLeftExpandedNotSplit() {
        // 末尾 3 条原始截断落在 seq=2 组中间(b3),须把 seq=2 整组左扩进来
        List<EventRecord> all = List.of(
                rec(1, "a"),
                rec(2, "b1"), rec(2, "b2"), rec(2, "b3"),
                rec(3, "c"), rec(4, "d"));
        TaskManager.TailWindow w = TaskManager.cutTailWindow(all, 3);
        assertEquals(List.of(2L, 2L, 2L, 3L, 4L), seqs(w.events()));
        assertTrue(w.hasMore());
    }

    @Test
    void sameSeqGroupOnlyAtHeadYieldsWholeGroupWithoutEarlierHasMore() {
        // 末尾 3 条恰好是 [b2, b3, c];左扩把 seq=2 整组补齐后,前面只剩 seq=1
        List<EventRecord> all = List.of(
                rec(1, "a"),
                rec(2, "b1"), rec(2, "b2"), rec(2, "b3"),
                rec(3, "c"));
        TaskManager.TailWindow w = TaskManager.cutTailWindow(all, 3);
        assertEquals(List.of(2L, 2L, 2L, 3L), seqs(w.events()));
        assertTrue(w.hasMore());
    }

    @Test
    void headGroupLeftExpansionCanConsumeAllAndClearHasMore() {
        List<EventRecord> all = List.of(
                rec(2, "b1"), rec(2, "b2"), rec(2, "b3"), rec(3, "c"));
        TaskManager.TailWindow w = TaskManager.cutTailWindow(all, 2);
        // 原始 cut=2 -> 首条 seq=2,左扩到 0 → 全量返回,hasMore=false
        assertEquals(seqs(all), seqs(w.events()));
        assertFalse(w.hasMore());
    }
}
