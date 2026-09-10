package dev.everyagent.worker.task;

import dev.everyagent.contract.json.Json;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.proto.Events;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * round.opened / round.closed 瞬态推送事件单测(@TempDir,无 Spring):
 * TaskEvents 两个新方法的事件形、openRoundAtStart 返回「是否真的新开一轮」、
 * persistClosedRounds 返回「本次新闭合的轮」、以及磁盘 jsonl 不落盘。
 */
class RoundEventsTest {

    @TempDir
    Path dataDir;

    private static final String MAIN = "a_main1";

    private WorkerProperties props;
    private TaskStore store;
    private RoundIndexStore rounds;
    private EventLog log;
    private TaskEvents events;

    @BeforeEach
    void setUp() {
        props = new WorkerProperties();
        props.setDataDir(dataDir.toString());
        store = new TaskStore(props);
        rounds = new RoundIndexStore();
        log = new EventLog(100_000);
        events = new TaskEvents(log, MAIN);
    }

    private Path dir() {
        return dataDir.resolve("tasks").resolve("t1");
    }

    // ---- TaskEvents 事件形:瞬态 + 主 agent(任务级)----

    @Test
    void roundOpenedEventShapeIsTransientAndMain() {
        long startSeq = events.userMessage("第一问");
        long seq = events.roundOpened(startSeq, "第一问");

        EventRecord r = findEvent(Events.ROUND_OPENED);
        assertNotNull(r);
        assertEquals(seq, r.seq());
        assertEquals(MAIN, r.agentId(), "round.opened 归主 agent(任务级)");
        assertEquals(String.valueOf(startSeq), r.payload().path("startSeq").asString(),
                "startSeq 字符串化(wire 防 JS 精度)");
        assertEquals("第一问", r.payload().path("user").asString());
        assertTransient(r, "round.opened 必须 ext.persist=false 才不落盘");
    }

    @Test
    void roundClosedEventShapeIsTransientAndMain() {
        long seq = events.roundClosed(10L, 20L, "最终答");
        EventRecord r = findEvent(Events.ROUND_CLOSED);
        assertNotNull(r);
        assertEquals(seq, r.seq());
        assertEquals(MAIN, r.agentId(), "round.closed 归主 agent(任务级)");
        assertEquals("10", r.payload().path("startSeq").asString());
        assertEquals("20", r.payload().path("endSeq").asString());
        assertEquals("最终答", r.payload().path("finalReply").asString());
        assertTransient(r, "round.closed 必须 ext.persist=false 才不落盘");
    }

    // ---- openRoundAtStart 返回值:true=真新开,false=沿用未闭合 ----

    @Test
    void openRoundAtStartReturnsTrueOnNewAndFalseOnReuse() {
        long s1 = events.userMessage("第一问");
        assertTrue(rounds.openRoundAtStart(store, "t1", s1, "第一问", null), "空/无行 → 追加新行=true");

        long s2 = events.userMessage("中间补充");
        assertFalse(rounds.openRoundAtStart(store, "t1", s2, "中间补充", null), "未闭合尾行沿用:false");

        // 闭合后再次开轮又为 true
        events.message(MAIN, "", "第一答", List.of());
        rounds.persistClosedRounds(store, log, "t1", MAIN, null, null, 0L);
        long s3 = events.userMessage("第二问");
        assertTrue(rounds.openRoundAtStart(store, "t1", s3, "第二问", null), "已闭合尾行 → 追加新行=true");
    }

    // ---- persistClosedRounds 返回本次新闭合的轮 ----

    @Test
    void persistClosedRoundsReturnsNewlyClosedRounds() {
        long startSeq = openAndEmitClosedRound("第一问", "第一答");
        List<RoundIndex.Round> closed = rounds.persistClosedRounds(store, log, "t1", MAIN, null, null, 0L);
        assertEquals(1, closed.size(), "本次确实新闭合了一轮");
        RoundIndex.Round r = closed.get(0);
        assertEquals(startSeq, r.startSeq());
        assertEquals("第一问", r.user());
        assertEquals("第一答", r.finalReply());
        assertNotNull(r.endSeq());

        // 幂等:再调用返回空(已闭合,不算新闭合)
        assertTrue(rounds.persistClosedRounds(store, log, "t1", MAIN, null, null, 0L).isEmpty(), "幂等:已闭合轮不再计入新闭合");

        // 无最终回复的未闭合轮(中间输入/中断)不产生新闭合
        long s2 = events.userMessage("被中断之问");
        rounds.openRoundAtStart(store, "t1", s2, "被中断之问", null);
        events.delta(MAIN, "半截");
        assertTrue(rounds.persistClosedRounds(store, log, "t1", MAIN, null, null, 0L).isEmpty(), "无最终回复:无新闭合轮");
    }

    // ---- 磁盘 jsonl 不含 round 事件(经 TaskStore 真落盘)----

    @Test
    void roundEventsAreNotPersistedToJsonl() throws Exception {
        store.start();
        try {
            store.track("t1", log, () -> Json.obj()
                    .put("taskId", "t1").put("status", "running")
                    .put("mainAgentId", MAIN).put("createdAt", System.currentTimeMillis()));

            long startSeq = events.userMessage("第一问");
            assertTrue(rounds.openRoundAtStart(store, "t1", startSeq, "第一问", null));
            events.roundOpened(startSeq, "第一问");
            events.delta(MAIN, "流");
            events.message(MAIN, "", "第一答", List.of());
            for (RoundIndex.Round r : rounds.persistClosedRounds(store, log, "t1", MAIN, null, null, 0L)) {
                if (r.endSeq() != null) {
                    events.roundClosed(r.startSeq(), r.endSeq(), r.finalReply());
                }
            }
            store.flush("t1");

            Path mainFile = dir().resolve(MAIN + ".jsonl");
            assertTrue(Files.isRegularFile(mainFile), "主事件文件已落盘");
            List<String> lines = Files.readAllLines(mainFile);
            // 瞬态事件不落盘:磁盘行不得出现 round.opened / round.closed 事件名
            for (String line : lines) {
                assertFalse(line.contains("\"event\":\"round.opened\""), "磁盘不得含 round.opened: " + line);
                assertFalse(line.contains("\"event\":\"round.closed\""), "磁盘不得含 round.closed: " + line);
            }
            // 持久事件仍在(至少 user.message + message)
            assertTrue(lines.stream().anyMatch(l -> l.contains("\"event\":\"user.message\"")), "user.message 正常落盘");
            assertTrue(lines.stream().anyMatch(l -> l.contains("\"event\":\"message\"")), "message 正常落盘");
            // rounds.jsonl 该行已闭合
            List<RoundIndex.Round> diskRounds = store.readRounds(dir());
            assertEquals(1, diskRounds.size());
            assertTrue(diskRounds.get(0).closed(), "rounds.jsonl 尾行已闭合");
        } finally {
            store.stop();
        }
    }

    // ---- helpers ----

    private long openAndEmitClosedRound(String question, String answer) {
        long startSeq = events.userMessage(question);
        rounds.openRoundAtStart(store, "t1", startSeq, question, null);
        events.roundOpened(startSeq, question);
        events.delta(MAIN, "答");
        events.message(MAIN, "", answer, List.of());
        return startSeq;
    }

    private EventRecord findEvent(String event) {
        for (EventRecord r : log.readLastRecords(log.size())) {
            if (event.equals(r.event())) {
                return r;
            }
        }
        return null;
    }

    private static void assertTransient(EventRecord r, String msg) {
        assertNotNull(r.ext(), msg);
        assertTrue(r.ext().isObject(), msg);
        assertFalse(r.ext().path("persist").asBoolean(true), msg);
    }
}