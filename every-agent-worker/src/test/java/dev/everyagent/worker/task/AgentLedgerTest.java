package dev.everyagent.worker.task;

import dev.everyagent.contract.json.Json;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.proto.TaskDtos.ModelSnapshot;
import dev.everyagent.worker.proto.TaskDtos.Usage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 子 agent 台账持久化纯单测(@TempDir,无 Spring):
 * TaskEntry.summaryJson 序列化 agentLedger → meta.json 的 agents 数组,
 * 经 TaskStore 落盘后读回(冷启动恢复供体),运行中状态在恢复时归 stopped。
 */
class AgentLedgerTest {

    @TempDir
    Path dataDir;

    @Test
    void agentLedgerPersistsToMetaAndRoundTrips() throws Exception {
        WorkerProperties props = new WorkerProperties();
        props.setDataDir(dataDir.toString());
        TaskStore store = new TaskStore(props);

        ModelSnapshot snap = new ModelSnapshot("cfg1", "p", null, "m",
                Json.obj().put("contextWindowTokens", 100_000));
        TaskEntry t = new TaskEntry("t1", "任务", snap, "sk", "C:/ws", "a_main1", 1000);

        // 模拟子 agent 台账项(与 AgentEntity.toSummary 同形)
        ObjectNode a = Json.obj()
                .put("agentId", "sub_1")
                .put("kind", "SUB")
                .put("title", "子任务")
                .put("createdAt", 123L)
                .put("status", "completed")
                .put("lastText", "结果");
        a.set("latestActivity", Json.obj().put("content", "结果").put("updatedAt", 456L));
        a.set("usage", Json.toJson(new Usage(10, 5, 15)));
        t.agentLedger.put("sub_1", a);

        // 台账随 summaryJson 序列化进 agents 数组
        ObjectNode meta = t.summaryJson();
        JsonNode agents = meta.path("agents");
        assertTrue(agents.isArray(), "summaryJson 含 agents 数组");
        assertEquals(1, agents.size());
        assertEquals("sub_1", agents.get(0).path("agentId").asString());
        assertEquals("completed", agents.get(0).path("status").asString());

        // 落盘 + 读回(冷启动恢复的供体路径)
        store.track("t1", t.log, t::summaryJson);
        store.updateMeta("t1");
        ObjectNode read = store.readMeta(store.dirOf("t1"));
        JsonNode readAgents = read.path("agents");
        assertEquals("sub_1", readAgents.get(0).path("agentId").asString(),
                "meta.json 持久化 agents 台账");
        assertEquals("completed", readAgents.get(0).path("status").asString());
        assertEquals(15, readAgents.get(0).path("usage").path("totalTokens").asLong());
        store.untrack("t1");
    }
}
