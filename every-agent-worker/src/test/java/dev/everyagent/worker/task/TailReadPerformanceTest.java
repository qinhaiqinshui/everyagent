package dev.everyagent.worker.task;

import dev.everyagent.contract.json.Json;
import dev.everyagent.worker.config.WorkerProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 高效读取路径验收(方案 §6):
 * - readSince/readBefore/readTail 只返回目标窗口、按 seq 升序(反向随机访问,不整文件重扫);
 * - 头部大小(10 万 vs 100 万行)与尾部读取耗时弱相关(性能防跑偏);
 * - 跨块长行(&gt;64KB,carry 残片拼接)、撕行按物理行计 limit、seq 空洞、多 agent 归并;
 * - roundStartSeq 轮次定位;EventLog.readAfterSeq/readLastRecords 只读语义(同 seq 组不拆批)。
 */
class TailReadPerformanceTest {

    @TempDir
    Path dataDir;

    private TaskStore store;
    private Path taskDir;

    private static final String MAIN = "main_agent";
    private static final String SUB = "sub_x9";

    @BeforeEach
    void setUp() throws Exception {
        WorkerProperties props = new WorkerProperties();
        props.setDataDir(dataDir.toString());
        store = new TaskStore(props); // 只走读路径,无需 start sink 线程
        taskDir = Files.createDirectories(dataDir.resolve("tasks").resolve("t-perf"));
    }

    private static String row(long seq, String event, String text) {
        return "{\"seq\":" + seq + ",\"ts\":0,\"event\":\"" + event
                + "\",\"agentId\":\"main_agent\",\"payload\":{\"text\":\"" + text + "\"}}";
    }

    private static String row(long seq, String event, String agentId, String text) {
        return "{\"seq\":" + seq + ",\"ts\":0,\"event\":\"" + event
                + "\",\"agentId\":\"" + agentId + "\",\"payload\":{\"text\":\"" + text + "\"}}";
    }

    private static List<Long> seqs(List<EventRecord> recs) {
        return recs.stream().map(EventRecord::seq).collect(Collectors.toList());
    }

    private static void append(Path f, String line) throws Exception {
        try (BufferedWriter w = Files.newBufferedWriter(f, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            w.write(line);
            w.write('\n');
        }
    }

    /** 头部 headRows 行(每 1000 行一个 user.message)+ 尾部 tailRows 行 message。 */
    private static Path buildFile(Path f, int headRows, int tailRows) throws Exception {
        try (BufferedWriter w = Files.newBufferedWriter(f, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (long seq = 1; seq <= headRows; seq++) {
                w.write(row(seq, seq % 1000 == 0 ? "user.message" : "delta", "head-" + seq));
                w.write('\n');
            }
            for (long seq = headRows + 1; seq <= headRows + tailRows; seq++) {
                w.write(row(seq, "message", "tail-" + seq));
                w.write('\n');
            }
        }
        return f;
    }

    // ---- readSince / readBefore / readTail:目标窗口 + 升序 ----

    @Test
    void readsOnlyTargetWindowAscending() throws Exception {
        // 10 万行头部 + 100 行尾部目标
        Path f = buildFile(taskDir.resolve(MAIN + ".jsonl"), 100_000, 100);
        int head = 100_000;

        // 增量(afterSeq 贴近尾部,反扫在 head 行命中 seq<=afterSeq 立即停,只取尾部 100 条)
        List<EventRecord> since = store.readSince(taskDir, MAIN, head, 200);
        assertEquals(range(head + 1, head + 100), seqs(since), "readSince 只含 >afterSeq 的尾部行且升序");

        // 紧贴尾部的小增量(limit 远大于未完窗口 → 完整返回)
        List<EventRecord> small = store.readSince(taskDir, MAIN, head + 95, 50);
        assertEquals(range(head + 96, head + 100), seqs(small));

        // 尾段(物理倒序取末尾 30 行 → seq 升序)
        List<EventRecord> tail = store.readTail(taskDir, MAIN, 30);
        assertEquals(range(head + 100 - 30 + 1, head + 100), seqs(tail));

        // 向前翻页(beforeSeq 之前最近的 20 条 → seq 升序)
        List<EventRecord> before = store.readBefore(taskDir, MAIN, head + 91, 20);
        assertEquals(range(head + 71, head + 90), seqs(before));
    }

    // ---- 撕行按物理行计 limit(不无限读) ----

    @Test
    void tornTailCountsAsPhysicalLineInTailLimit() throws Exception {
        Path f = buildFile(taskDir.resolve(MAIN + ".jsonl"), 10_000, 100);
        int head = 10_000;
        append(f, "{\"seq\":999999,\"event\":\"message\",\"payload\":{\"text\":\"torn"); // 无换行撕行

        // 末尾 5 个物理行 = [撕行, 后 4 行] → 有效 4 条,撕行计 limit 但不产出
        List<EventRecord> tail = store.readTail(taskDir, MAIN, 5);
        assertEquals(range(head + 97, head + 100), seqs(tail), "撕行计物理 limit 且静默跳过");
    }

    // ---- 跨块长行(carry 残片 >64KB)→ ReverseLineReader 正确拼接 ----

    @Test
    void carriesLongLineAcrossBlocks() throws Exception {
        String longText = "A".repeat(200_000); // 超 3 个 64KB 块
        String longRow = "{\"seq\":2,\"ts\":0,\"event\":\"long\",\"agentId\":\"main_agent\","
                + "\"payload\":{\"text\":\"" + longText + "\"}}";
        Path f = taskDir.resolve(MAIN + ".jsonl");
        append(f, row(1, "a", "head"));
        append(f, longRow);
        append(f, row(3, "c", "tail"));

        List<EventRecord> all = store.readTail(taskDir, MAIN, 3);
        assertEquals(List.of(1L, 2L, 3L), seqs(all), "readTail 跨块长行应完整返回 3 行");
        EventRecord longRec = all.get(1);
        assertEquals("long", longRec.event());
        assertEquals(200_000, longRec.payload().path("text").asString().length(),
                "跨块长行 payload 往返不丢字节");
    }

    // ---- seq 空洞 + 多 agent 文件按 seq 归并 ----

    @Test
    void mergesSubAgentFilesAcrossSeqHoles() throws Exception {
        // main:1,3,5,7,9;sub:2,4,6,8,10(seq 交错 + 有洞) → 按 seq 归并后 1..10
        for (long seq = 1; seq <= 10; seq += 2) {
            append(taskDir.resolve(MAIN + ".jsonl"), row(seq, "delta", "main-" + seq));
        }
        for (long seq = 2; seq <= 10; seq += 2) {
            append(taskDir.resolve(SUB + ".jsonl"), row(seq, "delta", "sub-" + seq));
        }
        assertEquals(range(1, 10), seqs(store.readSince(taskDir, MAIN, 0, 20)),
                "读洞不报错、不补洞:纯按存在行归并");
        assertEquals(range(1, 10), seqs(store.readTail(taskDir, MAIN, 20)),
                "多 agent 尾段按 seq 归并排序");
    }

    // ---- roundStartSeq:反向数第 n 个 user.message ----

    @Test
    void roundStartSeqFindsNthUserMessage() throws Exception {
        buildFile(taskDir.resolve(MAIN + ".jsonl"), 100_000, 100);
        assertEquals(100_000, store.roundStartSeq(taskDir, MAIN, 1), "最近 1 个 user.message");
        assertEquals(99_000, store.roundStartSeq(taskDir, MAIN, 2), "倒数第 2 个 user.message");
        assertTrue(store.roundStartSeq(taskDir, MAIN, 200) == 1, "不足 200 个 → 返回 1");
    }

    // ---- 性能:头部大小与尾部读取耗时弱相关(§6,宽松阈值防 CI 抖动) ----

    @Test
    void tailReadTimeWeaklyCorrelatedWithHeadSize() throws Exception {
        long t100k = timeTailReads(Files.createDirectories(taskDir.resolve("run-100k")), 100_000);
        long t1m = timeTailReads(Files.createDirectories(taskDir.resolve("run-1m")), 1_000_000);
        assertTrue(t1m < Math.max(2_000, t100k * 25),
                "1M 头 vs 100k 头:尾部读取应同量级(反向随机访问只碰尾部窗口)。t100k="
                        + t100k + "ms, t1m=" + t1m + "ms");
    }

    private long timeTailReads(Path dir, int headRows) throws Exception {
        buildFile(dir.resolve(MAIN + ".jsonl"), headRows, 100);
        long start = System.nanoTime();
        store.readSince(dir, MAIN, headRows, 200);
        store.readTail(dir, MAIN, 100);
        store.readBefore(dir, MAIN, headRows + 102, 100);
        return (System.nanoTime() - start) / 1_000_000;
    }

    // ---- EventLog 只读尾部(组不拆批 + 含瞬态) ----

    @Test
    void eventLogReadAfterSeqKeepsGroupsAndReadLastIsNonDestructive() {
        EventLog log = new EventLog(1000);
        long g1 = log.append("delta", Json.obj().put("t", "d"), MAIN, null).seq();
        log.append(g1, "thinking", Json.obj().put("t", "th"), MAIN, null); // 同轮同 seq
        log.append(g1, "message", Json.obj().put("t", "m"), MAIN, null);   // 同轮同 seq
        long g2 = log.append("delta", Json.obj().put("t", "d2"), SUB, null).seq();
        long g3 = log.append("message", Json.obj().put("t", "m3"), MAIN, null).seq();

        // afterSeq 落在批尾 → 同 seq 组(3 帧)整组吞下,不拆批
        List<EventRecord> first = log.readAfterSeq(g1 - 1, 2);
        assertEquals(3, first.size(), "批尾同 seq 组整组吞下,不拆批");
        for (EventRecord r : first) {
            assertEquals(g1, r.seq());
        }
        List<EventRecord> rest = log.readAfterSeq(g1, 10);
        assertEquals(range(g2, g3), seqs(rest), "严格 >afterSeq");

        List<EventRecord> last = log.readLastRecords(2);
        assertEquals(2, last.size());
        // readLastRecords 返回追加顺序(旧→新):末尾 2 条 = [g2, g3]
        assertEquals(g2, last.get(0).seq());
        assertEquals(g3, last.get(1).seq());

        // 只读:多次读取结果一致,缓冲未消费、未删除
        assertEquals(last.size(), log.readLastRecords(2).size());
        assertEquals(first.size() + rest.size(), log.size(), "读取不消费、不删除记录");
    }

    private static List<Long> range(long fromInclusive, long toInclusive) {
        List<Long> out = new ArrayList<>();
        for (long s = fromInclusive; s <= toInclusive; s++) {
            out.add(s);
        }
        return out;
    }
}