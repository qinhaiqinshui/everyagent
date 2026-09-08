package dev.everyagent.worker.task;

import dev.everyagent.contract.json.Json;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EventLog seq 新语义(雪花 ID):seq 不再连续自增、有空洞;seed 只设水位;同轮共享 seq 的
 * 多帧允许同 seq 追加且不被抬升;readRange 不拆同 seq 轮组。
 */
class EventLogTest {

    @Test
    void seedSetsBaselineForAppend() {
        EventLog log = new EventLog(100);
        log.seed(41); // 磁盘 lastSeq=41
        assertEquals(41, log.lastSeq());
        long s1 = log.append("user.message", Json.obj(), "a1", null).seq();
        long s2 = log.append("message", Json.obj(), "a1", null).seq();
        assertTrue(s1 > 41, "新事件续号(雪花 ID 大于 seed 水位)");
        assertTrue(s2 > s1, "进程内严格递增");
        assertEquals(s2, log.lastSeq());
    }

    @Test
    void seedOnlyMovesForward() {
        EventLog log = new EventLog(100);
        long s1 = log.append("user.message", Json.obj(), "a1", null).seq();
        log.seed(0); // 磁盘空/落后:不回退
        assertEquals(s1, log.lastSeq());
        long s2 = log.append("message", Json.obj(), "a1", null).seq();
        assertTrue(s2 > s1);
        assertEquals(s2, log.lastSeq());
    }

    @Test
    void readRangeAfterSeedSeesOnlyNewRecords() {
        EventLog log = new EventLog(100);
        log.seed(9);
        long s = log.append("user.message", Json.obj(), "a1", null).seq();
        assertEquals(1, log.readRange(0, 100).size(), "seed 不产生记录,只设 seq 基线");
        assertEquals(s, log.readRange(0, 100).get(0).seq());
        assertEquals(0, log.readRange(s, 100).size(), "afterSeq=已见 seq → 无新记录");
    }

    @Test
    void sameRoundSeqFramesNotBumpedAndNotSplit() {
        EventLog log = new EventLog(1000);
        long r = log.append("delta", Json.obj(), "a1", null).seq(); // 假设轮 seq(雪花)
        // 同轮后续帧同 seq:不得被抬升(否则破坏共享不变量)
        assertEquals(r, log.append(r, "thinking", Json.obj(), "a1", null).seq());
        assertEquals(r, log.append(r, "message", Json.obj(), "a1", null).seq());
        assertEquals(r, log.lastSeq(), "水位停在轮 seq(显式追加不抬升)");
        long t = log.append("usage", Json.obj(), "a1", null).seq();
        assertTrue(t > r, "独立事件续号大于轮 seq");
        // readRange 不拆同 seq 轮组:即便 max=1,轮组整体返回
        List<EventRecord> range = log.readRange(0, 1);
        assertEquals(3, range.size(), "批量边界切在轮组中间时整组吞下");
        assertEquals(r, range.get(0).seq());
        assertEquals(r, range.get(2).seq());
        assertEquals(1, log.readRange(r, 100).size(), "轮组消费完后仅剩 usage");
    }

    @Test
    void readFromByPositionNoGroupLoss() {
        EventLog log = new EventLog(1000);
        log.append("delta", Json.obj(), "a1", null);      // pos 0,轮 seq R
        log.append("thinking", Json.obj(), "a1", null);   // pos 1,同 R
        log.append("message", Json.obj(), "a1", null);    // pos 2,同 R
        log.append("tool.result", Json.obj(), "a1", null); // pos 3
        List<EventRecord> b1 = log.readFrom(0, 2);
        assertEquals(List.of("delta", "thinking"), b1.stream().map(EventRecord::event).toList());
        List<EventRecord> b2 = log.readFrom(2, 100);
        assertEquals(List.of("message", "tool.result"), b2.stream().map(EventRecord::event).toList(),
                "按追加位置续读不丢同组尾部帧");
        assertEquals(2, log.readFrom(0, 2).size());
        assertEquals(0, log.readFrom(4, 100).size());
    }

    @Test
    void overflowStillThrows() {
        EventLog log = new EventLog(2);
        log.seed(5);
        log.append("a", Json.obj(), "a1", null);
        log.append("b", Json.obj(), "a1", null);
        assertThrows(EventLog.LogOverflowException.class,
                () -> log.append("c", Json.obj(), "a1", null));
    }
}
