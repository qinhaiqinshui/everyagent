// ea: workflow_rerun 测试（失败实例从中断节点续跑/runNo 延续/已完成节点不重跑/任务档案会话恢复/input 快照持久化/幂等链/守卫文案/无绑定命令流/级联取消；桩注入不依赖 worker，§14.9 红线）
package dev.everyagent.plugin.workflow;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.exception.AgentCancelledException;
import dev.everyagent.plugin.api.exception.BadParamsException;
import dev.everyagent.plugin.api.model.ModelConfig;
import dev.everyagent.plugin.workflow.engine.WorkflowEngine;
import dev.everyagent.plugin.workflow.model.Definition;
import dev.everyagent.plugin.workflow.model.EventLine;
import dev.everyagent.plugin.workflow.model.InstanceMeta;
import dev.everyagent.plugin.workflow.model.InstanceStatus;
import dev.everyagent.plugin.workflow.store.WorkflowStore;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/**
 * workflow_rerun（§7.20 介入修复）：引擎 failed→running 重开 + 工具阻塞语义同 run。
 * 覆盖：失败节点重跑且已完成节点不重跑、runNo/runs/events 延续追加、命名 agent 会话
 * 从任务档案恢复、带 input 的节点输入替换（快照持久化到 meta.json）、再失败可再 rerun、
 * 非 failed 实例/origin 任务不一致或已收口/缺实例的守卫、无 originTaskId 纯命令流的
 * 无绑定 rerun、并行被中断节点重置 pending 不激活、指定任意节点重开、任务停止级联取消。
 */
class WorkflowRerunTest {

    @TempDir
    Path home;

    FakeWorkspaces workspaces;
    WorkflowStore store;
    Fakes.FakeAgentFactory factory;
    Fakes.FakeWorkerServices services;
    WorkflowEngine engine;
    WorkflowTools tools;
    Fakes.FakeTaskExec task;
    WorkflowRunToolTest.TaskToolContext ctx;

    @BeforeEach
    void setUp() throws Exception {
        workspaces = new FakeWorkspaces(home);
        Files.createDirectories(home.resolve("proj"));
        workspaces.register(home.resolve("proj"), "ws_run");
        factory = new Fakes.FakeAgentFactory();
        services = new Fakes.FakeWorkerServices(workspaces, new Fakes.RealNativeExec(), factory,
                List.of(new ModelConfig("gpt5", "openai", "http://localhost", null, "gpt-5", null)));
        store = new WorkflowStore(workspaces);
        engine = new WorkflowEngine(store, services);
        task = new Fakes.FakeTaskExec(home.resolve("workspaces"), "ws_run", "t_rerun_1", factory);
        ctx = new WorkflowRunToolTest.TaskToolContext(task, workspaces);
        tools = new WorkflowTools(store, engine, ctx);
    }

    // ── 构造工具 ──

    private Definition save(Object... parts) {
        return store.saveDefinition("ws_run", DefinitionValidatorTest.def(parts));
    }

    private void await(BooleanSupplier cond, String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (!cond.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                fail("超时等待: " + what);
            }
            Thread.sleep(20);
        }
    }

    private InstanceMeta meta(String instanceId) {
        return store.findInstance(instanceId).orElseThrow();
    }

    private List<String> eventNames(String instanceId) {
        return store.readEvents("ws_run", instanceId, 0, 0).stream()
                .map(EventLine::event).toList();
    }

    private List<EventLine> eventsOf(String instanceId, String name, String nodeId) {
        return store.readEvents("ws_run", instanceId, 0, 0).stream()
                .filter(e -> name.equals(e.event())
                        && (nodeId == null || nodeId.equals(e.payload().path("nodeId").asString())))
                .toList();
    }

    private List<dev.everyagent.plugin.workflow.model.RunRecord> runs(String instanceId) {
        return store.listRunRecords("ws_run", instanceId);
    }

    /** 工具跑一次到失败（writer 首次失败），返回失败实例 id。 */
    private String runToFailure() {
        AtomicInteger calls = new AtomicInteger();
        factory.script("writer", key -> calls.incrementAndGet() == 1
                ? Fakes.Script.failing(new IllegalStateException("第一次失败"))
                : Fakes.Script.echo("修复后产出"));
        Definition d = save(
                DefinitionValidatorTest.node("s", "start"),
                DefinitionValidatorTest.node("c1", "command").command("echo one"),
                DefinitionValidatorTest.node("n", "ai").agent("writer").maxRuns(10).input("做点事"),
                DefinitionValidatorTest.node("c3", "command").command("echo three"),
                DefinitionValidatorTest.edge("e1", "s", "c1"),
                DefinitionValidatorTest.edge("e2", "c1", "n"),
                DefinitionValidatorTest.edge("e3", "n", "c3"));
        JsonNode r = Json.parse(tools.workflow_run(d.id(), null, null));
        assertThat(r.path("status").asString()).isEqualTo("failed");
        assertThat(r.path("nodeId").asString()).isEqualTo("n");
        return r.path("instanceId").asString();
    }

    // ══════════════════════════ 从中断节点续跑 ══════════════════════════

    @Test
    void rerunContinuesFromFailedNodeWithRunNoContinuation() throws Exception {
        String id = runToFailure();
        // 首跑：1=s 2=c1 3=n(失败)；rerun 应从 n 续跑：4=n 5=c3
        assertThat(runs(id)).extracting(r -> r.runNo()).containsExactly(1, 2, 3);
        assertThat(meta(id).status()).isEqualTo(InstanceStatus.FAILED);

        JsonNode r = Json.parse(tools.workflow_rerun(id, null, null));
        assertThat(r.path("status").asString()).isEqualTo("done");
        assertThat(r.path("instanceId").asString()).isEqualTo(id);
        assertThat(r.path("nodes").size()).isEqualTo(4);

        // runNo 延续 + runs 文件名接续（0004_n.json / 0005_c3.json）
        assertThat(runs(id)).extracting(x -> x.runNo()).containsExactly(1, 2, 3, 4, 5);
        Path runsDir = home.resolve("workspaces/ws_run/workflows/instances")
                .resolve(id).resolve("runs");
        assertThat(Files.exists(runsDir.resolve("0004_n.json"))).isTrue();
        assertThat(Files.exists(runsDir.resolve("0005_c3.json"))).isTrue();
        assertThat(meta(id).runCounter()).isEqualTo(5);

        // 失败节点重跑；已完成节点（s/c1）不重跑
        assertThat(runs(id).stream().filter(x -> "c1".equals(x.nodeId()))).hasSize(1);
        assertThat(runs(id).stream().filter(x -> "n".equals(x.nodeId()))).hasSize(2);
        assertThat(runs(id).stream().filter(x -> "c3".equals(x.nodeId()))).hasSize(1);

        // events.jsonl 继续追加：failed 后再现 wf.status(running)、n 的第二次 node.started(runNo=4)
        List<String> names = eventNames(id);
        int failedIdx = -1;
        int rerunRunningIdx = -1;
        for (int i = 0; i < names.size(); i++) {
            if (!"wf.status".equals(names.get(i))) {
                continue;
            }
            if (store.readEvents("ws_run", id, 0, 0).get(i).payload().path("status")
                    .asString().equals("failed")) {
                failedIdx = i;
            }
            if (store.readEvents("ws_run", id, 0, 0).get(i).payload().path("status")
                    .asString().equals("running") && failedIdx >= 0 && rerunRunningIdx < 0) {
                rerunRunningIdx = i;
            }
        }
        assertThat(failedIdx).isGreaterThanOrEqualTo(0);
        assertThat(rerunRunningIdx).isGreaterThan(failedIdx);
        List<EventLine> nStarts = eventsOf(id, "node.started", "n");
        assertThat(nStarts).hasSize(2);
        assertThat(nStarts.get(1).payload().path("runNo").asInt()).isEqualTo(4);

        // runCount 延续不重置（防无限循环的预算跨 rerun；目标节点经决策级授权绕上限重开）
        assertThat(meta(id).nodeStates().get("n").runCount()).isEqualTo(2);
        assertThat(meta(id).nodeStates().get("c1").runCount()).isEqualTo(1);
        assertThat(meta(id).nodeStates().get("c1").status()).isEqualTo("done");
        assertThat(meta(id).error()).isNull();
        assertThat(meta(id).pendingDecision()).isNull();
    }

    @Test
    void rerunNamedAgentResumesConversationFromTaskArchive() throws Exception {
        String id = runToFailure();
        // 首跑失败已留指令轮在任务档案（无 message——模型调用失败）
        assertThat(task.agentLines("writer#" + id).stream().map(l -> l.event()).toList())
                .containsExactly("user.message");

        assertThat(Json.parse(tools.workflow_rerun(id, null, null))
                .path("status").asString()).isEqualTo("done");

        // 命名 agent 同 id 复用 + 上下文从任务档案重建：第二次装配的种子含首跑指令轮
        //（尾部孤立 user 由交替不变量补占位 assistant 轮——SubAgentManager 续跑同语义）
        List<Fakes.FakeAgent> writers = factory.built.stream()
                .filter(a -> a.agentId().equals("writer#" + id)).toList();
        assertThat(writers).hasSize(2);
        assertThat(writers.get(0).seed).isEmpty();
        assertThat(writers.get(1).seed).hasSize(2);
        assertThat(writers.get(1).seed.get(0).getText()).isEqualTo("做点事");
        assertThat(writers.get(1).seed.get(1).getText())
                .isEqualTo(dev.everyagent.plugin.workflow.engine.AgentSessions.NO_FINAL_ANSWER_PLACEHOLDER);
        assertThat(writers.get(1).userInput).isEqualTo("做点事");

        // 任务档案跨 rerun 连续追加（会话线单文件自包含）
        assertThat(task.agentLines("writer#" + id).stream().map(l -> l.event()).toList())
                .containsExactly("user.message", "user.message", "message", "usage");
    }

    @Test
    void rerunWithInputReplacesNodeInputAndPersistsSnapshot() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        factory.script("writer", key -> calls.incrementAndGet() == 1
                ? Fakes.Script.failing(new IllegalStateException("输入不当"))
                : Fakes.Script.echo("按新输入完成"));
        Definition d = save(
                DefinitionValidatorTest.node("s", "start"),
                DefinitionValidatorTest.node("n", "ai").agent("writer").maxRuns(10).input("原始输入"),
                DefinitionValidatorTest.edge("e1", "s", "n"));
        JsonNode first = Json.parse(tools.workflow_run(d.id(), null, null));
        assertThat(first.path("status").asString()).isEqualTo("failed");
        String id = first.path("instanceId").asString();

        assertThat(Json.parse(tools.workflow_rerun(id, null, "改用新输入"))
                .path("status").asString()).isEqualTo("done");

        // runs 留档：首跑原始输入、重跑新输入
        List<dev.everyagent.plugin.workflow.model.RunRecord> recs = runs(id).stream()
                .filter(r -> "n".equals(r.nodeId()))
                .sorted((a, b) -> a.runNo() - b.runNo()).toList();
        assertThat(recs).hasSize(2);
        assertThat(recs.get(0).input().rendered()).isEqualTo("原始输入");
        assertThat(recs.get(1).input().rendered()).isEqualTo("改用新输入");
        // 重跑实际收到新输入
        List<Fakes.FakeAgent> writers = factory.built.stream()
                .filter(a -> a.agentId().equals("writer#" + id)).toList();
        assertThat(writers.get(1).userInput).isEqualTo("改用新输入");
        // 替换后的快照持久化到 meta.json（后续再 rerun 读到的是新输入，不回退）
        Path metaFile = home.resolve("workspaces/ws_run/workflows/instances")
                .resolve(id).resolve("meta.json");
        InstanceMeta disk = Json.MAPPER.readValue(metaFile.toFile(), InstanceMeta.class);
        boolean persisted = disk.definitionSnapshot().nodesOrEmpty().stream()
                .anyMatch(n -> "n".equals(n.id()) && "改用新输入".equals(n.input()));
        assertThat(persisted).as("meta.json 快照应持久化替换后的输入").isTrue();
    }

    @Test
    void rerunFailsAgainCanBeRerunAgain() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        factory.script("writer", key -> calls.incrementAndGet() <= 2
                ? Fakes.Script.failing(new IllegalStateException("第" + calls.get() + "次失败"))
                : Fakes.Script.echo("第三次成功"));
        Definition d = save(
                DefinitionValidatorTest.node("s", "start"),
                DefinitionValidatorTest.node("n", "ai").agent("writer").maxRuns(10),
                DefinitionValidatorTest.edge("e1", "s", "n"));
        JsonNode first = Json.parse(tools.workflow_run(d.id(), null, null));
        String id = first.path("instanceId").asString();
        assertThat(first.path("status").asString()).isEqualTo("failed");

        // 第一次 rerun 再失败（幂等链中继）
        JsonNode second = Json.parse(tools.workflow_rerun(id, null, null));
        assertThat(second.path("status").asString()).isEqualTo("failed");
        assertThat(second.path("error").asString()).contains("第2次失败");
        assertThat(second.path("hint").asString()).contains("workflow_rerun");

        // 第二次 rerun 成功
        JsonNode third = Json.parse(tools.workflow_rerun(id, null, null));
        assertThat(third.path("status").asString()).isEqualTo("done");
        assertThat(runs(id).stream().filter(r -> "n".equals(r.nodeId()))).hasSize(3);
        assertThat(meta(id).nodeStates().get("n").runCount()).isEqualTo(3);
    }

    // ══════════════════════════ 守卫 ══════════════════════════

    @Test
    void rerunRejectsNonFailedOrMissingInstance() throws Exception {
        Definition ok = save(
                DefinitionValidatorTest.node("s", "start"),
                DefinitionValidatorTest.node("c", "command").command("echo ok"),
                DefinitionValidatorTest.edge("e1", "s", "c"));
        String doneId = Json.parse(tools.workflow_run(ok.id(), null, null))
                .path("instanceId").asString();

        // 已 done → 工具错误 + 引擎 BadParams
        assertThat(Json.parse(tools.workflow_rerun(doneId, null, null))
                .path("error").asString()).contains("仅 failed");
        assertThatThrownBy(() -> engine.rerun(doneId, null, null, task))
                .isInstanceOf(BadParamsException.class)
                .hasMessageContaining("仅 failed");

        // created（未启动）→ 同样拒绝
        Definition d = save(
                DefinitionValidatorTest.node("s", "start"),
                DefinitionValidatorTest.node("n", "ai").agent("writer").maxRuns(10),
                DefinitionValidatorTest.edge("e1", "s", "n"));
        InstanceMeta created = store.createInstance(d, null, task.subjectId());
        assertThat(Json.parse(tools.workflow_rerun(created.id(), null, null))
                .path("error").asString()).contains("仅 failed");

        // 缺实例 → NOT_FOUND / NotFoundException
        assertThat(Json.parse(tools.workflow_rerun("wi_nope", null, null))
                .path("error").asString()).contains("NOT_FOUND");
        assertThatThrownBy(() -> engine.rerun("wi_nope", null, null, null))
                .isInstanceOf(dev.everyagent.plugin.api.exception.NotFoundException.class);
    }

    @Test
    void rerunRequiresOriginTaskMatchAndAlive() throws Exception {
        String id = runToFailure();
        assertThat(meta(id).originTaskId()).isEqualTo("t_rerun_1");

        // 当前任务不是 origin 任务 → 明确文案（只能全新运行）
        Fakes.FakeTaskExec otherTask = new Fakes.FakeTaskExec(
                home.resolve("workspaces"), "ws_run", "t_other", factory);
        WorkflowTools otherTools = new WorkflowTools(store, engine,
                new WorkflowRunToolTest.TaskToolContext(otherTask, workspaces));
        String mismatch = Json.parse(otherTools.workflow_rerun(id, null, null))
                .path("error").asString();
        assertThat(mismatch).contains("原任务已结束/删除");
        assertThat(mismatch).contains("无法复用会话上下文");
        assertThat(mismatch).contains("全新运行");

        // origin 任务已收口（terminal）→ 同文案
        task.terminal = true;
        String dead = Json.parse(tools.workflow_rerun(id, null, null))
                .path("error").asString();
        assertThat(dead).contains("原任务已结束/删除");
        task.terminal = false;

        // 引擎级绑定不一致守卫（与 start 同款）
        assertThatThrownBy(() -> engine.rerun(id, null, null, otherTask))
                .isInstanceOf(BadParamsException.class)
                .hasMessageContaining("不一致");
        // 引擎级含 AI 节点 + 绑定不可用 → 拒绝
        assertThatThrownBy(() -> engine.rerun(id, null, null, null))
                .isInstanceOf(BadParamsException.class)
                .hasMessageContaining("AI");
    }

    // ══════════════════════════ 无 originTaskId 命令流 ══════════════════════════

    @Test
    void rerunWithoutOriginTaskIdWorksUnbound() throws Exception {
        // RPC 直启的纯命令流（无 AI 节点）：无绑定 rerun 可用；
        // 命令首次超时失败（Start-Sleep 无子进程，超时销毁不残留孤儿占用工作区目录）
        // → 放 marker 后重跑成功（Windows 命令节点默认经 PowerShell，用 PS 方言）
        Definition d = save(
                DefinitionValidatorTest.node("s", "start"),
                DefinitionValidatorTest.node("c2", "command")
                        .command("if (Test-Path go.marker) { echo fixed } else { Start-Sleep -Seconds 30 }")
                        .timeoutMs(800L),
                DefinitionValidatorTest.edge("e1", "s", "c2"));
        InstanceMeta m = store.createInstance(d, null);
        assertThat(m.originTaskId()).isNull();
        engine.start(m);
        assertThat(engine.awaitTerminal(m.id(), 20_000)).isTrue();
        assertThat(meta(m.id()).status()).isEqualTo(InstanceStatus.FAILED);
        assertThat(meta(m.id()).error().nodeId()).isEqualTo("c2");

        Files.writeString(home.resolve("proj").resolve("go.marker"), "go");
        InstanceMeta running = engine.rerun(m.id(), null, null, null);
        assertThat(running.status()).isEqualTo(InstanceStatus.RUNNING);
        assertThat(engine.awaitTerminal(m.id(), 20_000)).isTrue();
        assertThat(meta(m.id()).status()).isEqualTo(InstanceStatus.DONE);

        // runNo 延续（1=s 2=c2 失败 3=c2 重跑）
        assertThat(runs(m.id())).extracting(r -> r.runNo()).containsExactly(1, 2, 3);
        assertThat(runs(m.id()).stream().filter(r -> "c2".equals(r.nodeId()))).hasSize(2);
    }

    // ══════════════════════════ 状态机细节 ══════════════════════════

    @Test
    void rerunResetsInterruptedRunningNodeToPendingWithoutActivating() throws Exception {
        factory.script("writer", key -> Fakes.Script.failing(new IllegalStateException("快败")));
        factory.script("slow", key -> Fakes.Script.delayed("不该完成", 30_000));
        Definition d = save(
                DefinitionValidatorTest.node("s", "start"),
                DefinitionValidatorTest.node("a", "ai").agent("writer").maxRuns(10),
                DefinitionValidatorTest.node("b", "ai").agent("slow").maxRuns(10),
                DefinitionValidatorTest.edge("e1", "s", "a"),
                DefinitionValidatorTest.edge("e2", "s", "b"));
        InstanceMeta m = engine.start(store.createInstance(d, null, task.subjectId()), task);
        // b 确实跑起来（装配出 slow agent = 已进入节点运行）
        await(() -> factory.built.stream().anyMatch(a -> a.agentId().startsWith("slow#")), "b 运行中");
        assertThat(engine.awaitTerminal(m.id(), 20_000)).isTrue();
        assertThat(meta(m.id()).status()).isEqualTo(InstanceStatus.FAILED);
        assertThat(meta(m.id()).error().nodeId()).isEqualTo("a");
        // 实例失败时被中断的并行节点收口为 cancelled（收口扫描：终态投影零 running）
        assertThat(meta(m.id()).nodeStates().get("b").status()).isEqualTo("cancelled");
        assertThat(eventNames(m.id())).contains("node.interrupted");
        assertThat(runs(m.id()).stream().filter(r -> "b".equals(r.nodeId()))).isEmpty();

        // 从失败节点 a 重开（writer 修复）；b（cancelled）重置 pending 不激活 → 不阻塞收束、不重跑
        factory.script("writer", key -> Fakes.Script.echo("修复"));
        assertThat(Json.parse(tools.workflow_rerun(m.id(), null, null))
                .path("status").asString()).isEqualTo("done");
        assertThat(meta(m.id()).status()).isEqualTo(InstanceStatus.DONE);
        assertThat(meta(m.id()).nodeStates().get("b").status()).isEqualTo("pending");
        assertThat(runs(m.id()).stream().filter(r -> "b".equals(r.nodeId()))).isEmpty();
        assertThat(meta(m.id()).nodeStates().get("a").runCount()).isEqualTo(2);
    }

    @Test
    void rerunArbitraryNodeReopensUpstreamDoneNode() throws Exception {
        factory.script("writer", key -> Fakes.Script.failing(new IllegalStateException("仍失败")));
        Definition d = save(
                DefinitionValidatorTest.node("s", "start"),
                DefinitionValidatorTest.node("c1", "command").command("echo one"),
                DefinitionValidatorTest.node("n", "ai").agent("writer").maxRuns(10),
                DefinitionValidatorTest.edge("e1", "s", "c1"),
                DefinitionValidatorTest.edge("e2", "c1", "n"));
        JsonNode first = Json.parse(tools.workflow_run(d.id(), null, null));
        String id = first.path("instanceId").asString();
        assertThat(first.path("nodeId").asString()).isEqualTo("n");

        // 指定上游已完成节点重开：c1 重跑 → 再次激活 n → 再失败（幂等链）
        JsonNode r = Json.parse(tools.workflow_rerun(id, "c1", null));
        assertThat(r.path("status").asString()).isEqualTo("failed");
        assertThat(r.path("nodeId").asString()).isEqualTo("n");
        assertThat(runs(id).stream().filter(x -> "c1".equals(x.nodeId()))).hasSize(2);
        assertThat(runs(id).stream().filter(x -> "n".equals(x.nodeId()))).hasSize(2);
    }

    @Test
    void rerunTargetNodeMustExistInSnapshot() throws Exception {
        String id = runToFailure();
        String err = Json.parse(tools.workflow_rerun(id, "n_nope", null))
                .path("error").asString();
        assertThat(err).contains("不在定义快照中");
        assertThatThrownBy(() -> engine.rerun(id, "n_nope", null, task))
                .isInstanceOf(BadParamsException.class)
                .hasMessageContaining("不在定义快照中");
        // 实例仍 failed 未受影响
        assertThat(meta(id).status()).isEqualTo(InstanceStatus.FAILED);
    }

    @Test
    void rerunRejectsInputReplacementForCommandNode() throws Exception {
        // BUG-02：命令节点输入=command 本体,传 input 替换须显式报错——旧实现静默丢弃,
        // 调用方白等一次注定失败的续跑（exit N 在 cmd / PowerShell / sh 语义一致）
        Definition d = save(
                DefinitionValidatorTest.node("s", "start"),
                DefinitionValidatorTest.node("c1", "command").command("exit 3"),
                DefinitionValidatorTest.edge("e1", "s", "c1"));
        JsonNode r = Json.parse(tools.workflow_run(d.id(), null, null));
        assertThat(r.path("status").asString()).isEqualTo("failed");
        String id = r.path("instanceId").asString();

        JsonNode rerun = Json.parse(tools.workflow_rerun(id, "c1", "echo fixed"));
        assertThat(rerun.path("error").asString()).contains("不支持 input 替换");
        assertThatThrownBy(() -> engine.rerun(id, "c1", "echo fixed", task))
                .isInstanceOf(BadParamsException.class)
                .hasMessageContaining("workflow_update");
        // 实例未被启动(仍 failed,无新 runs)
        assertThat(meta(id).status()).isEqualTo(InstanceStatus.FAILED);
        assertThat(runs(id).stream().filter(rec -> "c1".equals(rec.nodeId()))).hasSize(1);
    }

    // ══════════════════════════ 阻塞与级联取消（同 run 语义） ══════════════════════════

    @Test
    void rerunBlocksAndTaskStopInterruptCascadesCancel() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        factory.script("writer", key -> calls.incrementAndGet() == 1
                ? Fakes.Script.failing(new IllegalStateException("首败"))
                : Fakes.Script.delayed("不该完成", 30_000));
        Definition d = save(
                DefinitionValidatorTest.node("s", "start"),
                DefinitionValidatorTest.node("n", "ai").agent("writer").maxRuns(10),
                DefinitionValidatorTest.edge("e1", "s", "n"));
        JsonNode first = Json.parse(tools.workflow_run(d.id(), null, null));
        String id = first.path("instanceId").asString();
        assertThat(first.path("status").asString()).isEqualTo("failed");

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicReference<String> result = new AtomicReference<>();
        Thread runner = Thread.ofVirtual().start(() -> {
            try {
                result.set(tools.workflow_rerun(id, null, null));
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        // rerun 已再次开跑 n（第二次 node.started）
        await(() -> eventsOf(id, "node.started", "n").size() == 2, "n 重开");

        runner.interrupt(); // 任务停止的中断直达工具阻塞点
        runner.join(20_000);
        assertThat(thrown.get()).isInstanceOf(AgentCancelledException.class);
        assertThat(result.get()).isNull();

        // 实例被级联取消（by=task-stopped）
        assertThat(engine.awaitTerminal(id, 20_000)).isTrue();
        assertThat(meta(id).status()).isEqualTo(InstanceStatus.CANCELLED);
        var cancelledEv = store.readEvents("ws_run", id, 0, 0).stream()
                .filter(e -> "wf.cancelled".equals(e.event())).findFirst().orElseThrow();
        assertThat(cancelledEv.payload().path("by").asString()).isEqualTo("task-stopped");
        // 取消后不可再 rerun（非 failed）
        assertThat(Json.parse(tools.workflow_rerun(id, null, null))
                .path("error").asString()).contains("仅 failed");
    }
}
