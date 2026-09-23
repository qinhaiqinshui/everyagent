package dev.everyagent.worker.task;

import dev.everyagent.contract.json.Json;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.proto.TaskDtos.ModelSnapshot;
import dev.everyagent.worker.proto.TaskDtos.Usage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
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
 * 子 agent 台账持久化纯单测(@TempDir,无 Spring):
 * 台账自 meta.json 拆出:summaryJson 不再携带 agents(减轻 tasks.list 任务列表数据),
 * 由 TaskStore.writeAgents 独立落盘 agents.json、readAgents 读回(冷启动恢复供体);
 * restoreAgentLedger 优先 agents.json 恢复并把运行中状态归 stopped,
 * 旧任务(仅 meta.json 带 agents、无 agents.json)回退兼容;
 * AgentEntity.toSummary 的 context 字段有数据才写入。
 */
class AgentLedgerTest {

    @TempDir
    Path dataDir;

    private WorkerProperties props() {
        WorkerProperties props = new WorkerProperties();
        props.setHomeDir(dataDir.toString());
        return props;
    }

    private static TaskEntry newTask(String taskId) {
        ModelSnapshot snap = new ModelSnapshot("cfg1", "p", null, "m",
                Json.obj().put("contextWindowTokens", 100_000));
        return new TaskEntry(taskId, "任务", snap, "sk", "C:/ws", "defaultworkspace", "a_main1", 1000);
    }

    /** 模拟台账项(与 AgentEntity.toSummary 同形)。 */
    private static ObjectNode subSummary(String id, String status) {
        return Json.obj()
                .put("agentId", id)
                .put("kind", "SUB")
                .put("title", "子任务")
                .put("createdAt", 123L)
                .put("status", status)
                .put("lastText", "结果");
    }

    /** TaskManager 只在 restoreAgentLedger 路径用到 store(其余依赖传 null,不触发生命周期)。 */
    private static TaskManager manager(TaskStore store, WorkerProperties props) {
        return new TaskManager(null, null, null, null, null, null, props, null, null, null, null,
                store, null, null, null, null, null, null);
    }

    @Test
    void summaryJsonOmitsAgents() {
        TaskEntry t = newTask("t1");
        t.agentLedger.put("sub_1", subSummary("sub_1", "completed"));
        ObjectNode meta = t.summaryJson();
        assertTrue(meta.path("agents").isMissingNode(),
                "summaryJson 不再输出 agents(台账改由 agents.json 承载)");
        assertEquals("sub_1", t.agentLedger.get("sub_1").path("agentId").asString(),
                "台账本体仍在内存(落盘走 writeAgents)");
    }

    @Test
    void writeAgentsRoundTripsAndEmptyDeletesFile() throws Exception {
        WorkerProperties props = props();
        TaskStore store = new TaskStore(props);
        TaskEntry t = newTask("t1");
        store.track("t1", "defaultworkspace", t.log, t::summaryJson);
        Path dir = store.dirOf("t1");
        Path agentsFile = dir.resolve("agents.json");

        // 空台账且无文件:no-op(不写空数组占位)
        store.writeAgents("t1", t.agentLedger.values());
        assertFalse(Files.exists(agentsFile));
        assertNull(store.readAgents(dir), "无 agents.json 时 readAgents 返回 null(走旧格式回退)");

        // 台账落盘 + 读回(usage/lastText 完整)
        ObjectNode sub1 = subSummary("sub_1", "completed");
        sub1.set("usage", Json.toJson(new Usage(10, 5, 15)));
        t.agentLedger.put("sub_1", sub1);
        t.agentLedger.put("sub_2", subSummary("sub_2", "stopped"));
        store.writeAgents("t1", t.agentLedger.values());
        List<ObjectNode> read = store.readAgents(dir);
        assertNotNull(read);
        assertEquals(2, read.size());
        ObjectNode r1 = read.stream()
                .filter(a -> "sub_1".equals(a.path("agentId").asString()))
                .findFirst().orElseThrow();
        assertEquals("completed", r1.path("status").asString());
        assertEquals("结果", r1.path("lastText").asString());
        assertEquals(15, r1.path("usage").path("totalTokens").asLong(), "累计 usage 台账读回");

        // 空台账再写:删除已存在文件(不遗留脏数据)
        t.agentLedger.clear();
        store.writeAgents("t1", t.agentLedger.values());
        assertFalse(Files.exists(agentsFile));
        store.untrack("t1");
    }

    @Test
    void restorePrefersAgentsJsonAndNormalizesRunning() throws Exception {
        WorkerProperties props = props();
        TaskStore store = new TaskStore(props);
        Path dir = props.resolveWorkspacesDir().resolve("defaultworkspace").resolve("tasks").resolve("t2");
        Files.createDirectories(dir);
        ArrayNode agents = Json.arr();
        agents.add(subSummary("sub_r", "running"));
        agents.add(subSummary("sub_w", "waiting-user"));
        agents.add(subSummary("sub_c", "completed"));
        Files.writeString(dir.resolve("agents.json"), Json.write(Json.obj().set("agents", agents)));

        TaskEntry t = newTask("t2");
        // meta 无 agents 字段:agents.json 存在即恢复,运行中/waiting-user 归 stopped
        manager(store, props).restoreAgentLedger(t, dir, Json.obj());
        assertEquals(3, t.agentLedger.size());
        assertEquals("stopped", t.agentLedger.get("sub_r").path("status").asString(),
                "running 归一为 stopped(worker 重启中断)");
        assertEquals("stopped", t.agentLedger.get("sub_w").path("status").asString(),
                "waiting-user 归一为 stopped");
        assertEquals("completed", t.agentLedger.get("sub_c").path("status").asString(),
                "终态保持原值");
    }

    @Test
    void restoreFallsBackToLegacyMetaAgents() throws Exception {
        WorkerProperties props = props();
        TaskStore store = new TaskStore(props);
        Path dir = props.resolveWorkspacesDir().resolve("defaultworkspace").resolve("tasks").resolve("t3");
        Files.createDirectories(dir);
        // 旧任务:无 agents.json,台账随 meta.json 落盘
        ArrayNode legacy = Json.arr();
        legacy.add(subSummary("sub_old_r", "running"));
        legacy.add(subSummary("sub_old_c", "error"));
        ObjectNode meta = Json.obj().set("agents", legacy);

        TaskEntry t = newTask("t3");
        manager(store, props).restoreAgentLedger(t, dir, meta);
        assertEquals(2, t.agentLedger.size());
        assertEquals("stopped", t.agentLedger.get("sub_old_r").path("status").asString());
        assertEquals("error", t.agentLedger.get("sub_old_c").path("status").asString());

        // 双源并存时 agents.json 优先(忽略 meta 的 agents)
        ArrayNode fresh = Json.arr();
        fresh.add(subSummary("sub_new", "completed"));
        Files.writeString(dir.resolve("agents.json"), Json.write(Json.obj().set("agents", fresh)));
        TaskEntry t2 = newTask("t3");
        manager(store, props).restoreAgentLedger(t2, dir, meta);
        assertEquals(1, t2.agentLedger.size());
        assertTrue(t2.agentLedger.containsKey("sub_new"), "agents.json 优先于 meta.agents");
    }

    @Test
    void toSummaryContextFieldOnlyWrittenWithUsage() {
        TaskEntry t = newTask("t4");
        AgentEntity sub = new AgentEntity(t, "sub_9", AgentEntity.Kind.SUB, "子任务",
                null, null, List.of());
        assertTrue(sub.toSummary().path("context").isMissingNode(), "无数据时省略 context 字段");

        sub.recordLastRound(new Usage(1200, 30, 1230), new Usage(1200, 30, 1230), 100_000L, "model-x");
        ObjectNode s = sub.toSummary();
        JsonNode ctx = s.path("context");
        assertTrue(ctx.isObject(), "有数据时写入 context");
        assertEquals(1200, ctx.path("inputTokens").asLong(), "最近一轮 prompt tokens");
        assertEquals(100_000, ctx.path("contextWindowTokens").asLong(), "窗口上限");
        assertEquals("model-x", ctx.path("model").asString(), "模型名");
        assertEquals(1230, s.path("usage").path("totalTokens").asLong(),
                "usage 仍是累计值,与 context 并存");

        // 上下文缺省(仅窗口/模型)不覆盖,context 依旧按最近一轮 usage 输出
        sub.recordLastRound(null, null, null, null);
        ObjectNode s2 = sub.toSummary();
        assertEquals(1200, s2.path("context").path("inputTokens").asLong(), "null 传参不覆盖");
    }

    /** Spring AI Usage 最小实现(仅三个 token 计数,足供 addUsage 累计)。 */
    private static final class SpringUsage implements org.springframework.ai.chat.metadata.Usage {
        private final Integer prompt;
        private final Integer completion;

        SpringUsage(int prompt, int completion) {
            this.prompt = prompt;
            this.completion = completion;
        }

        @Override
        public Integer getPromptTokens() {
            return prompt;
        }

        @Override
        public Integer getCompletionTokens() {
            return completion;
        }

        @Override
        public Object getNativeUsage() {
            return null;
        }
    }

    @Test
    void addUsageAccumulatesAndFeedsLedgerTotal() {
        TaskEntry t = newTask("t5");
        AgentEntity sub = new AgentEntity(t, "sub_10", AgentEntity.Kind.SUB, "子任务",
                null, null, List.of());

        // 第一轮:先累计(Advisor usage 分支顺序:addUsage 在 events.usage/recordLastRound 之前)
        sub.addUsage(new SpringUsage(100, 20));
        sub.recordLastRound(new Usage(100, 20, 120), sub.usageRef().get(), 100_000L, "model-x");
        assertEquals(120, sub.usage().totalTokens(), "第一轮累计");
        assertEquals(120, sub.toSummary().path("usage").path("totalTokens").asLong(),
                "台账 usage 为含本轮的累计值");

        // 第二轮:逐轮累加,context 仍为最近一轮快照
        sub.addUsage(new SpringUsage(50, 10));
        sub.recordLastRound(new Usage(50, 10, 60), sub.usageRef().get(), 100_000L, "model-x");
        assertEquals(180, sub.usage().totalTokens(), "两轮累计(120 + 60)");
        ObjectNode s = sub.toSummary();
        assertEquals(180, s.path("usage").path("totalTokens").asLong(), "台账 usage 为累计值");
        assertEquals(50, s.path("context").path("inputTokens").asLong(), "context 为最近一轮");

        // null / 全零不累计(addUsage 自身守卫,usage 不被清零)
        sub.addUsage(null);
        sub.addUsage(new SpringUsage(0, 0));
        assertEquals(180, sub.usage().totalTokens(), "null/零值轮不累计");
    }
}
