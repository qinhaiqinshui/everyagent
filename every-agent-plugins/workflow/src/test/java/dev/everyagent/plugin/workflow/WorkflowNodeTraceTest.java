// ea: wf.node 任务 trace 单测（非 AI 节点过程呈现契约，§7.20.8——固定 id upsert / 全量 input-output-error / AI 与无绑定不发）
package dev.everyagent.plugin.workflow;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.model.EmitEvent;
import dev.everyagent.plugin.api.model.ModelConfig;
import dev.everyagent.plugin.workflow.engine.WorkflowEngine;
import dev.everyagent.plugin.workflow.model.Definition;
import dev.everyagent.plugin.workflow.model.EventLine;
import dev.everyagent.plugin.workflow.model.InstanceMeta;
import dev.everyagent.plugin.workflow.model.InstanceStatus;
import dev.everyagent.plugin.workflow.model.NodeDef;
import dev.everyagent.plugin.workflow.model.OnError;
import dev.everyagent.plugin.workflow.store.WorkflowStore;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * wf.node trace 契约（计划「四」钉死项）：
 * 命令节点 started(running)→done/failed 同 EmitEvent.id 重发（任务 emitter 单口）；
 * data 携带 input/output/error 全量 + status/durationMs/activatedEdges/exitCode；
 * persist + REPLACE；AI 节点不发；无任务绑定运行不发。
 */
class WorkflowNodeTraceTest {

    @TempDir
    Path home;

    FakeWorkspaces workspaces;
    WorkflowStore store;
    Fakes.FakeAgentFactory factory;
    Fakes.FakeWorkerServices services;
    WorkflowEngine engine;
    private final java.util.concurrent.atomic.AtomicInteger taskSeq = new java.util.concurrent.atomic.AtomicInteger();

    /** 最近一次 startBound 的实例元数据与任务桩（断言用）。 */
    private InstanceMeta m;
    private Fakes.FakeTaskExec task;

    @BeforeEach
    void setUp() throws Exception {
        workspaces = new FakeWorkspaces(home);
        java.nio.file.Files.createDirectories(home.resolve("proj"));
        workspaces.register(home.resolve("proj"), "ws_run");
        factory = new Fakes.FakeAgentFactory();
        services = new Fakes.FakeWorkerServices(workspaces, new Fakes.RealNativeExec(), factory,
                List.of(new ModelConfig("gpt5", "openai", "http://localhost", null, "gpt-5", null)));
        store = new WorkflowStore(workspaces);
        engine = new WorkflowEngine(store, services);
    }

    // ── 构造工具 ──

    private Definition save(Object... parts) {
        return store.saveDefinition("ws_run", DefinitionValidatorTest.def(parts));
    }

    private Definition saveOnError(OnError onError, Object... parts) {
        Definition d = DefinitionValidatorTest.def(parts);
        return store.saveDefinition("ws_run", new Definition(d.schemaVersion(), d.id(), d.name(),
                d.description(), d.workspaceId(), d.createdAt(), d.updatedAt(), d.variables(),
                onError, d.supervisor(), d.nodes(), d.edges()));
    }

    /** 绑定任务桩启动并等终态（每实例一个 origin 任务，任务 ID 全局递增）。 */
    private void startBound(Definition d, Map<String, String> vars) throws InterruptedException {
        task = new Fakes.FakeTaskExec(home.resolve("workspaces"), "ws_run",
                "task_" + taskSeq.incrementAndGet(), factory);
        m = engine.start(store.createInstance(d, vars, task.subjectId()), task);
        assertThat(engine.awaitTerminal(m.id(), 20_000)).as("实例终态").isTrue();
    }

    private List<EmitEvent> wfNodeTraces() {
        return task.emitted.stream()
                .filter(e -> WorkflowEngine.WF_NODE_TRACE_KIND.equals(e.kind())).toList();
    }

    /** 按 data.nodeId 过滤（多命令节点场景按行归属断言）。 */
    private List<EmitEvent> tracesOf(String nodeId) {
        return wfNodeTraces().stream()
                .filter(e -> ((tools.jackson.databind.node.ObjectNode) Json.toJson(e.data()))
                        .path("nodeId").asString().equals(nodeId))
                .toList();
    }

    private static tools.jackson.databind.node.ObjectNode dataOf(EmitEvent e) {
        return (tools.jackson.databind.node.ObjectNode) Json.toJson(e.data());
    }

    /** 实例当前状态（磁盘真相源——start 返回的 meta 是启动时快照，终态后已过期）。 */
    private InstanceStatus status() {
        return store.findInstance(m.id()).orElseThrow().status();
    }

    /** 替换定义中的同名节点（N 构造器无 timeoutMs 槽位，慢命令节点直接换 NodeDef）。 */
    private static Definition withNode(Definition d, NodeDef replacement) {
        List<NodeDef> nodes = new java.util.ArrayList<>();
        for (NodeDef n : d.nodesOrEmpty()) {
            nodes.add(n.id().equals(replacement.id()) ? replacement : n);
        }
        return new Definition(d.schemaVersion(), d.id(), d.name(), d.description(), d.workspaceId(),
                d.createdAt(), d.updatedAt(), d.variables(), d.onError(), d.supervisor(), nodes, d.edges());
    }

    // ══════════════════════════ 契约断言 ══════════════════════════

    @Test
    void commandNodeEmitsRunningThenDoneTraceWithSameId() throws Exception {
        Definition d = save(
                DefinitionValidatorTest.node("s", "start"),
                DefinitionValidatorTest.node("cmd", "command").command("echo hello"),
                DefinitionValidatorTest.node("t", "command").command("echo tail"),
                DefinitionValidatorTest.edge("e1", "s", "cmd"),
                DefinitionValidatorTest.edge("e2", "cmd", "t"));
        startBound(d, Map.of());
        assertThat(status()).isEqualTo(InstanceStatus.DONE);

        List<EmitEvent> traces = tracesOf("cmd");
        assertThat(traces).hasSize(2);
        EmitEvent running = traces.get(0);
        EmitEvent done = traces.get(1);

        // 固定 id upsert：started 生成、done 同 id 重发（前端 upsertTraceBySeq 原地更新一行）
        assertThat(running.id()).isPositive();
        assertThat(done.id()).isEqualTo(running.id());
        // persist + REPLACE（落 EventLog 且整体替换）
        for (EmitEvent e : List.of(running, done)) {
            assertThat(e.persist()).as("persist").isTrue();
            assertThat(e.mode()).as("mode").isEqualTo(EmitEvent.Mode.REPLACE);
            assertThat(e.agentId()).as("归主线（agentId 空 → 任务层兜底 mainAgentId）").isNull();
            assertThat(e.title()).isEqualTo("cmd");
        }
        // 状态与 summary 形态（<节点类型> · <状态> · <耗时>）
        assertThat(running.status()).isEqualTo("running");
        assertThat(running.summary()).contains("命令").contains("运行中");
        assertThat(done.status()).isEqualTo("done");
        assertThat(done.summary()).contains("命令").contains("完成");
        assertThat(done.content()).asString().contains("hello"); // content 承载输出文本

        // running 轮 data 契约：input 全量、output/error 空、无 exitCode、激活边空
        var rd = dataOf(running);
        assertThat(rd.path("instanceId").asString()).isEqualTo(m.id());
        assertThat(rd.path("definitionName").asString()).isEqualTo("测试定义");
        assertThat(rd.path("nodeId").asString()).isEqualTo("cmd");
        assertThat(rd.path("nodeName").asString()).isEqualTo("cmd");
        assertThat(rd.path("nodeType").asString()).isEqualTo("command");
        assertThat(rd.path("runNo").asInt()).isEqualTo(2); // start=1、cmd=2（顺序链）
        assertThat(rd.path("input").asString()).isEqualTo("echo hello");
        assertThat(rd.path("output").asString()).isEmpty();
        assertThat(rd.path("error").asString()).isEmpty();
        assertThat(rd.path("durationMs").asLong()).isZero();
        assertThat(rd.path("exitCode").isMissingNode()).isTrue();
        assertThat(rd.path("activatedEdges").size()).isZero();

        // done 轮 data 契约：output 全量、exitCode、激活边
        var dd = dataOf(done);
        assertThat(dd.path("input").asString()).isEqualTo("echo hello");
        assertThat(dd.path("output").asString()).contains("hello"); // stdout 全量（无截断约定在后端侧）
        assertThat(dd.path("error").asString()).isEmpty();
        assertThat(dd.path("exitCode").asInt()).isZero();
        assertThat(dd.path("activatedEdges").size()).isEqualTo(1);
        assertThat(dd.path("activatedEdges").get(0).asString()).isEqualTo("e2");
        assertThat(dd.path("durationMs").asLong()).isGreaterThanOrEqualTo(0L);

        // 下游命令节点 t 同样成对发射（running→done）
        List<EmitEvent> tTraces = tracesOf("t");
        assertThat(tTraces).hasSize(2);
        assertThat(tTraces.get(1).id()).isEqualTo(tTraces.get(0).id());
        assertThat(tTraces.get(1).status()).isEqualTo("done");
        assertThat(dataOf(tTraces.get(1)).path("output").asString()).contains("tail");
    }

    @Test
    void commandFailureUpdatesTraceInPlaceWithFailedAndError() throws Exception {
        // 超时触发节点失败：Windows 命令节点默认经 PowerShell——用 PS 内建死循环（无外部
        // 子进程，destroyForcibly 即净，不留孤儿进程锁 @TempDir）；Unix 用 sleep
        boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
        NodeDef slow = new NodeDef("cmd", "command", "慢命令", 0.0, 0.0,
                win ? "for (;;) { }" : "sleep 30", 400L, null, null, null, null, null, null);
        Definition d = withNode(saveOnError(OnError.FAIL,
                DefinitionValidatorTest.node("s", "start"),
                DefinitionValidatorTest.node("cmd", "command").command("echo placeholder"),
                DefinitionValidatorTest.edge("e1", "s", "cmd")), slow);
        startBound(d, Map.of());
        assertThat(status()).isEqualTo(InstanceStatus.FAILED);

        List<EmitEvent> traces = tracesOf("cmd");
        assertThat(traces).hasSize(2);
        EmitEvent failed = traces.get(1);
        // 同 id 原地更新为 failed
        assertThat(failed.id()).isEqualTo(traces.get(0).id());
        assertThat(failed.status()).isEqualTo("failed");
        assertThat(failed.summary()).contains("命令").contains("失败");
        assertThat(failed.title()).isEqualTo("慢命令"); // 有名节点用展示名
        assertThat(failed.content()).asString().contains("超时");

        var fd = dataOf(failed);
        assertThat(fd.path("nodeName").asString()).isEqualTo("慢命令");
        assertThat(fd.path("input").asString()).contains(win ? "for (;;)" : "sleep");
        assertThat(fd.path("error").asString()).contains("超时"); // error 全量
        assertThat(fd.path("output").asString()).isEmpty();
        assertThat(fd.path("exitCode").isMissingNode()).isTrue();
        assertThat(fd.path("durationMs").asLong()).isGreaterThanOrEqualTo(300L); // 超时触发约 400ms
    }

    @Test
    void aiNodeDoesNotEmitWfNodeTrace() throws Exception {
        factory.script("writer", key -> Fakes.Script.echo("产出"));
        Definition d = save(
                DefinitionValidatorTest.node("s", "start"),
                DefinitionValidatorTest.node("n", "ai").agent("writer").input("做点事"),
                DefinitionValidatorTest.edge("e1", "s", "n"));
        startBound(d, Map.of());
        assertThat(status()).isEqualTo(InstanceStatus.DONE);

        // AI 节点过程=子 agent 会话条目（任务页已完整可见）→ 零 wf.node
        assertThat(wfNodeTraces()).isEmpty();
        // 对照：会话条目在场（指令轮走任务 emitter）
        assertThat(task.emitted.stream().anyMatch(e -> "user.message".equals(e.kind()))).isTrue();
    }

    @Test
    void unboundRunEmitsNoTraceAndStillCompletes() throws Exception {
        Definition d = save(
                DefinitionValidatorTest.node("s", "start"),
                DefinitionValidatorTest.node("cmd", "command").command("echo ok"),
                DefinitionValidatorTest.edge("e1", "s", "cmd"));
        InstanceMeta unbound = engine.start(store.createInstance(d, Map.of()));
        assertThat(engine.awaitTerminal(unbound.id(), 20_000)).isTrue();
        // 无绑定：无任务事件口可发（结构性不发）——运行照常完成、实例域事件不受影响
        assertThat(store.findInstance(unbound.id()).orElseThrow().status())
                .isEqualTo(InstanceStatus.DONE);
        List<String> names = store.readEvents("ws_run", unbound.id(), 0, 0).stream()
                .map(EventLine::event).toList();
        assertThat(names).containsSubsequence("node.started", "node.output", "wf.done");
    }

    @Test
    void refusedRescheduleEmitsStandaloneFailedTrace() throws Exception {
        // c 只许跑 1 次；t 完成后回边再激活 c → 拒绝调度（该 run 无 started 轮 → 新 id 单发 failed）
        Definition d = saveOnError(OnError.FAIL,
                DefinitionValidatorTest.node("s", "start"),
                DefinitionValidatorTest.node("c", "command").command("echo one").maxRuns(1),
                DefinitionValidatorTest.node("t", "command").command("echo two"),
                DefinitionValidatorTest.edge("e1", "s", "c"),
                DefinitionValidatorTest.edge("e2", "c", "t"),
                DefinitionValidatorTest.edge("e3", "t", "c"));
        startBound(d, Map.of());
        assertThat(status()).isEqualTo(InstanceStatus.FAILED);

        List<EmitEvent> traces = tracesOf("c");
        assertThat(traces).hasSize(3);
        assertThat(traces.get(0).status()).isEqualTo("running");
        assertThat(traces.get(1).status()).isEqualTo("done");
        assertThat(traces.get(1).id()).isEqualTo(traces.get(0).id());
        EmitEvent refused = traces.get(2);
        assertThat(refused.status()).isEqualTo("failed");
        // 拒绝调度的虚拟 runNo（runCount+1）与本节点实跑同号 → 按 nodeId:runNo 键命中既有行，
        // 同 id 原地翻转为 failed（trace id 契约按节点运行编号，不区分实跑/虚拟拒绝）
        assertThat(refused.id()).isEqualTo(traces.get(0).id());
        var rfd = dataOf(refused);
        assertThat(rfd.path("error").asString()).contains("最大运行次数");
        assertThat(rfd.path("runNo").asInt()).isEqualTo(2);
    }

    @Test
    void failCascadeFlipsInFlightNodeTraceToCancelled() throws Exception {
        // 失败级联中断在飞命令节点（wi_owr3 现场：并行慢分支仍在跑时兄弟失败收口）：
        // 收口扫描将 started(running) 的 trace 同 id 原地翻转为 cancelled（interrupted 标记），
        // 不残留永远「运行中」的 trace 行
        boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
        // 慢命令：Windows 命令节点默认经 PowerShell——用 PS 内建死循环（无外部子进程，销毁
        // 即净不留孤儿锁 @TempDir——fake 的 nativeExec 只杀直接子进程）；Unix 用 sleep
        NodeDef slow = new NodeDef("slow", "command", "慢命令", 0.0, 0.0,
                win ? "for (;;) { }" : "sleep 30", null, null, null, null, null, null, null);
        Definition base = saveOnError(OnError.FAIL,
                DefinitionValidatorTest.node("s", "start"),
                DefinitionValidatorTest.node("slow", "command").command("echo placeholder"),
                DefinitionValidatorTest.node("boom", "command").command("exit 3"),
                DefinitionValidatorTest.edge("e1", "s", "slow"),
                DefinitionValidatorTest.edge("e2", "s", "boom"));
        startBound(withNode(base, slow), Map.of());
        assertThat(status()).isEqualTo(InstanceStatus.FAILED);

        List<EmitEvent> traces = tracesOf("slow");
        assertThat(traces).hasSize(2);
        assertThat(traces.get(0).status()).isEqualTo("running");
        EmitEvent cancelled = traces.get(1);
        assertThat(cancelled.status()).isEqualTo("cancelled");
        // 同 id 原地翻转（started 轮已注册 nodeId:runNo 键）
        assertThat(cancelled.id()).isEqualTo(traces.get(0).id());
        assertThat(cancelled.summary()).contains("已中断");
        assertThat(cancelled.content()).asString().contains("中断");
        var cd = dataOf(cancelled);
        assertThat(cd.path("interrupted").asBoolean()).isTrue();
        assertThat(cd.path("error").asString()).contains("中断");
        assertThat(cd.path("exitCode").isMissingNode()).isTrue();
        // 实例域：关闭事件 + 终态投影零 running
        assertThat(store.readEvents("ws_run", m.id(), 0, 0).stream()
                .map(EventLine::event).toList()).contains("node.interrupted");
        assertThat(store.findInstance(m.id()).orElseThrow().nodeStates()
                .get("slow").status()).isEqualTo("cancelled");
    }
}
