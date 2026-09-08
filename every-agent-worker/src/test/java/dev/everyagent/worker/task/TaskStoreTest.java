package dev.everyagent.worker.task;

import dev.everyagent.contract.json.Json;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.proto.Events.ToolCallPart;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TaskStore 纯单测(@TempDir,无 Spring):
 * 任务统一存 data/tasks/&lt;taskId&gt;/、按 agent 分文件路由、瞬态不落盘、
 * ext null 不写行、agentId 恒非空、merge 读序、旧 events.jsonl 兼容、
 * 重启标 failed、撕行容忍、delete。
 */
class TaskStoreTest {

    @TempDir
    Path dataDir;

    private static final String MAIN = "a_main1";
    private static final String SUB = "sub_x9";

    private WorkerProperties props;
    private TaskStore store;

    @BeforeEach
    void setUp() {
        props = new WorkerProperties();
        props.setDataDir(dataDir.toString());
        store = new TaskStore(props);
        store.start();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        store.stop();
    }

    private static ObjectNode summary(String taskId, String status) {
        return Json.obj().put("taskId", taskId).put("status", status)
                .put("mainAgentId", MAIN)
                .put("createdAt", System.currentTimeMillis());
    }

    @Test
    void routesByAgentSkipsTransientAndOmitsNullExt() throws Exception {
        EventLog logA = new EventLog(1000);
        EventLog logB = new EventLog(1000);
        store.track("ta", logA, () -> summary("ta", "running"));
        store.track("tb", logB, () -> summary("tb", "running"));

        TaskEvents evA = new TaskEvents(logA, MAIN);
        long seqUser = evA.userMessage("你好"); // 落盘
        long seqDelta = evA.delta(MAIN, "d1");  // 瞬态(主);轮 seq
        long seqThinking = evA.thinking(MAIN, "思"); // 瞬态(主);同轮 seq
        long seqMsg = evA.message(MAIN, "思考全文", "正文",
                List.of(new ToolCallPart("call-1", "ask_user", "{}"))); // 落盘;同轮 seq
        long seqToolResult = evA.toolResult("call-1", "ask_user", "答案", false, MAIN); // 落盘
        long seqSubDelta = evA.delta(SUB, "sd");  // 瞬态(子,同名事件);子轮 seq
        long seqSubMsg = evA.message(SUB, "子思考", "子回答", List.of()); // 落盘(子);同子轮 seq
        long seqUsage = logA.append("usage", Json.obj(), MAIN, Json.obj().put("trace", "x")).seq(); // 落盘(ext 非 null)
        logB.append("message", Json.obj().put("text", "b"), MAIN, null); // tb 主文件
        store.flush("ta");
        store.flush("tb");
        assertEquals(seqDelta, seqThinking, "同轮 delta/thinking/message 共享同一 seq");
        assertEquals(seqDelta, seqMsg);
        assertEquals(seqSubDelta, seqSubMsg, "子 agent 同轮共享");
        assertTrue(seqUsage > seqMsg, "独立事件 seq 递增");

        Path dirA = dataDir.resolve("tasks").resolve("ta");
        Path dirB = dataDir.resolve("tasks").resolve("tb");
        assertTrue(Files.isRegularFile(dirA.resolve("meta.json")), "任务布局 data/tasks/<taskId>/");
        assertTrue(Files.isRegularFile(dirB.resolve("meta.json")), "每任务独立目录");

        // 按 agent 分文件:主文件 seq 1,4,6,9;子文件 seq 8
        List<String> mainLines = Files.readAllLines(dirA.resolve(MAIN + ".jsonl"));
        List<String> subLines = Files.readAllLines(dirA.resolve(SUB + ".jsonl"));
        assertEquals(4, mainLines.size(), "瞬态(delta/thinking 主/子同名)不落盘: " + mainLines);
        assertEquals(1, subLines.size(), "子 agent 事件路由到独立文件");
        for (String line : mainLines) {
            // 按 event 名精确判瞬态:message payload 合法携带 "thinking"/"toolCalls" 字段,不能裸 contains
            assertFalse(line.contains("\"event\":\"delta\"") || line.contains("\"event\":\"thinking\""), line);
            assertTrue(line.contains("\"agentId\":\"" + MAIN + "\""), "每行必记 agentId: " + line);
        }
        // ext:usage 行携带,其余行不写字段(null 缺省)
        assertTrue(mainLines.get(3).contains("\"ext\""), "ext 非 null 才写: " + mainLines.get(3));
        for (int i = 0; i < mainLines.size(); i++) {
            if (i != 3) {
                assertFalse(mainLines.get(i).contains("\"ext\""), "ext null 不写行: " + mainLines.get(i));
            }
        }
        assertEquals(seqUsage, store.diskLastSeq(dirA), "瞬态占 seq:磁盘 seq 有洞但 lastSeq 覆盖");

        // merge 读序:全部文件按 seq 归并;wire 形主事件无 payload.agentId、子事件有
        List<ObjectNode> events = store.readEvents(dirA, MAIN, 0, 100);
        assertEquals(List.of(seqUser, seqMsg, seqToolResult, seqSubMsg, seqUsage),
                events.stream().map(e -> e.path("seq").asLong()).toList(), "按 seq 归并跨文件");
        assertNull(events.get(0).path("payload").path("agentId").asString(null), "主 agent 事件 wire 无 agentId");
        assertEquals(SUB, events.get(3).path("payload").path("agentId").asString(), "子事件并入 payload.agentId");
        assertEquals(3, store.readEvents(dirA, MAIN, seqMsg, 100).size(), "afterSeq=message 只回其后持久事件");
        JsonNode msg = Json.parse(mainLines.get(1));
        assertEquals("思考全文", msg.path("payload").path("thinking").asString(), "message 行含完整 thinking");
        assertEquals("call-1", msg.path("payload").path("toolCalls").get(0).path("id").asString(),
                "message 行含真实 toolCall id");
        JsonNode tr = Json.parse(mainLines.get(2));
        assertEquals("ask_user", tr.path("payload").path("name").asString(), "tool.result 带 name(冷启动重建用)");

        // 单 agent 读(ConversationLoader 视角):只看主文件
        List<EventRecord> agentEvents = store.readAgentEvents(dirA, MAIN, 0, 100);
        assertEquals(List.of("user.message", "message", "tool.result", "usage"),
                agentEvents.stream().map(EventRecord::event).toList());

        store.untrack("ta");
        store.untrack("tb");
    }

    @Test
    void scanFindsTaskDirsUnderTasksRoot() throws Exception {
        EventLog log = new EventLog(10);
        store.track("t1", log, () -> summary("t1", "running"));
        log.append("message", Json.obj().put("text", "a"), MAIN, null);
        store.flush("t1");
        // 非法形状:tasks 下的非目录文件、无 meta.json 的目录,均不入索引
        Files.createDirectories(dataDir.resolve("tasks").resolve("no-meta"));
        Files.writeString(dataDir.resolve("tasks").resolve("workspaces.json"), "{}");

        List<TaskStore.StoredTask> scanned = store.scan();
        assertEquals(1, scanned.size());
        assertEquals("t1", scanned.get(0).taskId());
        assertEquals(dataDir.resolve("tasks").resolve("t1"), scanned.get(0).dir());
    }

    @Test
    void restartMarksNonTerminalFailedInMainFile() throws Exception {
        EventLog log = new EventLog(1000);
        store.track("t1", log, () -> summary("t1", "running"));
        long seq = log.append("message", Json.obj().put("text", "hi"), MAIN, null).seq();
        store.flush("t1");
        store.stop(); // 模拟进程退出

        TaskStore store2 = new TaskStore(props); // 新实例同目录 = 重启
        List<TaskStore.StoredTask> scanned = store2.scan();
        assertEquals(1, scanned.size());
        assertEquals("running", scanned.get(0).summary().path("status").asString());
        ObjectNode fixed = store2.markRestartFailed(scanned.get(0), "worker 重启中断");
        assertEquals("failed", fixed.path("status").asString());
        Path dir = scanned.get(0).dir();
        assertEquals(seq + 1, store2.diskLastSeq(dir), "error 事件接在磁盘尾部 seq=磁盘last+1");
        List<String> lines = Files.readAllLines(dir.resolve(MAIN + ".jsonl"));
        assertEquals(MAIN, Json.parse(lines.getLast()).path("agentId").asString(),
                "重启 error 落 mainAgentId 文件且带 agentId");
        // 旧格式(无 mainAgentId)→ 追加进 legacy events.jsonl 且不带 agentId
        TaskStore.StoredTask legacy = new TaskStore.StoredTask("told", dir.getParent().resolve("told"),
                Json.obj().put("taskId", "told").put("status", "running"));
        Files.createDirectories(legacy.dir());
        ObjectNode fixedOld = store2.markRestartFailed(legacy, "旧任务中断");
        assertTrue(Files.isRegularFile(legacy.dir().resolve("events.jsonl")), "旧格式写 events.jsonl");
        assertFalse(Json.parse(Files.readAllLines(legacy.dir().resolve("events.jsonl")).getLast())
                .has("agentId"), "旧格式行不带 agentId 字段");
        assertEquals("failed", fixedOld.path("status").asString());
        store2.stop();
    }

    @Test
    void tasksIsolatedOnDisk() throws Exception {
        EventLog la = new EventLog(10);
        EventLog lb = new EventLog(10);
        store.track("ta", la, () -> summary("ta", "running"));
        store.track("tb", lb, () -> summary("tb", "running"));
        la.append("message", Json.obj().put("text", "a"), MAIN, null);
        lb.append("message", Json.obj().put("text", "b"), MAIN, null);
        store.flush("ta");
        store.flush("tb");
        assertTrue(Files.isRegularFile(dataDir.resolve("tasks").resolve("ta").resolve(MAIN + ".jsonl")),
                "ta 独立任务目录");
        assertTrue(Files.isRegularFile(dataDir.resolve("tasks").resolve("tb").resolve(MAIN + ".jsonl")),
                "tb 独立任务目录");
        assertEquals(2, store.scan().size());
    }

    @Test
    void legacyEventsJsonlReadable() throws Exception {
        // 旧单文件布局的任务目录:readEvents 天然兼容(行无 agentId → 主线程 wire)
        Path dir = dataDir.resolve("tasks").resolve("told");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("events.jsonl"), """
                {"seq":1,"ts":1,"event":"user.message","payload":{"text":"旧问"}}
                {"seq":2,"ts":2,"event":"delta","payload":{"text":"旧流"}}
                {"seq":3,"ts":3,"event":"done","payload":{"summary":"旧答"}}
                """);
        Files.writeString(dir.resolve("meta.json"),
                Json.write(Json.obj().put("taskId", "told").put("status", "done")));
        List<ObjectNode> events = store.readEvents(dir, null, 0, 100);
        assertEquals(List.of(1L, 2L, 3L), events.stream().map(e -> e.path("seq").asLong()).toList());
        assertNull(events.get(0).path("payload").path("agentId").asString(null), "旧行 wire 无 agentId");
        assertEquals(3, store.diskLastSeq(dir));
        assertTrue(store.scan().stream().anyMatch(s -> s.taskId().equals("told")), "旧任务可被索引");
        assertTrue(ConversationLoader.load(store, dir, "").isEmpty(), "旧格式无可重建会话(mainAgentId 空)");
    }

    @Test
    void tornTailTolerated() throws Exception {
        EventLog log = new EventLog(1000);
        store.track("t1", log, () -> summary("t1", "done"));
        long seqA = log.append("message", Json.obj().put("text", "a"), MAIN, null).seq();
        long seqB = log.append("message", Json.obj().put("text", "b"), MAIN, null).seq();
        store.flush("t1");
        store.untrack("t1");
        // 模拟崩溃残留:半行 JSON 无换行
        Files.writeString(dataDir.resolve("tasks").resolve("t1").resolve(MAIN + ".jsonl"),
                "{\"seq\":3,\"ts\":1,\"event\":\"del", java.nio.file.StandardOpenOption.APPEND);
        Path dir = dataDir.resolve("tasks").resolve("t1");
        assertEquals(2, store.readEvents(dir, MAIN, 0, 100).size(), "撕行被跳过");
        assertEquals(seqB, store.diskLastSeq(dir), "撕行不计入 lastSeq");
        assertTrue(seqB > seqA, "雪花 ID 进程内递增");
    }

    @Test
    void deleteRemovesDirPermanently() throws Exception {
        EventLog log = new EventLog(1000);
        store.track("t1", log, () -> summary("t1", "done"));
        log.append("message", Json.obj().put("text", "a"), MAIN, null);
        store.flush("t1");
        store.untrack("t1");
        Path dir = dataDir.resolve("tasks").resolve("t1");
        assertTrue(Files.isDirectory(dir));
        store.delete(dir);
        assertFalse(Files.exists(dir), "用户删除是唯一删除路径,目录整体移除");
    }

    @Test
    void queueRoundTripArbitraryTextAndEmptyOverwrite() throws Exception {
        Path dir = dataDir.resolve("tasks").resolve("tq");
        Files.createDirectories(dir);
        // 文件不存在 → 空列表
        assertEquals(List.of(), store.readQueue(dir), "queue.jsonl 缺失返回空列表");
        // 任意用户文本往返(含引号/反斜杠/换行/中文/emoji/空串),JSON 行序列化必须无损
        List<UserInput> items = List.of(UserInput.of("你好 world"),
                UserInput.of("含 \"双引号\" 与 \\\\ 反斜杠"),
                UserInput.of("多行\n文本\r\n换行"), UserInput.of("emoji 🚀 表情"), UserInput.of(""));
        store.writeQueue(dir, items);
        assertEquals(items, store.readQueue(dir), "每行一个输入,顺序=写入序");
        // 撕行容忍:末行半行 JSON 被跳过,不影响前面完整行
        Files.writeString(dir.resolve("queue.jsonl"), "{\"text\":\"撕裂",
                StandardOpenOption.APPEND);
        assertEquals(items, store.readQueue(dir), "末行撕行被跳过");
        // 空列表覆盖写(清空既有内容)
        store.writeQueue(dir, List.of());
        assertEquals(List.of(), store.readQueue(dir), "空列表也能写/覆盖");
        assertEquals(0L, Files.size(dir.resolve("queue.jsonl")), "空队列落成空文件");
    }

    @Test
    void deleteQueueRemovesFileAndIgnoresMissing() throws Exception {
        Path dir = dataDir.resolve("tasks").resolve("tq2");
        Files.createDirectories(dir);
        store.writeQueue(dir, List.of(UserInput.of("a"), UserInput.of("b")));
        assertTrue(Files.isRegularFile(dir.resolve("queue.jsonl")));
        store.deleteQueue(dir);
        assertFalse(Files.exists(dir.resolve("queue.jsonl")), "删除 queue.jsonl");
        store.deleteQueue(dir); // 不存在:忽略不抛
        store.deleteQueue(dir.resolve("nope")); // 目录不存在:忽略不抛
    }
}
