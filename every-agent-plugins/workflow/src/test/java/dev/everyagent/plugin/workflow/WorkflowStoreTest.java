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
import dev.everyagent.plugin.workflow.model.AgentEventLine;
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
        // 引用即声明的 agent 已合并落盘
        Definition disk = Json.MAPPER.readValue(file.toFile(), Definition.class);
        assertThat(disk.agentsOrEmpty()).extracting(a -> a.name()).containsExactly("writer");
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
        assertThat(imported.agentsOrEmpty()).extracting(a -> a.name()).containsExactly("writer");
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
        InstanceMeta meta = store.createInstance(def, Map.of("novelName", "星际牧羊人"));
        assertThat(meta.id()).startsWith("wi_");
        assertThat(meta.status()).isEqualTo(InstanceStatus.CREATED);
        assertThat(meta.definitionSnapshot()).isEqualTo(def);
        // 快照独立：深拷贝经 JSON 往返（不同对象图），原定义引用不串扰实例
        InstanceMeta reread = store.findInstance(meta.id()).orElseThrow();
        assertThat(reread.definitionSnapshot().nodesOrEmpty())
                .isNotSameAs(def.nodesOrEmpty())
                .hasSize(2);
        // 目录布局
        Path dir = workflowsRoot(WS_A).resolve("instances").resolve(meta.id());
        assertThat(dir.resolve("meta.json")).isRegularFile();
        assertThat(dir.resolve("runs")).isDirectory();
        assertThat(dir.resolve("agents")).isDirectory();
        // wf.created 事件
        List<EventLine> events = store.readEvents(WS_A, meta.id(), 0, 0);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).event()).isEqualTo("wf.created");
        assertThat(events.get(0).payload().path("instanceId").asString()).isEqualTo(meta.id());
        assertThat(events.get(0).payload().path("variables").path("novelName").asString()).isEqualTo("星际牧羊人");
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
                m.workspaceId(), m.definitionSnapshot(), m.variables(), status,
                m.createdAt(), m.startedAt(), m.endedAt(), m.durationMs(),
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

    @Test
    void agentJsonlAppendOnlyAndCorruptLineSkipped() throws Exception {
        Definition def = store.saveDefinition(WS_A, sampleDef());
        InstanceMeta meta = store.createInstance(def, Map.of());
        AgentEventLine l1 = store.appendAgentEvent(WS_A, meta.id(), "writer",
                "user.message", "writer#" + meta.id(), Json.obj().put("content", "拟大纲"));
        store.appendAgentEvent(WS_A, meta.id(), "writer",
                "message", "writer#" + meta.id(), Json.obj().put("text", "大纲如下"));
        Path file = workflowsRoot(WS_A).resolve("instances").resolve(meta.id())
                .resolve("agents").resolve("writer.jsonl");
        List<String> lines = Files.readAllLines(file);
        assertThat(lines).hasSize(2); // 追加不重写
        assertThat(l1.agentId()).isEqualTo("writer#" + meta.id());

        Files.writeString(file, "{ broken line\n", java.nio.file.StandardOpenOption.APPEND);
        List<AgentEventLine> back = store.readAgentEvents(WS_A, meta.id(), "writer");
        assertThat(back).hasSize(2); // 坏行跳过
        assertThat(back.get(0).event()).isEqualTo("user.message");
    }
}
