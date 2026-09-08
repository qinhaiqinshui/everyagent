package dev.everyagent.worker.task;

import dev.everyagent.contract.json.Json;
import dev.everyagent.worker.config.WorkerProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TaskStore rounds.jsonl I/O 纯单测(@TempDir,无 Spring):
 * 撕行容忍、文件缺失返回空。
 */
class TaskStoreRoundsTest {

    @TempDir
    Path dataDir;

    private static final String SUB = "sub_x9";

    private TaskStore newStore() {
        WorkerProperties props = new WorkerProperties();
        props.setDataDir(dataDir.toString());
        return new TaskStore(props);
    }

    private static RoundIndex.Round closedRound() {
        return new RoundIndex.Round("round_test", 1L, 10L, 20L, "第一问", "第一答",
                List.of(new RoundIndex.SubRange(SUB, "子任务", 12L, 18L)), 0L, null, null);
    }

    private static RoundIndex.Round openRound() {
        return new RoundIndex.Round("round_test", 2L, 30L, null, "第二问", "",
                List.of(new RoundIndex.SubRange(SUB, "未收尾子任务", 32L, null)), 0L, null, null);
    }

    @Test
    void appendReadRoundTripClosedAndOpen() throws Exception {
        TaskStore store = newStore();
        store.appendRound("t1", closedRound());
        store.appendRound("t1", openRound());
        Path dir = dataDir.resolve("tasks").resolve("t1");
        assertTrue(Files.isRegularFile(dir.resolve("rounds.jsonl")), "rounds.jsonl 与 meta 同级落盘");

        List<RoundIndex.Round> rounds = store.readRounds(dir);
        assertEquals(2, rounds.size());
        assertEquals(closedRound(), rounds.get(0), "闭合轮 round-trip");
        RoundIndex.Round open = rounds.get(1);
        assertEquals(2, open.index());
        assertEquals(30, open.startSeq());
        assertNull(open.endSeq(), "endSeq 空串 → null");
        assertEquals("第二问", open.user());
        assertEquals("", open.finalReply());
        assertEquals(1, open.subs().size());
        assertNull(open.subs().get(0).endSeq(), "subs 内未闭合 endSeq 空串 → null");

        String line1 = Files.readAllLines(dir.resolve("rounds.jsonl")).get(0);
        assertTrue(line1.contains("\"startSeq\":\"10\""), "startSeq 序列化为字符串: " + line1);
        assertTrue(line1.contains("\"endSeq\":\"20\""), line1);
        assertTrue(line1.contains("\"subs\":[{\"agentId\":\"sub_x9\",\"title\":\"子任务\","
                + "\"startSeq\":\"12\",\"endSeq\":\"18\"}]"), line1);

        assertEquals(30, store.lastRoundStartSeq(dir), "最后一行 startSeq");
    }

    @Test
    void lastRoundStartSeqTracksLastLine() throws Exception {
        TaskStore store = newStore();
        store.appendRound("t1", closedRound());
        store.appendRound("t1", new RoundIndex.Round("round_test", 2L, 30L, 40L, "u2", "r2", List.of(), 0L, null, null));
        store.appendRound("t1", new RoundIndex.Round("round_test", 3L, 50L, 60L, "u3", "r3", List.of(), 0L, null, null));
        Path dir = dataDir.resolve("tasks").resolve("t1");
        assertEquals(50, store.lastRoundStartSeq(dir));
        assertEquals(3, store.readRounds(dir).size());
    }

    @Test
    void tornTailToleratedByReadAndZeroedByLastStartSeq() throws Exception {
        TaskStore store = newStore();
        store.appendRound("t1", closedRound());
        store.appendRound("t1", openRound());
        Path dir = dataDir.resolve("tasks").resolve("t1");
        // 模拟崩溃残留:末行半行 JSON 无换行
        Files.writeString(dir.resolve("rounds.jsonl"), "{\"index\":3,\"startSeq\":\"40",
                StandardOpenOption.APPEND);
        assertEquals(2, store.readRounds(dir).size(), "末行撕行被跳过,不影响完整行");
        assertEquals(0, store.lastRoundStartSeq(dir), "最后一行解析失败返回 0");
    }

    @Test
    void missingRoundsFileReturnsEmptyAndZero() throws Exception {
        TaskStore store = newStore();
        Path dir = dataDir.resolve("tasks").resolve("t_empty");
        Files.createDirectories(dir);
        assertTrue(store.readRounds(dir).isEmpty(), "rounds.jsonl 缺失返回空列表");
        assertEquals(0, store.lastRoundStartSeq(dir));
    }

    // ---- 返工:rewriteRound 原位改写单行(未闭合行 → 闭合行,续跑改判闭合)----

    @Test
    void rewriteRoundClosesUnclosedLineInPlace() throws Exception {
        TaskStore store = newStore();
        store.appendRound("t1", closedRound());
        store.appendRound("t1", openRound());
        Path dir = dataDir.resolve("tasks").resolve("t1");
        assertEquals(2, store.readRounds(dir).size());

        // 续跑补完:startSeq 匹配的未闭合行被闭合版原位替换(index 沿用磁盘行)
        RoundIndex.Round closedSeed = new RoundIndex.Round("round_test", 2L, 30L, 44L, "第二问", "补完之答",
                List.of(new RoundIndex.SubRange(SUB, "未收尾子任务", 32L, 40L)), 0L, null, null);
        assertTrue(store.rewriteRound("t1", closedSeed), "命中目标行应返回 true");

        List<RoundIndex.Round> rounds = store.readRounds(dir);
        assertEquals(2, rounds.size(), "改写后行数不变");
        assertEquals(closedRound(), rounds.get(0), "未涉及行原样保留");
        RoundIndex.Round second = rounds.get(1);
        assertEquals(2, second.index(), "index 不变");
        assertEquals(44, second.endSeq().longValue(), "未闭合行被闭合版原位替换");
        assertEquals("补完之答", second.finalReply());
        assertEquals(40, second.subs().get(0).endSeq().longValue());
        assertEquals(30, store.lastRoundStartSeq(dir), "锚点不受改写影响");
        assertTrue(!Files.exists(dir.resolve("rounds.jsonl.tmp")), "临时文件已清理");
    }

    @Test
    void rewriteRoundWithoutMatchLeavesFileUntouched() throws Exception {
        TaskStore store = newStore();
        store.appendRound("t1", closedRound());
        Path dir = dataDir.resolve("tasks").resolve("t1");
        String before = Files.readString(dir.resolve("rounds.jsonl"));
        RoundIndex.Round stranger = new RoundIndex.Round("round_test", 9L, 999L, 1000L, "陌生轮", "答", List.of(), 0L, null, null);
        assertFalse(store.rewriteRound("t1", stranger), "无 startSeq 匹配行返回 false");
        assertEquals(before, Files.readString(dir.resolve("rounds.jsonl")), "文件未动");
        assertTrue(!Files.exists(dir.resolve("rounds.jsonl.tmp")));
    }

    @Test
    void roundTripPreservesRoundIdAndFileChanges() throws Exception {
        TaskStore store = newStore();
        ArrayNode light = Json.arr();
        light.addObject()
                .put("filePath", "/a.md").put("fileName", "a.md")
                .put("changeType", "updated").put("saveCount", 1);
        store.appendRound("t1", new RoundIndex.Round("round_abc123", 1L, 10L, 20L, "第一问",
                "第一答", List.of(), 0L, light, null));

        List<RoundIndex.Round> rounds = store.readRounds(dataDir.resolve("tasks").resolve("t1"));
        assertEquals(1, rounds.size());
        RoundIndex.Round back = rounds.get(0);
        assertEquals("round_abc123", back.roundId(), "roundId round-trip 保留");
        assertEquals(light, back.fileChanges(), "fileChanges 轻量摘要 round-trip 保留");
        assertEquals("/a.md", back.fileChanges().get(0).path("filePath").asString());
        assertTrue(back.fileChanges().get(0).path("beforeContent").isMissingNode(),
                "轻量摘要不含 before/after 全文字段");
        assertEquals(1, back.fileChanges().get(0).path("saveCount").asInt());

        // 行内序列化检查:roundId 与 fileChanges 字段显式写出
        String line = Files.readAllLines(dataDir.resolve("tasks").resolve("t1").resolve("rounds.jsonl")).get(0);
        assertTrue(line.contains("\"roundId\":\"round_abc123\""), "行内含 roundId: " + line);
        assertTrue(line.contains("\"fileChanges\":[{\"filePath\":\"/a.md\""), "行内含 fileChanges: " + line);
    }

    @Test
    void roundWithoutChangesOmitsRoundIdAndFileChanges() throws Exception {
        TaskStore store = newStore();
        // roundId 为 null(旧行/scan 阶段)且 fileChanges 为 null:两字段都不写
        store.appendRound("t1", new RoundIndex.Round(null, 1L, 10L, 20L, "问", "答", List.of(), 0L, null, null));
        String line = Files.readString(dataDir.resolve("tasks").resolve("t1").resolve("rounds.jsonl"));
        assertFalse(line.contains("\"roundId\""), "roundId=null 不写字段: " + line);
        assertFalse(line.contains("\"fileChanges\""), "fileChanges=null 不写字段: " + line);

        List<RoundIndex.Round> rounds = store.readRounds(dataDir.resolve("tasks").resolve("t1"));
        assertNull(rounds.get(0).roundId(), "缺失 roundId 读回 null");
        assertNull(rounds.get(0).fileChanges(), "缺失 fileChanges 读回 null");
    }

    @Test
    void roundWithBlankRoundIdNormalizesToNull() throws Exception {
        TaskStore store = newStore();
        store.appendRound("t1", new RoundIndex.Round("", 1L, 10L, 20L, "问", "答", List.of(), 0L, null, null));
        assertNull(store.readRounds(dataDir.resolve("tasks").resolve("t1")).get(0).roundId(),
                "roundId 空串在构造阶段归一为 null(不写字段)");
    }

    @Test
    void writeReadRoundFileChangesRoundTrip() throws Exception {
        TaskStore store = newStore();
        ObjectNode full = Json.obj();
        ArrayNode fullChanges = Json.arr();
        fullChanges.addObject()
                .put("filePath", "/a.md").put("fileName", "a.md")
                .put("changeType", "updated").put("beforeContent", "旧")
                .put("afterContent", "新").put("saveCount", 3);
        full.set("changes", fullChanges);
        store.writeRoundFileChanges("t1", "round_abc123", full);
        Path f = dataDir.resolve("tasks").resolve("t1").resolve("file-changes").resolve("round_abc123.json");
        assertTrue(Files.isRegularFile(f), "全文文件应写到 file-changes/<roundId>.json");
        assertEquals(full, store.readRoundFileChanges("t1", "round_abc123"), "读回内容一致");
        assertEquals("updated", store.readRoundFileChanges("t1", "round_abc123")
                .path("changes").get(0).path("changeType").asString());
    }

    @Test
    void readRoundFileChangesMissingOrBlankReturnsNull() throws Exception {
        TaskStore store = newStore();
        assertNull(store.readRoundFileChanges("t1", "round_nope"), "文件不存在 → null");
        assertNull(store.readRoundFileChanges("t1", null), "roundId null → null");
        assertNull(store.readRoundFileChanges("t1", ""), "roundId 空串 → null");
    }
}
