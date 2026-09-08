package dev.everyagent.worker.task;

import dev.everyagent.contract.json.Json;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.proto.Events.ToolCallPart;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RoundIndexStore 开轮/闭合单测(@TempDir,无 Spring):
 * openRoundAtStart(开轮路径:空/已闭合尾行时追加未闭合轮,未闭合尾行时沿用不写)、
 * persistClosedRounds(advisor 路径:只把已落盘未闭合行改判闭合、幂等去重、续跑闭合)。
 * 事件经真实 TaskEvents 发射(同轮 delta/thinking/message 共享 seq,贴近线上形态)。
 */
class RoundIndexPersistTest {

    @TempDir
    Path dataDir;

    private static final String MAIN = "a_main1";
    private static final String SUB = "sub_x9";

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

    /** 开一轮:user.message 落盘后调用 openRoundAtStart(与 consumeInput 同路径)。 */
    private long openRound(String question) {
        long seq = events.userMessage(question);
        rounds.openRoundAtStart(store, "t1", seq, question, null);
        return seq;
    }

    /** 模拟一轮正常任务:开轮 → 工具轮(message 带 toolCalls + tool.result)→ 最终回复。 */
    private void emitNormalRound(String question, String answer) {
        openRound(question);
        events.delta(MAIN, "想");
        events.thinking(MAIN, "思考");
        events.message(MAIN, "思考全文", "调工具",
                List.of(new ToolCallPart("c1", "bash", "{}")));
        events.toolResult("c1", "bash", "ok", false, MAIN);
        events.delta(MAIN, "答");
        events.message(MAIN, "", answer, List.of());
    }

    @Test
    void openRoundAtStartAppendsAndReusesUnclosed() {
        long s1 = openRound("第一问");
        List<RoundIndex.Round> r1 = store.readRounds(dir());
        assertEquals(1, r1.size());
        RoundIndex.Round first = r1.get(0);
        assertEquals(1, first.index());
        assertEquals(s1, first.startSeq());
        assertNull(first.endSeq(), "开轮即未闭合(endSeq 空)");
        assertEquals("第一问", first.user());
        assertEquals("", first.finalReply());

        // 尾行未闭合:中间输入沿用,不写新行
        openRound("中间补充输入");
        assertEquals(1, store.readRounds(dir()).size(), "未闭合尾行沿用,不新增行");

        // 闭合第一轮后,再开新轮应追加
        events.message(MAIN, "", "第一答", List.of());
        rounds.persistClosedRounds(store, log, "t1", MAIN, null, null);
        long s3 = openRound("第三问");
        List<RoundIndex.Round> r3 = store.readRounds(dir());
        assertEquals(2, r3.size());
        assertEquals(2, r3.get(1).index());
        assertEquals(s3, r3.get(1).startSeq());
        assertNull(r3.get(1).endSeq());
    }

    @Test
    void persistClosedRoundsWritesOnlyClosedAndIsIdempotent() {
        emitNormalRound("第一问", "第一答");
        rounds.persistClosedRounds(store, log, "t1", MAIN, null, null);

        List<RoundIndex.Round> r1 = store.readRounds(dir());
        assertEquals(1, r1.size());
        RoundIndex.Round round = r1.get(0);
        assertEquals(1, round.index());
        assertTrue(round.closed());
        assertEquals("第一问", round.user());
        assertEquals("第一答", round.finalReply());
        // 区间内过程事件:工具 message(同 seq 折叠为 1 条)+ tool.result → 2

        // 再来一轮(独立 run 模拟:同一 EventLog 追加即可)
        emitNormalRound("第二问", "第二答");
        rounds.persistClosedRounds(store, log, "t1", MAIN, null, null);
        List<RoundIndex.Round> r2 = store.readRounds(dir());
        assertEquals(2, r2.size());
        assertEquals(2, r2.get(1).index());
        assertEquals("第二问", r2.get(1).user());

        // 幂等:重复补写不产生重复行
        rounds.persistClosedRounds(store, log, "t1", MAIN, null, null);
        assertEquals(2, store.readRounds(dir()).size(), "已写过的轮不重复补写");
    }

    @Test
    void persistClosedRoundsDoesNotCreateMissingOpenRow() {
        // 未走开轮路径(用户输入后未落盘行):增量闭合不做自愈/对账,保持不写
        events.userMessage("未开轮之问");
        events.delta(MAIN, "流");
        events.message(MAIN, "", "已答", List.of());
        rounds.persistClosedRounds(store, log, "t1", MAIN, null, null);
        assertTrue(store.readRounds(dir()).isEmpty(), "缺失开轮行的已闭合轮不补写");
    }

    @Test
    void persistIgnoresForeignMainId() {
        // 事件 agentId 归属另一主 id:扫描不产轮(防御)
        log.append(Events_USER_MESSAGE(), Json.obj().put("text", "别人的"), "other_main", null);
        rounds.persistClosedRounds(store, log, "t1", MAIN, null, null);
        assertTrue(store.readRounds(dir()).isEmpty());
    }

    // ---- 返工:续跑改判闭合(未闭合尾行 → 闭合行原位改写,行数不变)----

    @Test
    void persistRewritesUnclosedRowAfterResume() {
        emitNormalRound("第一问", "第一答");
        rounds.persistClosedRounds(store, log, "t1", MAIN, null, null); // 第 1 轮闭合落盘
        // 中断:新输入无最终回复 → 开轮路径即落未闭合行(endSeq="")
        long interruptedSeq = events.userMessage("被中断之问");
        rounds.openRoundAtStart(store, "t1", interruptedSeq, "被中断之问", null);
        events.delta(MAIN, "半截");
        List<RoundIndex.Round> before = store.readRounds(dir());
        assertEquals(2, before.size());
        assertNull(before.get(1).endSeq());
        long openStart = before.get(1).startSeq();
        assertEquals(openStart, store.lastRoundStartSeq(dir()));

        // 续跑:同一日志无新输入,补出工具轮 + 最终回复
        events.message(MAIN, "续跑思考", "先调工具",
                List.of(new ToolCallPart("c9", "bash", "{}")));
        events.toolResult("c9", "bash", "ok", false, MAIN);
        events.delta(MAIN, "续答");
        events.message(MAIN, "", "补完之答", List.of());
        rounds.persistClosedRounds(store, log, "t1", MAIN, null, null); // 增量路径改判闭合

        List<RoundIndex.Round> after = store.readRounds(dir());
        assertEquals(2, after.size(), "行数不变:未闭合行被原位改写,不追加新行");
        RoundIndex.Round closed = after.get(1);
        assertEquals(2, closed.index(), "index 沿用磁盘原行");
        assertEquals(openStart, closed.startSeq());
        assertEquals("被中断之问", closed.user(), "user 保持开轮输入");
        assertNotNull(closed.endSeq(), "endSeq 已补上(最终回复 seq)");
        assertTrue(closed.endSeq() > closed.startSeq());
        assertEquals("补完之答", closed.finalReply());
        assertEquals(closed.startSeq(), after.get(1).startSeq());
        // 幂等:重复增量调用不改判也不重复
        rounds.persistClosedRounds(store, log, "t1", MAIN, null, null);
        assertEquals(2, store.readRounds(dir()).size());
    }

    // ---- MeasureDurationAdvisor 耗时回填(不再发 task_duration trace,改写入 rounds.jsonl)----

    @Test
    void recordDurationFillsLastClosedRoundAndIsIdempotent() {
        emitNormalRound("第一问", "第一答");
        rounds.persistClosedRounds(store, log, "t1", MAIN, null, null);
        assertEquals(0L, store.readRounds(dir()).get(0).durationMs(), "落盘时刻尚未回填耗时");

        rounds.recordDuration(store, "t1", 1234L);
        RoundIndex.Round after = store.readRounds(dir()).get(0);
        assertEquals(1234L, after.durationMs(), "耗时回填到最后一轮");
        assertEquals(1, after.index());
        assertTrue(after.closed());

        // 第二轮:耗时只回填到最新闭合轮,不覆盖第一轮
        emitNormalRound("第二问", "第二答");
        rounds.persistClosedRounds(store, log, "t1", MAIN, null, null);
        rounds.recordDuration(store, "t1", 5678L);
        List<RoundIndex.Round> all = store.readRounds(dir());
        assertEquals(2, all.size());
        assertEquals(1234L, all.get(0).durationMs(), "第一轮耗时不被覆盖");
        assertEquals(5678L, all.get(1).durationMs(), "第二轮耗时回填到最新闭合轮");

        // 幂等:同一耗时重复调用不重复写行、不覆盖值
        rounds.recordDuration(store, "t1", 9999L);
        List<RoundIndex.Round> idem = store.readRounds(dir());
        assertEquals(2, idem.size(), "重复回填不新增行");
        assertEquals(5678L, idem.get(1).durationMs(), "已有耗时不被覆盖(幂等)");
    }

    @Test
    void recordDurationSkipsUnclosedAndZeroAndMissing() {
        // 无轮:静默跳过
        rounds.recordDuration(store, "t1", 100L);
        assertTrue(store.readRounds(dir()).isEmpty());

        // 未闭合尾轮(中断):不写耗时
        emitNormalRound("第一问", "第一答");
        rounds.persistClosedRounds(store, log, "t1", MAIN, null, null);
        long interruptedSeq = events.userMessage("被中断之问");
        rounds.openRoundAtStart(store, "t1", interruptedSeq, "被中断之问", null);
        events.delta(MAIN, "半截");
        // 最后一行是未闭合轮:recordDuration 应回填到最近一条闭合轮(第一轮),不写未闭合轮
        rounds.recordDuration(store, "t1", 250L);
        List<RoundIndex.Round> roundsOnDisk = store.readRounds(dir());
        assertEquals(2, roundsOnDisk.size());
        assertEquals(250L, roundsOnDisk.get(0).durationMs(), "未闭合行之前最近一条闭合轮被回填");
        assertEquals(0L, roundsOnDisk.get(1).durationMs(), "未闭合轮不写耗时");

        // 耗时 ≤ 0:跳过
        rounds.recordDuration(store, "t1", 0L);
        assertEquals(250L, store.readRounds(dir()).get(0).durationMs());
    }


    @Test
    void persistMergesIntermediateUserInputIntoRound() {
        emitNormalRound("第一问", "第一答");
        rounds.persistClosedRounds(store, log, "t1", MAIN, null, null);

        long secondSeq = events.userMessage("第二问");
        rounds.openRoundAtStart(store, "t1", secondSeq, "第二问", null);
        events.message(MAIN, "想", "先调工具",
                List.of(new ToolCallPart("c2", "bash", "{}")));
        events.toolResult("c2", "bash", "ok", false, MAIN);
        // 开着的轮内第二条用户输入:openRoundAtStart 沿用当前未闭合轮,不写新行
        long midSeq = events.userMessage("中间补充输入");
        rounds.openRoundAtStart(store, "t1", midSeq, "中间补充输入", null);
        events.delta(MAIN, "答");
        events.message(MAIN, "", "第二答", List.of());
        rounds.persistClosedRounds(store, log, "t1", MAIN, null, null);

        List<RoundIndex.Round> all = store.readRounds(dir());
        assertEquals(2, all.size(), "中间输入不开新轮,仍只有 2 行");
        RoundIndex.Round r2 = all.get(1);
        assertEquals("第二问", r2.user(), "user = 开轮输入");
        assertEquals("第二答", r2.finalReply());
        assertTrue(r2.closed());
    }

    // ---- 返工(文件变更迁移):roundId 稳定主键(开轮生成、沿用不重生成、改判闭合不变)----

    @Test
    void openRoundAtStartGeneratesStableRoundId() {
        long s1 = openRound("第一问");
        RoundIndex.Round first = store.readRounds(dir()).get(0);
        assertNotNull(first.roundId(), "开轮应生成稳定 roundId");
        assertFalse(first.roundId().isBlank());

        // 再次 open(尾行未闭合)→ 沿用当前轮,不重新生成(roundId 不变、行数不变)
        long s2 = events.userMessage("中间补充");
        assertFalse(rounds.openRoundAtStart(store, "t1", s2, "中间补充", null), "未闭合尾行沿用返回 false");
        List<RoundIndex.Round> afterReuse = store.readRounds(dir());
        assertEquals(1, afterReuse.size(), "沿用不追加新行");
        assertEquals(first.roundId(), afterReuse.get(0).roundId(), "沿用轮 roundId 不重新生成");

        // 闭合该轮(增量改写路径)→ roundId 沿用不变化
        events.message(MAIN, "", "第一答", List.of());
        rounds.persistClosedRounds(store, log, "t1", MAIN, null, null);
        List<RoundIndex.Round> closed = store.readRounds(dir());
        assertEquals(1, closed.size());
        assertTrue(closed.get(0).closed(), "未闭合尾行被改判为闭合");
        assertEquals(first.roundId(), closed.get(0).roundId(), "未闭合→闭合 roundId 不变");
        assertEquals(s1, closed.get(0).startSeq(), "startSeq 仍为开轮输入 seq");
    }

    // ---- 返工(文件变更迁移):persistClosedRounds 闭环(light 内联 + full 写 file-changes/<roundId>.json)----

    @Test
    void persistClosedRoundsWritesLightAndFullFileChanges() throws Exception {
        emitNormalRound("第一问", "第一答");
        String openRoundId = store.readRounds(dir()).get(0).roundId();
        assertNotNull(openRoundId, "开轮应生成 roundId");

        ArrayNode light = Json.arr();
        light.addObject()
                .put("filePath", "/a.md").put("fileName", "a.md")
                .put("changeType", "updated").put("saveCount", 1);
        ObjectNode full = Json.obj();
        ArrayNode fullChanges = Json.arr();
        fullChanges.addObject()
                .put("filePath", "/a.md").put("fileName", "a.md")
                .put("changeType", "updated").put("beforeContent", "旧")
                .put("afterContent", "新").put("saveCount", 1);
        full.set("changes", fullChanges);

        List<RoundIndex.Round> closed =
                rounds.persistClosedRounds(store, log, "t1", MAIN, light, full);
        assertEquals(1, closed.size(), "本次确实新闭合了一轮");
        // 闭合行:roundId 沿用开轮的稳定主键,fileChanges = 轻量摘要
        RoundIndex.Round onDisk = store.readRounds(dir()).get(0);
        assertTrue(onDisk.closed(), "未闭合尾行应被改判闭合");
        assertEquals(openRoundId, onDisk.roundId(), "改判闭合沿用 roundId");
        assertEquals(light, onDisk.fileChanges(), "闭合行 fileChanges 内联轻量摘要");
        // 行内确实写入了 roundId 与 fileChanges 字段(序列化无副作用)
        String line = Files.readString(dir().resolve("rounds.jsonl"));
        assertTrue(line.contains("\"roundId\":"), "rounds.jsonl 行应含 roundId: " + line);
        assertTrue(line.contains("\"fileChanges\":"), "rounds.jsonl 行应含 fileChanges: " + line);

        // 全文文件写到了 file-changes/<roundId>.json 且可读回同内容
        Path fullFile = dir().resolve("file-changes").resolve(openRoundId + ".json");
        assertTrue(Files.isRegularFile(fullFile), "全文文件应写盘: " + fullFile);
        assertEquals(full, store.readRoundFileChanges("t1", openRoundId), "读回全文与写入一致");
    }

    @Test
    void persistClosedRoundsSkipsFullFileWhenNull() throws Exception {
        emitNormalRound("第一问", "第一答");
        String openRoundId = store.readRounds(dir()).get(0).roundId();
        assertNotNull(openRoundId);

        ArrayNode light = Json.arr();
        light.addObject()
                .put("filePath", "/a.md").put("fileName", "a.md")
                .put("changeType", "created").put("saveCount", 2);
        // fileChangesFull == null:light 摘要仍内联,但不写全文文件
        rounds.persistClosedRounds(store, log, "t1", MAIN, light, null);

        RoundIndex.Round onDisk = store.readRounds(dir()).get(0);
        assertTrue(onDisk.closed());
        assertEquals(light, onDisk.fileChanges(), "light 摘要仍内联进闭合行");
        assertFalse(Files.exists(dir().resolve("file-changes").resolve(openRoundId + ".json")),
                "fileChangesFull == null 时不写全文文件");
    }

    private static String Events_USER_MESSAGE() {
        return dev.everyagent.worker.proto.Events.USER_MESSAGE;
    }
}
