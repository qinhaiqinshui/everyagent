package dev.everyagent.plugin.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.exception.BadParamsException;
import dev.everyagent.plugin.workflow.model.Definition;
import dev.everyagent.plugin.workflow.model.EventLine;
import dev.everyagent.plugin.workflow.model.InstanceMeta;
import dev.everyagent.plugin.workflow.model.InstanceStatus;
import dev.everyagent.plugin.workflow.model.RunRecord;
import dev.everyagent.plugin.workflow.store.WorkflowStore;

/**
 * WorkflowStore 磁盘行为：目录布局、原子写、索引、导入重赋 id、激活扫描标 failed、
 * 损坏文件容错（列表不崩/坏行跳过）。不依赖 worker 类（FakeWorkspaces 桩实现 plugin-api 接口）。
 */
class WorkflowStoreTest {

    @TempDir
    Path home;

    FakeWorkspaces workspaces;
    WorkflowStore store;

    static final String WS_A = "ws_aaa";
    static final String WS_B = "ws_bbb";

    @BeforeEach
    void setUp() {
        workspaces = new FakeWorkspaces(home);
        workspaces.register(home.resolve("projA"), WS_A);
        store = new WorkflowStore(workspaces);
    }

    private Path workflowsRoot(String wsId) {
        return home.resolve("workspaces").resolve(wsId).resolve("workflows");
    }

    private Definition sampleDef() {
        return DefinitionValidatorTest.withVariables(DefinitionValidatorTest.def(
                        DefinitionValidatorTest.node("n1", "start"),
                        DefinitionValidatorTest.node("n2", "ai")
                                .agent("writer").input("写《{{novelName}}》"),
                        DefinitionValidatorTest.edge("e1", "n1", "n2")),
                new dev.everyagent.plugin.workflow.model.VariableDef("novelName", "小说名", true, null, null));
    }

    // ── 定义 ──

    @Test
    void saveDefinitionAssignsIdWritesFileAndForcesWorkspace() throws Exception {
        Definition saved = store.saveDefinition(WS_A, sampleDef());
        assertThat(saved.id()).startsWith("wf_");
        assertThat(saved.workspaceId()).isEqualTo(WS_A);
        assertThat(saved.schemaVersion()).isEqualTo(1);
        Path file = workflowsRoot(WS_A).resolve("definitions").resolve(saved.id()).resolve("definition.json");
        assertThat(file).isRegularFile();
        // 磁盘往返保真：AI 节点自带 agent 名
        Definition disk = Json.MAPPER.readValue(file.toFile(), Definition.class);
        assertThat(disk.nodesOrEmpty().get(1).agent()).isEqualTo("writer");
        assertThat(store.getDefinition(WS_A, saved.id())).contains(saved);
        // 挂靠边界：另一工作区查不到
        assertThat(store.getDefinition(WS_B, saved.id())).isEmpty();
    }

    @Test
    void updatePreservesCreatedAtAndBumpsUpdatedAt() throws Exception {
        Definition first = store.saveDefinition(WS_A, sampleDef());
        Thread.sleep(25);
        Definition updated = store.saveDefinition(WS_A, DefinitionValidatorTest.withVariables(
                DefinitionValidatorTest.def(
                        DefinitionValidatorTest.node("n1", "start"),
                        DefinitionValidatorTest.node("n2", "ai").agent("writer"),
                        DefinitionValidatorTest.node("n3", "ai").agent("editor"),
                        DefinitionValidatorTest.edge("e1", "n1", "n2"),
                        DefinitionValidatorTest.edge("e2", "n2", "n3")),
                new dev.everyagent.plugin.workflow.model.VariableDef("novelName", null, true, null, null))
                .withId(first.id()));
        assertThat(updated.id()).isEqualTo(first.id());
        assertThat(updated.createdAt()).isEqualTo(first.createdAt());
        assertThat(updated.updatedAt()).isGreaterThan(first.updatedAt());
        // 全局唯一 id：同 id 落另一工作区被拒
        Definition foreign = DefinitionValidatorTest.withVariables(DefinitionValidatorTest.def(
                        DefinitionValidatorTest.node("n1", "start")),
                new dev.everyagent.plugin.workflow.model.VariableDef("x", null, true, null, null))
                .withId(first.id());
        assertThatThrownBy(() -> store.saveDefinition(WS_B, foreign))
                .isInstanceOf(BadParamsException.class)
                .hasMessageContaining("其他工作区");
    }

    @Test
    void saveDefinitionRejectsInvalidDraftWithItemizedErrors() {
        Definition bad = DefinitionValidatorTest.def(DefinitionValidatorTest.node("n1", "ai")); // 无 start
        assertThatThrownBy(() -> store.saveDefinition(WS_A, bad))
                .isInstanceOf(BadParamsException.class)
                .hasMessageContaining("start");
    }

    @Test
    void listDefinitionsFiltersByWorkspaceAndSortsByUpdatedAtDesc() throws Exception {
        Definition a = store.saveDefinition(WS_A, sampleDef());
        Thread.sleep(25);
        Definition b = store.saveDefinition(WS_A, DefinitionValidatorTest.withVariables(
                DefinitionValidatorTest.def(DefinitionValidatorTest.node("n1", "start")),
                new dev.everyagent.plugin.workflow.model.VariableDef("x", null, true, null, null)));
        Definition c = store.saveDefinition(WS_B, sampleDef());
        assertThat(store.listDefinitions(WS_A)).extracting(Definition::id)
                .containsExactly(b.id(), a.id());
        assertThat(store.listDefinitions(WS_B)).extracting(Definition::id)
                .containsExactly(c.id());
        assertThat(store.listDefinitions()).extracting(Definition::id)
                .containsExactlyInAnyOrder(a.id(), b.id(), c.id());
    }

    @Test
    void deleteDefinitionRemovesDirButKeepsInstances() throws Exception {
        Definition saved = store.saveDefinition(WS_A, sampleDef());
        InstanceMeta instance = store.createInstance(saved, Map.of("novelName", "星际牧羊人"));
        assertThat(store.deleteDefinition(WS_A, saved.id())).isTrue();
        assertThat(workflowsRoot(WS_A).resolve("definitions").resolve(saved.id())).doesNotExist();
        assertThat(store.findDefinition(saved.id())).isEmpty();
        // 实例持快照不受影响
        assertThat(store.findInstance(instance.id())).isPresent();
        assertThat(store.deleteDefinition(WS_A, saved.id())).isFalse();
    }

    @Test
    void importDefinitionReassignsIdAndLandsInTargetWorkspace() {
        Definition incoming = DefinitionValidatorTest.withVariables(
                DefinitionValidatorTest.def(
                        DefinitionValidatorTest.node("n1", "start"),
                        DefinitionValidatorTest.node("n2", "ai").agent("writer"),
                        DefinitionValidatorTest.node("n3", "command").command("echo {{novelName}}"),
                        DefinitionValidatorTest.edge("e1", "n1", "n2"),
                        DefinitionValidatorTest.edge("e2", "n2", "n3")),
                new dev.everyagent.plugin.workflow.model.VariableDef("novelName", null, true, null, null))
                .withId("wf_from_other_machine");
        Definition imported = store.importDefinition(WS_B, incoming);
        assertThat(imported.id()).startsWith("wf_").isNotEqualTo("wf_from_other_machine");
        assertThat(imported.workspaceId()).isEqualTo(WS_B);
        // 节点/边/agent 名保留
        assertThat(imported.nodesOrEmpty()).extracting(n -> n.id()).containsExactly("n1", "n2", "n3");
        assertThat(store.getDefinition(WS_B, imported.id())).isPresent();
        // 导入校验失败拒绝
        Definition broken = DefinitionValidatorTest.def(DefinitionValidatorTest.node("n1", "ai"));
        assertThatThrownBy(() -> store.importDefinition(WS_B, broken))
                .isInstanceOf(BadParamsException.class);
    }

    @Test
    void exportDefinitionReturnsRawFileContent() throws Exception {
        Definition saved = store.saveDefinition(WS_A, sampleDef());
        String exported = store.exportDefinition(WS_A, saved.id()).orElseThrow();
        Path file = workflowsRoot(WS_A).resolve("definitions").resolve(saved.id()).resolve("definition.json");
        assertThat(exported).isEqualTo(Files.readString(file));
        assertThat(store.exportDefinition(WS_B, saved.id())).isEmpty();
    }

    @Test
    void unsafeWorkspaceSegmentIsRejected() {
        assertThatThrownBy(() -> store.saveDefinition("../evil", sampleDef()))
                .isInstanceOf(BadParamsException.class);
    }

    // ── 实例 ──

    @Test
    void createInstanceDeepCopiesSnapshotAndEmitsCreatedEvent() {
        Definition def = store.saveDefinition(WS_A, sampleDef());
        InstanceMeta meta = store.createInstance(def, Map.of("novelName", "星际牧羊人"), "task_origin9");
        assertThat(meta.id()).startsWith("wi_");
        assertThat(meta.status()).isEqualTo(InstanceStatus.CREATED);
        assertThat(meta.definitionSnapshot()).isEqualTo(def);
        assertThat(meta.originTaskId()).isEqualTo("task_origin9");
        // 快照独立：深拷贝经 JSON 往返（不同对象图），原定义引用不串扰实例
        InstanceMeta reread = store.findInstance(meta.id()).orElseThrow();
        assertThat(reread.definitionSnapshot().nodesOrEmpty())
                .isNotSameAs(def.nodesOrEmpty())
                .hasSize(2);
        assertThat(reread.originTaskId()).isEqualTo("task_origin9");
        // 目录布局：只建实例目录与 runs/（无 agents/——AI 会话单写任务域）
        Path dir = workflowsRoot(WS_A).resolve("instances").resolve(meta.id());
        assertThat(dir.resolve("meta.json")).isRegularFile();
        assertThat(dir.resolve("runs")).isDirectory();
        assertThat(dir.resolve("agents")).doesNotExist();
        // wf.created 事件（originTaskId 同步携带）
        List<EventLine> events = store.readEvents(WS_A, meta.id(), 0, 0);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).event()).isEqualTo("wf.created");
        assertThat(events.get(0).payload().path("instanceId").asString()).isEqualTo(meta.id());
        assertThat(events.get(0).payload().path("variables").path("novelName").asString()).isEqualTo("星际牧羊人");
        assertThat(events.get(0).payload().path("originTaskId").asString()).isEqualTo("task_origin9");
        // 无 origin：meta 省略字段（NON_NULL），payload 不带 originTaskId（前向兼容）
        InstanceMeta plain = store.createInstance(def, Map.of());
        assertThat(plain.originTaskId()).isNull();
        assertThat(Json.write(plain)).doesNotContain("originTaskId");
        assertThat(store.readEvents(WS_A, plain.id(), 0, 0).get(0).payload().has("originTaskId"))
                .isFalse();
    }

    @Test
    void originTaskIdSerializationForwardCompatible() throws Exception {
        // 旧版本 meta.json（无 originTaskId 字段）→ 反序列化得 null，不抛（前向兼容）
        Definition def = store.saveDefinition(WS_A, sampleDef());
        InstanceMeta meta = store.createInstance(def, Map.of());
        Path file = workflowsRoot(WS_A).resolve("instances").resolve(meta.id()).resolve("meta.json");
        tools.jackson.databind.node.ObjectNode legacy = (tools.jackson.databind.node.ObjectNode) Json.parse(
                Files.readString(file));
        legacy.remove("originTaskId");
        dev.everyagent.plugin.api.util.AtomicFiles.writeText(file, Json.write(legacy));
        InstanceMeta back = Json.MAPPER.readValue(file.toFile(), InstanceMeta.class);
        assertThat(back.originTaskId()).isNull();
        assertThat(back.id()).isEqualTo(meta.id());
        // 带值往返保留
        InstanceMeta withOrigin = store.createInstance(def, Map.of(), "task_keep");
        InstanceMeta reread = Json.MAPPER.readValue(
                workflowsRoot(WS_A).resolve("instances").resolve(withOrigin.id())
                        .resolve("meta.json").toFile(), InstanceMeta.class);
        assertThat(reread.originTaskId()).isEqualTo("task_keep");
    }

    @Test
    void listInstancesFiltersAndDoesNotTouchEventsJsonl() throws Exception {
        Definition def = store.saveDefinition(WS_A, sampleDef());
        InstanceMeta i1 = store.createInstance(def, Map.of());
        Definition other = store.saveDefinition(WS_B, sampleDef());
        InstanceMeta i2 = store.createInstance(other, Map.of());

        // events.jsonl 塞垃圾行：listInstances 只读 meta,不解析 events
        Files.writeString(workflowsRoot(WS_A).resolve("instances").resolve(i1.id()).resolve("events.jsonl"),
                "{ this is not json !!!\n", java.nio.file.StandardOpenOption.APPEND);
        List<InstanceMeta> list = store.listInstances(WS_A, def.id());
        assertThat(list).extracting(InstanceMeta::id).containsExactly(i1.id());
        assertThat(store.listInstances(WS_B, null)).extracting(InstanceMeta::id).containsExactly(i2.id());
        // readEvents 容错跳过坏行：只剩合法的 wf.created
        List<EventLine> events = store.readEvents(WS_A, i1.id(), 0, 0);
        assertThat(events).extracting(EventLine::event).containsExactly("wf.created");
    }

    @Test
    void deleteInstanceRemovesDirAndRejectsRunning() {
        Definition def = store.saveDefinition(WS_A, sampleDef());
        InstanceMeta meta = store.createInstance(def, Map.of());
        // created 允许删
        assertThat(store.deleteInstance(WS_A, meta.id())).isTrue();
        assertThat(workflowsRoot(WS_A).resolve("instances").resolve(meta.id())).doesNotExist();

        InstanceMeta again = store.createInstance(def, Map.of());
        store.saveInstanceMeta(withStatus(again, InstanceStatus.RUNNING));
        assertThatThrownBy(() -> store.deleteInstance(WS_A, again.id()))
                .isInstanceOf(BadParamsException.class)
                .hasMessageContaining("拒绝删除");
        store.saveInstanceMeta(withStatus(again, InstanceStatus.DONE));
        assertThat(store.deleteInstance(WS_A, again.id())).isTrue();
    }

    private static InstanceMeta withStatus(InstanceMeta m, InstanceStatus status) {
        return new InstanceMeta(m.schemaVersion(), m.id(), m.definitionId(), m.definitionName(),
                m.workspaceId(), m.originTaskId(), m.definitionSnapshot(), m.variables(), status,
                m.createdAt(), m.startedAt(), m.endedAt(), m.durationMs(), m.resumedDurationMs(),
                m.error(), m.pendingDecision(), m.nodeStates(), m.runCounter());
    }

    // ── 激活扫描 ──

    @Test
    void scanOnActivateIndexesAndMarksNonTerminalInstancesFailed() {
        Definition def = store.saveDefinition(WS_A, sampleDef());
        Definition defDone = store.saveDefinition(WS_A, DefinitionValidatorTest.withVariables(
                DefinitionValidatorTest.def(DefinitionValidatorTest.node("n1", "start")),
                new dev.everyagent.plugin.workflow.model.VariableDef("x", null, true, null, null)));
        InstanceMeta running = withStatus(store.createInstance(def, Map.of()), InstanceStatus.RUNNING);
        store.saveInstanceMeta(running);
        InstanceMeta waiting = store.createInstance(def, Map.of());
        store.saveInstanceMeta(withStatus(waiting, InstanceStatus.WAITING_USER));
        InstanceMeta created = store.createInstance(def, Map.of());
        InstanceMeta done = store.createInstance(defDone, Map.of());
        store.saveInstanceMeta(withStatus(done, InstanceStatus.DONE));

        // 新 store 模拟 worker 重启
        WorkflowStore restarted = new WorkflowStore(new FakeWorkspaces(home));
        WorkflowStore.ScanReport report = restarted.scanOnActivate();

        assertThat(report.definitions()).isEqualTo(2);
        assertThat(report.instances()).isEqualTo(4);
        assertThat(report.interrupted()).isEqualTo(3); // running + waiting-user + created

        InstanceMeta failed = restarted.findInstance(running.id()).orElseThrow();
        assertThat(failed.status()).isEqualTo(InstanceStatus.FAILED);
        assertThat(failed.error()).isNotNull();
        assertThat(failed.error().message()).isEqualTo(WorkflowStore.INTERRUPT_MESSAGE);
        assertThat(failed.endedAt()).isNotNull();
        List<EventLine> events = restarted.readEvents(WS_A, running.id(), 0, 0);
        assertThat(events).extracting(EventLine::event).contains("wf.created", "wf.status");
        EventLine status = events.stream().filter(e -> e.event().equals("wf.status")).findFirst().orElseThrow();
        assertThat(status.payload().path("status").asString()).isEqualTo("failed");
        assertThat(status.payload().path("error").path("message").asString())
                .isEqualTo(WorkflowStore.INTERRUPT_MESSAGE);

        // 终态实例不动
        assertThat(restarted.findInstance(done.id()).orElseThrow().status()).isEqualTo(InstanceStatus.DONE);
        assertThat(restarted.readEvents(WS_A, done.id(), 0, 0))
                .extracting(EventLine::event).containsExactly("wf.created");
        // 索引已建：列表可用
        assertThat(restarted.listDefinitions(WS_A)).hasSize(2);
    }

    @Test
    void scanToleratesCorruptFilesWithoutCrashing() throws Exception {
        Definition ok = store.saveDefinition(WS_A, sampleDef());
        // 坏 definition.json：手工造目录
        Path badDefDir = workflowsRoot(WS_A).resolve("definitions").resolve("wf_bad");
        Files.createDirectories(badDefDir);
        Files.writeString(badDefDir.resolve("definition.json"), "{ not json");
        // 坏 meta.json
        Path badInstanceDir = workflowsRoot(WS_A).resolve("instances").resolve("wi_bad");
        Files.createDirectories(badInstanceDir);
        Files.writeString(badInstanceDir.resolve("meta.json"), "{ broken");

        WorkflowStore restarted = new WorkflowStore(new FakeWorkspaces(home));
        WorkflowStore.ScanReport report = restarted.scanOnActivate(); // 不抛
        assertThat(report.definitions()).isEqualTo(1); // 只索引好的
        assertThat(report.instances()).isZero();
        assertThat(restarted.listDefinitions()).extracting(Definition::id).containsExactly(ok.id());
    }

    // ── 工作区缺失标记 ──

    @Test
    void workspaceRegistrationMarking() {
        store.refreshWorkspaceRegistry();
        assertThat(store.isWorkspaceRegistered(WS_A)).isTrue();
        assertThat(store.isWorkspaceRegistered("ws_ghost")).isFalse();
        // 注销后（模拟工作区根移除）标记翻转
        FakeWorkspaces shrink = new FakeWorkspaces(home);
        WorkflowStore s2 = new WorkflowStore(shrink);
        s2.scanOnActivate();
        assertThat(s2.isWorkspaceRegistered(WS_A)).isFalse();
    }

    // ── runs ──

    @Test
    void runRecordsSortedByRunNoAscendingWithPaddedFilenames() {
        Definition def = store.saveDefinition(WS_A, sampleDef());
        InstanceMeta meta = store.createInstance(def, Map.of());
        RunRecord r3 = runRec(3, "n1");
        RunRecord r1 = runRec(1, "n2");
        RunRecord r2 = runRec(2, "n1");
        store.saveRunRecord(WS_A, meta.id(), r3);
        store.saveRunRecord(WS_A, meta.id(), r1);
        store.saveRunRecord(WS_A, meta.id(), r2);
        List<RunRecord> list = store.listRunRecords(WS_A, meta.id());
        assertThat(list).extracting(RunRecord::runNo).containsExactly(1, 2, 3);
        assertThat(workflowsRoot(WS_A).resolve("instances").resolve(meta.id()).resolve("runs")
                .resolve("0003_n1.json")).isRegularFile();
        assertThat(list.get(0)).isEqualTo(r1);
    }

    private RunRecord runRec(int runNo, String nodeId) {
        return new RunRecord(runNo, nodeId, "节点" + runNo, "ai",
                1L, 2L, 1L, null, RunRecord.RunOutput.ai("out"), List.of(), null, null, null);
    }

    // ── events / agents jsonl 容错 ──

    @Test
    void eventReadFiltersByAfterSeqAndLimit() {
        Definition def = store.saveDefinition(WS_A, sampleDef());
        InstanceMeta meta = store.createInstance(def, Map.of());
        EventLine e1 = store.appendEvent(WS_A, meta.id(), "node.started", Json.obj().put("nodeId", "n1"));
        EventLine e2 = store.appendEvent(WS_A, meta.id(), "node.output", Json.obj().put("nodeId", "n1"));
        EventLine e3 = store.appendEvent(WS_A, meta.id(), "wf.done", Json.obj());
        assertThat(store.readEvents(WS_A, meta.id(), 0, 0)).hasSize(4); // 含 wf.created
        assertThat(store.readEvents(WS_A, meta.id(), e1.seqValue(), 0))
                .extracting(EventLine::event).containsExactly("node.output", "wf.done");
        assertThat(store.readEvents(WS_A, meta.id(), 0, 2)).hasSize(2);
        // 内存尾部
        assertThat(store.tailEvents(WS_A, meta.id(), 2)).hasSize(2);
    }

    // ── 变更监听（worker 级插件通知频道的信号源，架构 §5.2） ──

    @Test
    void changeListenerFiresOnEverySuccessfulMutationOnly() {
        List<String> events = new java.util.ArrayList<>();
        store.addChangeListener((kind, action, ws, id) -> events.add(kind + ":" + action + ":" + ws + ":" + id));

        Definition created = store.saveDefinition(WS_A, sampleDef());
        Definition updated = store.saveDefinition(WS_A,
                new Definition(created.schemaVersion(), created.id(), created.name(), "改描述", created.workspaceId(),
                        created.createdAt(), created.updatedAt(), created.variables(), created.agentConfigId(),
                        created.onError(),
                        created.supervisor(), created.nodes(), created.edges()));
        assertThat(events).containsExactly(
                "DEFINITION:CREATED:" + WS_A + ":" + created.id(),
                "DEFINITION:UPDATED:" + WS_A + ":" + created.id());
        events.clear();

        InstanceMeta meta = store.createInstance(updated, Map.of());
        store.saveInstanceMeta(withStatus(meta, InstanceStatus.RUNNING));
        assertThat(events).containsExactly(
                "INSTANCE:CREATED:" + WS_A + ":" + meta.id(),
                "INSTANCE:UPDATED:" + WS_A + ":" + meta.id());
        events.clear();

        // 失败路径不回调：删除不存在 / 运行中实例拒绝删除 / 校验失败的定义
        assertThat(store.deleteDefinition(WS_B, created.id())).isFalse();
        assertThatThrownBy(() -> store.deleteInstance(WS_A, meta.id())).isInstanceOf(BadParamsException.class);

        assertThat(events).isEmpty();

        // 删除成功回调；监听器抛错不影响存储路径也不打断其余监听器
        store.addChangeListener((kind, action, ws, id) -> {
            throw new IllegalStateException("监听器故意抛错");
        });
        assertThat(store.saveInstanceMeta(withStatus(meta, InstanceStatus.DONE)).status()).isEqualTo(InstanceStatus.DONE);
        assertThat(store.deleteInstance(WS_A, meta.id())).isTrue();
        assertThat(store.deleteDefinition(WS_A, created.id())).isTrue();
        assertThat(events).containsExactly(
                "INSTANCE:UPDATED:" + WS_A + ":" + meta.id(),
                "INSTANCE:DELETED:" + WS_A + ":" + meta.id(),
                "DEFINITION:DELETED:" + WS_A + ":" + created.id());
    }

    // ── 实例事件监听（实例流频道扇出的信号源） ──

    @Test
    void instanceEventListenerFiresOnAppendInOrderAndSurvivesListenerErrors() {
        InstanceMeta meta = store.createInstance(store.saveDefinition(WS_A, sampleDef()), Map.of());
        List<String> seen = new java.util.ArrayList<>();
        store.addInstanceEventListener((ws, wiId, line) ->
                seen.add(ws + ":" + wiId + ":" + line.event() + ":" + line.seq()));
        // 先注册一个会抛错的监听器：异常被吞掉，不影响存储路径与后续监听器
        store.addInstanceEventListener((ws, wiId, line) -> {
            throw new IllegalStateException("监听器故意抛错");
        });

        EventLine first = store.appendEvent(WS_A, meta.id(), "node.started", Json.obj().put("nodeId", "wn_a"));
        EventLine second = store.appendEvent(WS_A, meta.id(), "wf.status", Json.obj().put("status", "running"));

        assertThat(seen).containsExactly(
                WS_A + ":" + meta.id() + ":node.started:" + first.seq(),
                WS_A + ":" + meta.id() + ":wf.status:" + second.seq());
        // 事件确实落盘（监听器在成功落盘后回调；createInstance 自带的 wf.created 也应在盘）
        assertThat(store.readEvents(WS_A, meta.id(), 0, 10)).extracting(EventLine::seq)
                .endsWith(first.seq(), second.seq());
        // seq 单实例内严格递增（前端去重合流的依赖）
        assertThat(Long.parseLong(second.seq())).isGreaterThan(Long.parseLong(first.seq()));
    }
}
