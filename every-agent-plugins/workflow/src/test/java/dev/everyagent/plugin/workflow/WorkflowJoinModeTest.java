// ea: 节点级汇合屏障(joinMode=all)引擎语义测试——等齐触发一次/部分到达不触发/
// 整图静默未齐静默跳过/回指循环每轮重新等齐(全部走真实引擎,命令节点真执行)
package dev.everyagent.plugin.workflow;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.everyagent.plugin.workflow.engine.WorkflowEngine;
import dev.everyagent.plugin.workflow.model.ConditionDef;
import dev.everyagent.plugin.workflow.model.Definition;
import dev.everyagent.plugin.workflow.model.EventLine;
import dev.everyagent.plugin.workflow.model.InstanceMeta;
import dev.everyagent.plugin.workflow.model.InstanceStatus;
import dev.everyagent.plugin.workflow.model.RunRecord;
import dev.everyagent.plugin.workflow.store.WorkflowStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * joinMode 汇合屏障四组核心场景（契约：all = 等齐全部入边各激活一次 → 触发一次执行）：
 * ①等齐触发一次（maxRuns=1 即可，无需按入边数手算）；②部分到达不触发；③整图静默
 * 未齐 → join 置 skipped + 实例正常收束 done（与死分支不阻塞收束同语义）；④回指循环
 * 每轮重新等齐（第二轮对端未重跑 → 等不齐 → 不再执行）。
 */
class WorkflowJoinModeTest {

    private static final ConditionDef ON_SUCCESS =
            new ConditionDef(ConditionDef.ON_SUCCESS, null, null, null);
    private static final ConditionDef ON_ERROR =
            new ConditionDef(ConditionDef.ON_ERROR, null, null, null);

    @TempDir
    Path home;

    FakeWorkspaces workspaces;
    WorkflowStore store;
    Fakes.FakeAgentFactory factory;
    Fakes.FakeWorkerServices services;
    WorkflowEngine engine;
    private String latest;

    @BeforeEach
    void setUp() throws Exception {
        workspaces = new FakeWorkspaces(home);
        // 命令节点 cwd=工作区根：目录须真实存在（与 WorkflowEngineTest 同口径）
        Files.createDirectories(home.resolve("proj"));
        workspaces.register(home.resolve("proj"), "ws_run");
        factory = new Fakes.FakeAgentFactory();
        services = new Fakes.FakeWorkerServices(workspaces, new Fakes.RealNativeExec(), factory,
                List.of());
        store = new WorkflowStore(workspaces);
        engine = new WorkflowEngine(store, services);
    }

    // ── 构造工具 ──

    private Definition save(Object... parts) {
        return store.saveDefinition("ws_run", DefinitionValidatorTest.def(parts));
    }

    private InstanceMeta start(Definition d) {
        return engine.start(store.createInstance(d, Map.of()));
    }

    private List<EventLine> events(String instanceId) {
        return store.readEvents("ws_run", instanceId, 0, 0);
    }

    private List<String> eventNames(String instanceId) {
        return events(instanceId).stream().map(EventLine::event).toList();
    }

    private List<RunRecord> runs(String instanceId) {
        return store.listRunRecords("ws_run", instanceId);
    }

    private List<String> startedNodes(String instanceId) {
        return events(instanceId).stream()
                .filter(e -> "node.started".equals(e.event()))
                .map(e -> e.payload().path("nodeId").asString())
                .toList();
    }

    private InstanceMeta meta(String instanceId) {
        return store.findInstance(instanceId).orElseThrow();
    }

    private void awaitTerminal(String instanceId) throws InterruptedException {
        latest = instanceId;
        if (!engine.awaitTerminal(instanceId, 20_000)) {
            fail("超时等待实例终态;事件=" + eventNames(instanceId));
        }
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static String echo(String text) {
        return "echo " + text;
    }

    /** 追加一行到 cwd 下文件（Windows 命令节点经 PowerShell；Unix /bin/sh 重定向）。 */
    private static String appendLine(String file, String line) {
        return windows()
                ? "Add-Content -Path '" + file + "' -Value '" + line + "'"
                : "echo " + line + " >> " + file;
    }

    /** 工作区根下文件中 marker 出现次数（文件不存在=0）。 */
    private int markerCount(String file, String marker) throws java.io.IOException {
        Path f = home.resolve("proj").resolve(file);
        if (!Files.isRegularFile(f)) {
            return 0;
        }
        String content = Files.readString(f);
        int count = 0;
        for (int i = content.indexOf(marker); i >= 0;
                i = content.indexOf(marker, i + marker.length())) {
            count++;
        }
        return count;
    }

    // ══════════════════════════ ① 等齐触发一次 ══════════════════════════

    @Test
    void joinAllTriggersExactlyOnceWhenAllEdgesArrive() throws Exception {
        // 4 个 command 前驱（onSuccess 边）全部到达 → join(all, maxRuns=1) 恰好触发一次执行
        Definition d = save(
                DefinitionValidatorTest.node("s", "start"),
                DefinitionValidatorTest.node("p1", "command").command(echo("p1")),
                DefinitionValidatorTest.node("p2", "command").command(echo("p2")),
                DefinitionValidatorTest.node("p3", "command").command(echo("p3")),
                DefinitionValidatorTest.node("p4", "command").command(echo("p4")),
                DefinitionValidatorTest.node("j", "command")
                        .command(appendLine("join1.txt", "hit")).maxRuns(1).joinMode("all"),
                DefinitionValidatorTest.edge("g1", "s", "p1"),
                DefinitionValidatorTest.edge("g2", "s", "p2"),
                DefinitionValidatorTest.edge("g3", "s", "p3"),
                DefinitionValidatorTest.edge("g4", "s", "p4"),
                DefinitionValidatorTest.edge("j1", "p1", "j", ON_SUCCESS),
                DefinitionValidatorTest.edge("j2", "p2", "j", ON_SUCCESS),
                DefinitionValidatorTest.edge("j3", "p3", "j", ON_SUCCESS),
                DefinitionValidatorTest.edge("j4", "p4", "j", ON_SUCCESS));
        InstanceMeta m = start(d);
        awaitTerminal(m.id());
        assertThat(meta(m.id()).status())
                .as("事件=" + eventNames(m.id()) + ";runs=" + runs(m.id()))
                .isEqualTo(InstanceStatus.DONE);
        // join 恰好 1 条 runs 记录、runCount=1、文件只追加 1 行——maxRuns=1 即可
        // （旧语义下 4 条入边各触发一次，maxRuns 须手算成 4；这正是本功能解决的痛点）
        assertThat(runs(m.id()).stream().filter(r -> "j".equals(r.nodeId()))).hasSize(1);
        assertThat(meta(m.id()).nodeStates().get("j").runCount()).isEqualTo(1);
        assertThat(meta(m.id()).nodeStates().get("j").status()).isEqualTo("done");
        assertThat(markerCount("join1.txt", "hit")).isEqualTo(1);
        // 4 前驱各自 done
        for (String p : List.of("p1", "p2", "p3", "p4")) {
            assertThat(meta(m.id()).nodeStates().get(p).status()).isEqualTo("done");
        }
    }

    // ══════════════════════════ ②/③ 部分到达：不触发 + 静默跳过收束 ══════════════════════════

    /** 4 前驱之一失败被 onError 接走到别处（不达 join），其余 3 条到达 → join 等不齐的拓扑。 */
    private Definition partialArrivalDef() {
        return save(
                DefinitionValidatorTest.node("s", "start"),
                DefinitionValidatorTest.node("p1", "command").command("exit 1"),
                DefinitionValidatorTest.node("p2", "command").command(echo("p2")),
                DefinitionValidatorTest.node("p3", "command").command(echo("p3")),
                DefinitionValidatorTest.node("p4", "command").command(echo("p4")),
                DefinitionValidatorTest.node("rescue", "command").command(echo("rescued")),
                DefinitionValidatorTest.node("j", "command")
                        .command(appendLine("join2.txt", "hit")).maxRuns(10).joinMode("all"),
                DefinitionValidatorTest.edge("g1", "s", "p1"),
                DefinitionValidatorTest.edge("g2", "s", "p2"),
                DefinitionValidatorTest.edge("g3", "s", "p3"),
                DefinitionValidatorTest.edge("g4", "s", "p4"),
                DefinitionValidatorTest.edge("r1", "p1", "rescue", ON_ERROR),
                DefinitionValidatorTest.edge("j1", "p1", "j", ON_SUCCESS), // 失败不激活 → 永不达
                DefinitionValidatorTest.edge("j2", "p2", "j", ON_SUCCESS),
                DefinitionValidatorTest.edge("j3", "p3", "j", ON_SUCCESS),
                DefinitionValidatorTest.edge("j4", "p4", "j", ON_SUCCESS));
    }

    @Test
    void joinNotTriggeredOnPartialArrival() throws Exception {
        // 4 前驱之一失败被 onError 接走（不达 join），其余 3 条到达：join 不执行
        InstanceMeta m = start(partialArrivalDef());
        awaitTerminal(m.id());
        assertThat(meta(m.id()).status())
                .as("事件=" + eventNames(m.id())).isEqualTo(InstanceStatus.DONE);
        // join 不执行：0 条 runs、无 node.started、文件无追加
        assertThat(runs(m.id()).stream().filter(r -> "j".equals(r.nodeId()))).isEmpty();
        assertThat(startedNodes(m.id())).doesNotContain("j");
        assertThat(markerCount("join2.txt", "hit")).isZero();
    }

    @Test
    void strandedJoinSkippedAndInstanceConvergesDone() throws Exception {
        // 整图静默（无 running、无挂起决策、无非 join 滞留激活）→ 未齐 join 置 skipped
        // 终态 + node.skipped 事件留痕 + 实例正常收束 done（与死分支不阻塞收束同语义）
        InstanceMeta m = start(partialArrivalDef());
        awaitTerminal(m.id());
        assertThat(meta(m.id()).status()).isEqualTo(InstanceStatus.DONE);
        assertThat(meta(m.id()).nodeStates().get("j").status()).isEqualTo("skipped");
        assertThat(meta(m.id()).nodeStates().get("j").runCount()).isZero();
        var skipEv = events(m.id()).stream()
                .filter(e -> "node.skipped".equals(e.event())
                        && "j".equals(e.payload().path("nodeId").asString()))
                .findFirst().orElseThrow();
        assertThat(skipEv.payload().path("message").asString()).contains("等齐");
        // 其余分支正常：3 个到达前驱 done、失败前驱 failed（被 onError 接走）、rescue done
        assertThat(meta(m.id()).nodeStates().get("p1").status()).isEqualTo("failed");
        assertThat(meta(m.id()).nodeStates().get("p2").status()).isEqualTo("done");
        assertThat(meta(m.id()).nodeStates().get("p3").status()).isEqualTo("done");
        assertThat(meta(m.id()).nodeStates().get("p4").status()).isEqualTo("done");
        assertThat(meta(m.id()).nodeStates().get("rescue").status()).isEqualTo("done");
        assertThat(startedNodes(m.id())).doesNotContain("j");
    }

    // ══════════════════════════ ④ 回指循环每轮重新等齐 ══════════════════════════

    @Test
    void joinReWaitsEachCycleInLoopBack() throws Exception {
        // A、B 汇入 join(all)；join 出边 outputContains 路由回 A 一轮：第一轮 join 触发后
        // A 重跑，A 的边再次到达 join，但 B 未重跑 → 第二轮等不齐 → join 不再执行，实例收束
        Definition d = save(
                DefinitionValidatorTest.node("s", "start"),
                DefinitionValidatorTest.node("a", "command").command(echo("A")).maxRuns(2),
                DefinitionValidatorTest.node("b", "command").command(echo("B")).maxRuns(1),
                DefinitionValidatorTest.node("j", "command").command(echo("RETRY")).maxRuns(10)
                        .joinMode("all"),
                DefinitionValidatorTest.edge("e1", "s", "a"),
                DefinitionValidatorTest.edge("e2", "s", "b"),
                DefinitionValidatorTest.edge("ja", "a", "j", ON_SUCCESS),
                DefinitionValidatorTest.edge("jb", "b", "j", ON_SUCCESS),
                DefinitionValidatorTest.edge("back", "j", "a",
                        new ConditionDef("outputContains", null, "RETRY", null)));
        InstanceMeta m = start(d);
        awaitTerminal(m.id());
        assertThat(meta(m.id()).status())
                .as("事件=" + eventNames(m.id())).isEqualTo(InstanceStatus.DONE);
        // join 恰好执行 1 次（第一轮等齐触发；第二轮等不齐不再触发——maxRuns=10 证明
        // 拦住它的是汇合屏障而非循环上限）
        assertThat(runs(m.id()).stream().filter(r -> "j".equals(r.nodeId()))).hasSize(1);
        assertThat(meta(m.id()).nodeStates().get("j").runCount()).isEqualTo(1);
        // A 恰好跑 2 次（初始 + 回指一轮），B 恰好 1 次
        assertThat(meta(m.id()).nodeStates().get("a").runCount()).isEqualTo(2);
        assertThat(meta(m.id()).nodeStates().get("b").runCount()).isEqualTo(1);
    }
}
