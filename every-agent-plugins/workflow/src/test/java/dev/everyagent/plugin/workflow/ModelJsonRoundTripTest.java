package dev.everyagent.plugin.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.workflow.model.AgentEventLine;
import dev.everyagent.plugin.workflow.model.ConditionDef;
import dev.everyagent.plugin.workflow.model.Definition;
import dev.everyagent.plugin.workflow.model.DefinitionJson;
import dev.everyagent.plugin.workflow.model.EdgeDef;
import dev.everyagent.plugin.workflow.model.EventLine;
import dev.everyagent.plugin.workflow.model.InstanceMeta;
import dev.everyagent.plugin.workflow.model.InstanceStatus;
import dev.everyagent.plugin.workflow.model.NodeDef;
import dev.everyagent.plugin.workflow.model.OnError;
import dev.everyagent.plugin.workflow.model.RunRecord;
import dev.everyagent.plugin.workflow.model.VariableDef;

/**
 * 模型层 JSON 往返保真：五种持久化结构（definition/meta/events 行/runs/agent jsonl 行）
 * 序列化-反序列化等值，且 wire 形态符合存储设计契约（seq 字符串、枚举短名、NON_NULL 二形态）。
 */
class ModelJsonRoundTripTest {

    @Test
    void definitionRoundTripPreservesAllFields() {
        Definition def = fullDefinition();
        Definition back = Json.MAPPER.readValue(Json.write(def), Definition.class);
        assertThat(back).isEqualTo(def);
    }

    @Test
    void definitionWireShapeMatchesContract() {
        String json = Json.write(fullDefinition());
        assertThat(json).contains("\"schemaVersion\":1");
        assertThat(json).contains("\"id\":\"wf_a1b2c\"");
        assertThat(json).contains("\"workspaceId\":\"w_x1\"");
        assertThat(json).contains("\"onError\":\"askUser\"");
        // 命令节点字段 + AI 节点字段
        assertThat(json).contains("\"command\":\"mkdir -p out/{{novelName}}\"");
        assertThat(json).contains("\"timeoutMs\":60000");
        assertThat(json).contains("\"maxRuns\":2");
        assertThat(json).contains("\"agent\":\"writer\"");
        assertThat(json).contains("\"input\":\"请为《{{novelName}}》拟一份章节大纲\"");
        // 条件边
        assertThat(json).contains("\"condition\":{\"type\":\"outputMatches\",\"pattern\":\"ok.*\"}");
    }

    @Test
    void enumWireNamesMatchContract() {
        assertThat(Json.write(OnError.ASK_USER)).isEqualTo("\"askUser\"");
        assertThat(Json.write(OnError.SUPERVISOR)).isEqualTo("\"supervisor\"");
        assertThat(Json.write(InstanceStatus.WAITING_USER)).isEqualTo("\"waiting-user\"");
        assertThat(Json.write(InstanceStatus.CANCELLED)).isEqualTo("\"cancelled\"");
        // 反序列化（waiting-user 连字符形态）
        assertThat(Json.MAPPER.readValue("\"waiting-user\"", InstanceStatus.class)).isEqualTo(InstanceStatus.WAITING_USER);
        assertThat(Json.MAPPER.readValue("\"askUser\"", OnError.class)).isEqualTo(OnError.ASK_USER);
    }

    @Test
    void instanceMetaRoundTripWithSnapshotAndProjections() {
        InstanceMeta meta = new InstanceMeta(
                InstanceMeta.SCHEMA_VERSION, "wi_x9y8z", "wf_a1b2c", "小说生成", "w_x1",
                "task_origin1",
                fullDefinition(),
                Map.of("novelName", "星际牧羊人"),
                InstanceStatus.WAITING_USER,
                1730000001000L, 1730000001500L, null, null, null,
                new InstanceMeta.Error("节点失败", "wn_c3", 7),
                new InstanceMeta.PendingDecision("wn_c3", 7, "如何处理?",
                        List.of("retry", "skip", "fail", "retryWithInput"), "错误摘要", 1730000009000L),
                Map.of("wn_c3", new InstanceMeta.NodeState(2, "failed", 7)),
                7);
        InstanceMeta back = Json.MAPPER.readValue(Json.write(meta), InstanceMeta.class);
        assertThat(back).isEqualTo(meta);
        assertThat(back.definitionSnapshot()).isEqualTo(meta.definitionSnapshot());
        assertThat(back.resumedDurationMs()).isNull(); // 旧实例缺字段读为 null(兼容)

        // 续跑后终态:durationMs=总时长,resumedDurationMs=本次续跑段
        InstanceMeta resumed = new InstanceMeta(
                InstanceMeta.SCHEMA_VERSION, meta.id(), meta.definitionId(), meta.definitionName(),
                meta.workspaceId(), meta.originTaskId(), meta.definitionSnapshot(), meta.variables(),
                InstanceStatus.DONE,
                1730000001000L, 1730000001500L, 1730000100000L,
                1730000100000L - 1730000001000L, 12_000L,
                null, null,
                Map.of("wn_c3", new InstanceMeta.NodeState(2, "done", 7)),
                7);
        InstanceMeta resumedBack = Json.MAPPER.readValue(Json.write(resumed), InstanceMeta.class);
        assertThat(resumedBack.resumedDurationMs()).isEqualTo(12_000L);

        String json = Json.write(meta);
        assertThat(json).contains("\"status\":\"waiting-user\"");
        assertThat(json).contains("\"definitionSnapshot\":");
        assertThat(json).contains("\"runCounter\":7");
        // 运行中投影为 null 的字段不上 wire
        assertThat(json).doesNotContain("\"endedAt\"");
        assertThat(json).doesNotContain("\"durationMs\"");
    }

    @Test
    void runRecordOutputHasTwoShapesCommandAndAi() {
        RunRecord command = new RunRecord(7, "wn_b7", "初始化目录", "command",
                1730000002000L, 1730000025000L, 23000L,
                new RunRecord.RunInput("mkdir -p out/{{novelName}}", "mkdir -p out/星际牧羊人", "mkdir -p out/星际牧羊人"),
                RunRecord.RunOutput.command(0, "done", ""),
                List.of("we_02"),
                null, null, null);
        String json = Json.write(command);
        assertThat(json).contains("\"output\":{\"exitCode\":0,\"stdout\":\"done\",\"stderr\":\"\"}");
        assertThat(json).doesNotContain("\"text\"");
        assertThat(Json.MAPPER.readValue(json, RunRecord.class)).isEqualTo(command);

        RunRecord ai = new RunRecord(8, "wn_c3", "生成大纲", "ai",
                1730000026000L, 1730000035000L, 9000L,
                new RunRecord.RunInput("请为《{{novelName}}》拟大纲", "请为《星际牧羊人》拟大纲", "请为《星际牧羊人》拟大纲"),
                RunRecord.RunOutput.ai("第一章 牧羊人……"),
                List.of(),
                new RunRecord.AgentTrace("writer", "gpt5", 12, 18,
                        new RunRecord.Usage(15200L, 3100L)),
                null,
                List.of(new RunRecord.SupervisorEntry(1,
                        new RunRecord.SupervisorDecision("retry", null, "瞬时错误"), "首次失败")));
        String aiJson = Json.write(ai);
        assertThat(aiJson).contains("\"output\":{\"text\":\"第一章 牧羊人……\"}");
        assertThat(aiJson).doesNotContain("\"exitCode\"");
        assertThat(aiJson).contains("\"agent\":{\"name\":\"writer\",\"modelConfigId\":\"gpt5\",\"seedTurns\":12,\"resultTurns\":18,\"usage\":{\"inputTokens\":15200,\"outputTokens\":3100}}");
        assertThat(Json.MAPPER.readValue(aiJson, RunRecord.class)).isEqualTo(ai);
    }

    @Test
    void eventLineSeqStaysStringOnWire() {
        EventLine line = new EventLine("7391234567890123456", 1730000002000L, "node.started",
                Json.obj().put("nodeId", "wn_c3").put("runNo", 7));
        String json = Json.write(line);
        assertThat(json).startsWith("{\"seq\":\"7391234567890123456\"");
        EventLine back = Json.MAPPER.readValue(json, EventLine.class);
        assertThat(back).isEqualTo(line);
        assertThat(back.seqValue()).isEqualTo(7391234567890123456L);
    }

    @Test
    void agentEventLineRoundTripKeepsAgentId() {
        AgentEventLine line = new AgentEventLine("7391234567890123999", 1730000002100L,
                "user.message", "writer#wi_x9y8z",
                Json.obj().put("content", "请拟大纲"));
        AgentEventLine back = Json.MAPPER.readValue(Json.write(line), AgentEventLine.class);
        assertThat(back).isEqualTo(line);
        assertThat(back.agentId()).isEqualTo("writer#wi_x9y8z");
    }

    @Test
    void unknownFieldsAreIgnoredForForwardCompatibility() {
        String json = Json.write(fullDefinition());
        String padded = json.replaceFirst("\\{", "\\{\"futureField\":123,");
        Definition back = Json.MAPPER.readValue(padded, Definition.class);
        assertThat(back.id()).isEqualTo("wf_a1b2c");
    }

    @Test
    void legacyAgentsTableMigratesOntoReferencingNodes() {
        // 旧结构：agents[] 总表声明 + 节点只带 agent 引用 → 解析口迁移到节点自带字段
        String legacy = """
                {"schemaVersion":1,"id":"wf_old","name":"旧定义","workspaceId":"w_x1",
                 "nodes":[
                   {"id":"n1","type":"start","name":"开始","x":0,"y":0},
                   {"id":"n2","type":"ai","name":"初稿","x":100,"y":0,"agent":"writer"},
                   {"id":"n3","type":"ai","name":"润色","x":200,"y":0,"agent":"polisher",
                    "modelConfigId":"node-model","systemPrompt":"节点自带优先"}],
                 "edges":[{"id":"e1","from":"n1","to":"n2"},{"id":"e2","from":"n2","to":"n3"}],
                 "agents":[
                   {"name":"writer","modelConfigId":"gpt5","systemPrompt":"你是资深小说作者"},
                   {"name":"polisher","modelConfigId":"agent-model","systemPrompt":"总表声明"},
                   {"name":"nobody","systemPrompt":"无节点引用,静默丢弃"}]}
                """;
        Definition migrated = DefinitionJson.parseText(legacy);
        assertThat(Json.write(migrated)).doesNotContain("\"agents\"");
        NodeDef writer = migrated.nodesOrEmpty().stream().filter(n -> n.id().equals("n2")).findFirst().orElseThrow();
        assertThat(writer.agent()).isEqualTo("writer");
        assertThat(writer.modelConfigId()).isEqualTo("gpt5");
        assertThat(writer.systemPrompt()).isEqualTo("你是资深小说作者");
        NodeDef polisher = migrated.nodesOrEmpty().stream().filter(n -> n.id().equals("n3")).findFirst().orElseThrow();
        // 节点自带值优先于总表声明
        assertThat(polisher.modelConfigId()).isEqualTo("node-model");
        assertThat(polisher.systemPrompt()).isEqualTo("节点自带优先");
        // 迁移后可过校验并可保存（新形态无 agents 键）
        assertThat(dev.everyagent.plugin.workflow.validate.DefinitionValidator.validate(migrated)).isEmpty();
    }

    // ── 构造完整定义（覆盖全部字段类型）──

    static Definition fullDefinition() {
        return new Definition(
                Definition.SCHEMA_VERSION, "wf_a1b2c", "小说生成", "三步生成", "w_x1",
                1730000000000L, 1730000000500L,
                List.of(new VariableDef("novelName", "小说名", true, null, "要生成的小说标题")),
                OnError.ASK_USER,
                new Definition.Supervisor("gpt5", "你是工作流督导"),
                List.of(
                        new NodeDef("wn_st0", NodeDef.TYPE_START, "开始", 40.0, 200.0,
                                null, null, null, null, null, null, null),
                        new NodeDef("wn_b7", NodeDef.TYPE_COMMAND, "初始化目录", 240.0, 200.0,
                                "mkdir -p out/{{novelName}}", 60000L, 2, null, null, null, null),
                        new NodeDef("wn_c3", NodeDef.TYPE_AI, "生成大纲", 440.0, 200.0,
                                null, null, 2, "writer", null, "请为《{{novelName}}》拟一份章节大纲",
                                "你是资深小说作者")),
                List.of(
                        new EdgeDef("we_01", "wn_st0", "wn_b7", null),
                        new EdgeDef("we_02", "wn_b7", "wn_c3",
                                new ConditionDef(ConditionDef.OUTPUT_MATCHES, null, null, "ok.*")),
                        new EdgeDef("we_03", "wn_c3", "wn_b7",
                                new ConditionDef(ConditionDef.EXIT_CODE, 2L, null, null))));
    }
}
