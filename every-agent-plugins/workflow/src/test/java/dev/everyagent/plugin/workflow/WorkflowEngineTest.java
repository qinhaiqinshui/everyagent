// ea: 引擎集成测试（假 AgentFactory 经 agentFactoryFor 注入点伪造模型行为；命令节点真执行）
package dev.everyagent.plugin.workflow;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.Message;

import dev.everyagent.plugin.api.exception.BadParamsException;
import dev.everyagent.plugin.api.model.ModelConfig;
import dev.everyagent.plugin.workflow.engine.WorkflowEngine;
import dev.everyagent.plugin.workflow.model.ConditionDef;
import dev.everyagent.plugin.workflow.model.Definition;
import dev.everyagent.plugin.workflow.model.EventLine;
import dev.everyagent.plugin.workflow.model.InstanceMeta;
import dev.everyagent.plugin.workflow.model.InstanceStatus;
import dev.everyagent.plugin.workflow.model.NodeDef;
import dev.everyagent.plugin.workflow.model.OnError;
import dev.everyagent.plugin.workflow.model.RunRecord;
import dev.everyagent.plugin.workflow.model.VariableDef;
import dev.everyagent.plugin.workflow.store.WorkflowStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/**
 * 引擎端到端：并行+同 agent 互斥（含锁排队读到上游完整事件流）、循环上限、七种条件路由
 * （含多路激活/无匹配收束）、错误三策略、取消、匿名 vs 命名 agent、变量渲染、事件/记录留档。
 */
class WorkflowEngineTest {

    @TempDir
    Path home;

    FakeWorkspaces workspaces;
    WorkflowStore store;
    Fakes.FakeAgentFactory factory;
    Fakes.FakeWorkerServices services;
    WorkflowEngine engine;
    private final java.util.concurrent.atomic.AtomicInteger taskSeq = new java.util.concurrent.atomic.AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        workspaces = new FakeWorkspaces(home);
        // 命令节点 cwd=工作区根：目录须真实存在（真实 worker 注册的工作区必有实体目录）
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
        Definition d = withOnError(DefinitionValidatorTest.def(parts), null);
        return store.saveDefinition("ws_run", d);
    }

    private Definition saveOnError(OnError onError, Object... parts) {
        return store.saveDefinition("ws_run", withOnError(DefinitionValidatorTest.def(parts), onError));
    }

    private static Definition withOnError(Definition d, OnError onError) {
        return new Definition(d.schemaVersion(), d.id(), d.name(), d.description(), d.workspaceId(),
                d.createdAt(), d.updatedAt(), d.variables(), onError, d.supervisor(),
                d.nodes(), d.edges());
    }

    private InstanceMeta start(Definition d) {
        return start(d, Map.of());
    }

    private InstanceMeta start(Definition d, Map<String, String> vars) {
        task = taskExec();
        return engine.start(store.createInstance(d, vars, task.subjectId()), task);
    }

    /** 新建任务 ExecContext 桩（每实例一个 origin 任务，任务 ID 全局递增）。 */
    private Fakes.FakeTaskExec taskExec() {
        return new Fakes.FakeTaskExec(home.resolve("workspaces"), "ws_run",
                "task_" + taskSeq.incrementAndGet(), factory);
    }

    /** 最近一次 start() 绑定的任务桩（AI 节点事件断言用）。 */
    private Fakes.FakeTaskExec task;

    private List<EventLine> events(String instanceId) {
        return store.readEvents("ws_run", instanceId, 0, 0);
    }

    private List<String> eventNames(String instanceId) {
        return events(instanceId).stream().map(EventLine::event).toList();
    }

    private List<RunRecord> runs(String instanceId) {
        return store.listRunRecords("ws_run", instanceId);
    }

    private InstanceMeta meta(String instanceId) {
        return store.findInstance(instanceId).orElseThrow();
    }

    private void await(BooleanSupplier cond, String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (!cond.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                fail("超时等待: " + what + "；事件=" + eventNames(latest));
            }
            Thread.sleep(20);
        }
    }

    private String latest;

    private void awaitTerminal(String instanceId) throws InterruptedException {
        latest = instanceId;
        assertThat(engine.awaitTerminal(instanceId, 20_000)).as("实例终态").isTrue();
    }

    private void awaitStatus(String instanceId, InstanceStatus status) throws InterruptedException {
        latest = instanceId;
        await(() -> meta(instanceId).status() == status, "状态 " + status);
    }

    // ══════════════════════════ 基础流转 ══════════════════════════

    @Test
    void startOnlyDefinitionCompletes() throws Exception {
        Definition d = save(DefinitionValidatorTest.node("s", "start"));
        InstanceMeta m = start(d);
        awaitTerminal(m.id());

        assertThat(meta(m.id()).status()).isEqualTo(InstanceStatus.DONE);
        assertThat(meta(m.id()).startedAt()).isNotNull();
        assertThat(meta(m.id()).endedAt()).isNotNull();
        assertThat(meta(m.id()).nodeStates()).isNotNull();
        assertThat(meta(m.id()).nodeStates()).containsKey("s");

        List<String> names = eventNames(m.id());
        assertThat(names).containsSubsequence("wf.created", "wf.status", "node.started",
                "node.output", "wf.done", "wf.status");
        assertThat(names).doesNotContain("node.error");
        // 终态 nodeStates 投影
        assertThat(meta(m.id()).nodeStates().get("s").status()).isEqualTo("done");
        assertThat(meta(m.id()).nodeStates().get("s").runCount()).isEqualTo(1);
        assertThat(meta(m.id()).runCounter()).isEqualTo(1);
    }

    @Test
    void startRejectsNonCreatedOrMissingWorkspace() throws Exception {
        Definition d = save(DefinitionValidatorTest.node("s", "start"));
        InstanceMeta m = start(d);
        awaitTerminal(m.id());
        // 已终态不可再启动
        assertThatThrownBy(() -> engine.start(meta(m.id())))
                .isInstanceOf(BadParamsException.class);
        // 工作区不在注册表 → 拒绝启动
        Definition orphan = store.saveDefinition("ws_run", DefinitionValidatorTest.def(
                DefinitionValidatorTest.node("s", "start")));
        InstanceMeta created = store.createInstance(
                new Definition(orphan.schemaVersion(), orphan.id(), orphan.name(),
                        orphan.description(), "ws_missing", orphan.createdAt(), orphan.updatedAt(),
                        orphan.variables(), orphan.onError(), orphan.supervisor(), orphan.nodes(), orphan.edges()), Map.of());
        assertThatThrownBy(() -> engine.start(created)).isInstanceOf(BadParamsException.class);
    }

    // ══════════════════════════ 任务绑定（§7.20.8） ══════════════════════════

    @Test
    void aiDefinitionRejectedWithoutUsableTaskBinding() throws InterruptedException {
        Definition d = save(
                DefinitionValidatorTest.node("s", "start"),
                DefinitionValidatorTest.node("n", "ai").agent("writer"),
                DefinitionValidatorTest.edge("e1", "s", "n"));
        // 无绑定 → 拒绝（引导经任务运行）
        assertThatThrownBy(() -> engine.start(store.createInstance(d, Map.of())))
                .isInstanceOf(BadParamsException.class)
                .hasMessageContaining("AI")
                .hasMessageContaining("任务");
        // 绑定任务已收口（emitter 失效语义）→ 同样拒绝
        Fakes.FakeTaskExec dead = taskExec();
        dead.terminal = true;
        assertThatThrownBy(() -> engine.start(store.createInstance(d, Map.of(), dead.subjectId()), dead))
                .isInstanceOf(BadParamsException.class)
                .hasMessageContaining("AI");
        // 绑定与 meta.originTaskId 不一致 → 拒绝
        Fakes.FakeTaskExec bound = taskExec();
        Fakes.FakeTaskExec other = taskExec();
        assertThatThrownBy(() -> engine.start(
                store.createInstance(d, Map.of(), other.subjectId()), bound))
                .isInstanceOf(BadParamsException.class)
                .hasMessageContaining("不一致");
        // 无 AI 节点定义无绑定照常可跑（RPC 直启兼容通道）；等终态后再收测试——
        // 否则引擎线程仍在写 @TempDir，Windows 上目录清理间歇性失败（flaky）
        Definition cmdOnly = save(
                DefinitionValidatorTest.node("s", "start"),
                DefinitionValidatorTest.node("c", "command").command("echo ok"),
                DefinitionValidatorTest.edge("e2", "s", "c"));
        InstanceMeta unbound = engine.start(store.createInstance(cmdOnly, Map.of()));
        assertThat(unbound.status()).isEqualTo(InstanceStatus.RUNNING);
        assertThat(engine.awaitTerminal(unbound.id(), 20_000)).isTrue();
    }

    @Test
    void aiNodeEventsLandInTaskDomainOnly() throws Exception {
        // 任务绑定后：AI 节点事件（指令轮/回复/usage）全走任务 emitter，实例域不落 agent 事件
        factory.script("writer", key -> Fakes.Script.echo("产出"));
        Definition d = save(
                DefinitionValidatorTest.node("s", "start"),
                DefinitionValidatorTest.node("n", "ai").agent("writer").input("做点事"),
                DefinitionValidatorTest.edge("e1", "s", "n"));
        InstanceMeta m = start(d);
        awaitTerminal(m.id());
        assertThat(meta(m.id()).status()).isEqualTo(InstanceStatus.DONE);

        // 任务档案：完整轮次（user.message → message → usage）
        assertThat(task.agentLines("writer#" + m.id()).stream().map(l -> l.event()).toList())
                .containsExactly("user.message", "message", "usage");
        // agent 经任务 ExecContext 的 agentFactory 装配（绑定主体=任务）
        assertThat(factory.bound).isSameAs(task);
        // 实例 events.jsonl 只含实例域事件（无 user.message/message/usage）
        assertThat(eventNames(m.id()))
                .doesNotContain("user.message", "message", "usage", "tool.result");
        // 实例目录无 agents/
        assertThat(instanceAgentsDir(m.id())).doesNotExist();
    }

    // ══════════════════════════ 命令节点 + 变量渲染 ══════════════════════════

    @Test
    void commandNodeExecutesWithRenderedVariable() throws Exception {
        Definition base = DefinitionValidatorTest.def(
                DefinitionValidatorTest.node("s", "start"),
                DefinitionValidatorTest.node("cmd", "command").command("echo hello {{name}}"),
                DefinitionValidatorTest.edge("e1", "s", "cmd"));
        Definition withVars = DefinitionValidatorTest.withVariables(base,
                new VariableDef("name", null, true, null, null));
        Definition saved = store.saveDefinition("ws_run", withVars);

        InstanceMeta m = start(saved, Map.of("name", "starship"));
        awaitTerminal(m.id());
        assertThat(meta(m.id()).status()).as(eventNames(m.id()) + "/" + runs(m.id())).isEqualTo(InstanceStatus.DONE);

        RunRecord cmdRun = runs(m.id()).stream()
                .filter(r -> "cmd".equals(r.nodeId())).findFirst().orElseThrow();
        assertThat(cmdRun.output().stdout()).contains("hello starship");
        assertThat(cmdRun.output().exitCode()).isZero();
        assertThat(cmdRun.input().rendered()).isEqualTo("echo hello starship");
        assertThat(cmdRun.input().raw()).isEqualTo("echo hello {{name}}");
        assertThat(cmdRun.input().visibleToAi()).isNull(); // 命令节点无 token 拍平视图
    }

    @Test
    void aiNodeInputRenderedAndTokenFlattened() throws Exception {
        String token = dev.everyagent.plugin.api.slash.SlashTokenEncoder.buildToken(
                "file.ref", "a.png", null, dev.everyagent.contract.json.Json.obj().put("path", "a.png"));
        Definition base = DefinitionValidatorTest.def(
                DefinitionValidatorTest.node("s", "start"),
                DefinitionValidatorTest.node("ai", "ai").agent("writer").modelConfigId("gpt5").systemPrompt("你是作者").input("引用 {{ref}} 写作"),
                DefinitionValidatorTest.edge("e1", "s", "ai"));
        Definition withVars = DefinitionValidatorTest.withVariables(base,
                new VariableDef("ref", null, true, null, null));
        Definition saved = store.saveDefinition("ws_run", withVars);
        factory.script("writer", key -> Fakes.Script.echo("写作完成"));

        InstanceMeta m = start(saved, Map.of("ref", token));
        awaitTerminal(m.id());
        assertThat(meta(m.id()).status()).isEqualTo(InstanceStatus.DONE);

        RunRecord aiRun = runs(m.id()).stream().filter(r -> "ai".equals(r.nodeId()))
                .findFirst().orElseThrow();
        // rendered 留档 opaque token 原串；visibleToAi 拍平为 label
        assertThat(aiRun.input().rendered()).contains(token);
        assertThat(aiRun.input().visibleToAi()).isEqualTo("引用 a.png 写作");
        // 指令轮 user.message：content=拍平文本，data.rawContent=原串（任务档案为唯一会话载体）
        var lines = task.agentLines("writer#" + m.id());
        assertThat(lines).isNotEmpty();
        var instruction = lines.stream()
                .filter(l -> "user.message".equals(l.event())).findFirst().orElseThrow();
        assertThat(instruction.payload().path("content").asString()).isEqualTo("引用 a.png 写作");
        assertThat(instruction.payload().path("data").path("rawContent").asString()).contains(token);
        assertThat(instruction.agentId()).isEqualTo("writer#" + m.id());
        // agent 观测：seed 0 轮、结果轮 > 0、usage 落档
        assertThat(aiRun.agent().name()).isEqualTo("writer");
        assertThat(aiRun.agent().modelConfigId()).isEqualTo("gpt5");
        assertThat(aiRun.agent().seedTurns()).isZero();
        assertThat(aiRun.agent().resultTurns()).isPositive();
        assertThat(aiRun.agent().usage().inputTokens()).isEqualTo(100L);
    }

    // ══════════════════════════ 七种条件路由 ══════════════════════════

    @Test
    void conditionRoutingAllSevenTypes() throws Exception {
        // 每条分支：start → cmd(特定产出) →(条件) sink；sink 起跑与否即路由结果
        Definition ok = route("cmd-ok", "echo hello", "sink-ok");
        Definition bad = route("cmd-bad", "exit 3", "sink-bad");

        // onSuccess / onError / exitCode
        assertActivated(routeCond("cmd-ok", "echo hello", new ConditionDef("onSuccess", null, null, null), "sink"), "sink");
        assertNotActivated(routeCond("cmd-ok", "echo hello", new ConditionDef("onError", null, null, null), "sink"), "sink");
        assertActivated(routeCond("cmd-bad", "exit 3", new ConditionDef("onError", null, null, null), "sink"), "sink");
        assertActivated(routeCond("cmd-bad", "exit 3", new ConditionDef("exitCode", 3L, null, null), "sink"), "sink");
        assertNotActivated(routeCond("cmd-bad", "exit 3", new ConditionDef("exitCode", 0L, null, null), "sink"), "sink");
        // 文本条件
        assertActivated(routeCond("cmd-ok", "echo hello", new ConditionDef("outputContains", null, "hello", null), "sink"), "sink");
        assertNotActivated(routeCond("cmd-ok", "echo hello", new ConditionDef("outputContains", null, "zzz", null), "sink"), "sink");
        assertActivated(routeCond("cmd-ok", "echo hello", new ConditionDef("outputNotContains", null, "zzz", null), "sink"), "sink");
        assertNotActivated(routeCond("cmd-ok", "echo hello", new ConditionDef("outputNotContains", null, "hello", null), "sink"), "sink");
        assertActivated(routeCond("cmd-ok", "echo hello",
                new ConditionDef("outputMatches", null, null, "(?s).*hello.*"), "sink"), "sink");
        assertNotActivated(routeCond("cmd-ok", "echo hello",
                new ConditionDef("outputMatches", null, null, "(?s)^bye.*"), "sink"), "sink");
        // 未用实例仅防编译告警
        assertThat(ok).isNotNull();
        assertThat(bad).isNotNull();
    }

    private Definition route(String cmdId, String cmd, String sinkId) {
        return routeCond(cmdId, cmd, null, sinkId);
    }

    private Definition routeCond(String cmdId, String cmd, ConditionDef cond, String sinkId) {
        return store.saveDefinition("ws_run", DefinitionValidatorTest.def(
                DefinitionValidatorTest.node("s", "start"),
                DefinitionValidatorTest.node(cmdId, "command").command(cmd).maxRuns(10),
                DefinitionValidatorTest.node(sinkId, "ai").agent(sinkId).maxRuns(10),
                DefinitionValidatorTest.edge("e1", "s", cmdId),
                DefinitionValidatorTest.edge("e2", cmdId, sinkId, cond)));
    }

    private void assertActivated(Definition d, String sinkId) throws InterruptedException {
        InstanceMeta m = start(d);
        awaitTerminal(m.id());
        assertThat(meta(m.id()).status()).as("事件=" + eventNames(m.id())).isEqualTo(InstanceStatus.DONE);
        assertThat(startedNodes(m.id())).contains(sinkId);
    }

    private void assertNotActivated(Definition d, String sinkId) throws InterruptedException {
        InstanceMeta m = start(d);
        awaitTerminal(m.id());
        // 无匹配收束：实例照常 done，sink 不跑（不报错）
        assertThat(meta(m.id()).status()).isEqualTo(InstanceStatus.DONE);
        assertThat(startedNodes(m.id())).doesNotContain(sinkId);
    }

    private List<String> startedNodes(String instanceId) {
        return events(instanceId).stream()
                .filter(e -> "node.started".equals(e.event()))
                .map(e -> e.payload().path("nodeId").asString())
                .toList();
    }

    @Test
    void multiActivationFansOutAndDeadEndConverges() throws Exception {
        // 一个源节点两条满足条件的出边 → 双扇出
        Definition fanout = store.saveDefinition("ws_run", DefinitionValidatorTest.def(
                DefinitionValidatorTest.node("s", "start"),
                DefinitionValidatorTest.node("c", "command").command("echo hello").maxRuns(10),
                DefinitionValidatorTest.node("t1", "ai").agent("t1").maxRuns(10),
                DefinitionValidatorTest.node("t2", "ai").agent("t2").maxRuns(10),
                DefinitionValidatorTest.node("t3", "ai").agent("t3").maxRuns(10),
                DefinitionValidatorTest.edge("e1", "s", "c"),
                DefinitionValidatorTest.edge("e2", "c", "t1", new ConditionDef("onSuccess", null, null, null)),
                DefinitionValidatorTest.edge("e3", "c", "t2", new ConditionDef("outputContains", null, "hello", null)),
                DefinitionValidatorTest.edge("e4", "c", "t3", new ConditionDef("outputContains", null, "zzz", null))));
        InstanceMeta m = start(fanout);
        awaitTerminal(m.id());
        assertThat(meta(m.id()).status()).isEqualTo(InstanceStatus.DONE);
        assertThat(startedNodes(m.id())).contains("t1", "t2").doesNotContain("t3");
        // node.output 事件的 activatedEdges 记录两条
        var outputEv = events(m.id()).stream().filter(e -> "node.output".equals(e.event())
                && "c".equals(e.payload().path("nodeId").asString())).findFirst().orElseThrow();
        assertThat(outputEv.payload().path("activatedEdges").size()).isEqualTo(2);
        assertThat(outputEv.payload().path("outputPreview").asString()).contains("hello");
    }

    // ══════════════════════════ 同 agent 互斥 / 并行 ══════════════════════════

    @Test
    void sameAgentNodesSerializeAndLaterRunSeesUpstreamConversation() throws Exception {
        AtomicInteger seq = new AtomicInteger();
        factory.script("writer", key -> Fakes.Script.delayed("产出" + seq.incrementAndGet(), 150));
        Definition d = save(
                DefinitionValidatorTest.node("s", "start"),
                DefinitionValidatorTest.node("a", "ai").agent("writer").input("任务A").maxRuns(10),
                DefinitionValidatorTest.node("b", "ai").agent("writer").input("任务B").maxRuns(10),
                DefinitionValidatorTest.edge("e1", "s", "a"),
                DefinitionValidatorTest.edge("e2", "s", "b"));
        InstanceMeta m = start(d);
        awaitTerminal(m.id());
        assertThat(meta(m.id()).status()).as("事件=" + eventNames(m.id())).isEqualTo(InstanceStatus.DONE);

        List<Fakes.FakeAgent> writers = factory.built.stream()
                .filter(a -> a.agentId().startsWith("writer#")).toList();
        assertThat(writers).hasSize(2);
        // 互斥：两次 run 的时间区间不重叠（fair 锁排队）
        Fakes.FakeAgent first = writers.get(0).runStartedAt <= writers.get(1).runStartedAt
                ? writers.get(0) : writers.get(1);
        Fakes.FakeAgent second = first == writers.get(0) ? writers.get(1) : writers.get(0);
        boolean overlap = first.runStartedAt < second.runEndedAt
                && second.runStartedAt < first.runEndedAt;
        assertThat(overlap).as("同 agent 两次 run 必须串行").isFalse();
        // 后跑者读到上游完整事件流（种子含先跑者的指令轮 + 回复轮——从任务档案重建）
        assertThat(second.seed).hasSize(2);
        assertThat(second.seed.get(0)).isInstanceOf(org.springframework.ai.chat.messages.UserMessage.class);
        assertThat(second.seed.get(1).getText()).isEqualTo(first.lastText);
        // 任务档案：两轮 user.message/message(+usage) 交替、无交错（行数即完整性证据）
        var lines = task.agentLines("writer#" + m.id());
        assertThat(lines).hasSize(6);
        assertThat(lines.stream().map(l -> l.event()).toList())
                .containsExactly("user.message", "message", "usage",
                        "user.message", "message", "usage");
        Path writerFile = task.taskDir.resolve("writer#" + m.id() + ".jsonl");
        assertThat(Files.readAllLines(writerFile)).hasSize(6);
        // 实例目录不再产生 agents/ 写入（会话单写任务域）
        assertThat(instanceAgentsDir(m.id())).doesNotExist();
    }

    @Test
    void differentAgentsRunInParallel() throws Exception {
        factory.script("a1", key -> Fakes.Script.delayed("a1-out", 400));
        factory.script("a2", key -> Fakes.Script.delayed("a2-out", 400));
        Definition d = save(
                DefinitionValidatorTest.node("s", "start"),
                DefinitionValidatorTest.node("n1", "ai").agent("a1").maxRuns(10),
                DefinitionValidatorTest.node("n2", "ai").agent("a2").maxRuns(10),
                DefinitionValidatorTest.edge("e1", "s", "n1"),
                DefinitionValidatorTest.edge("e2", "s", "n2"));
        InstanceMeta m = start(d);
        awaitTerminal(m.id());
        assertThat(meta(m.id()).status()).isEqualTo(InstanceStatus.DONE);

        Fakes.FakeAgent x = agent("a1#");
        Fakes.FakeAgent y = agent("a2#");
        boolean overlap = x.runStartedAt < y.runEndedAt && y.runStartedAt < x.runEndedAt;
        assertThat(overlap).as("不同 agent 应真并行").isTrue();
        // 各自会话线隔离（任务档案按 agentId 分文件）
        assertThat(task.agentLines("a1#" + m.id())).isNotEmpty();
        assertThat(task.agentLines("a2#" + m.id())).isNotEmpty();
    }

    private Fakes.FakeAgent agent(String prefix) {
        return factory.built.stream().filter(a -> a.agentId().startsWith(prefix))
                .reduce((a, b) -> b).orElseThrow();
    }

    // ══════════════════════════ 循环上限 ══════════════════════════

    @Test
    void loopMaxRunsTriggersErrorPolicy() throws Exception {
        Definition d = saveOnError(OnError.FAIL,
                DefinitionValidatorTest.node("s", "start"),
                DefinitionValidatorTest.node("a", "ai").agent("looperA").maxRuns(2),
                DefinitionValidatorTest.node("b", "ai").agent("looperB").maxRuns(10),
                DefinitionValidatorTest.edge("e1", "s", "a"),
                DefinitionValidatorTest.edge("e2", "a", "b"),
                DefinitionValidatorTest.edge("e3", "b", "a"));
        InstanceMeta m = start(d);
        awaitTerminal(m.id());
        assertThat(meta(m.id()).status()).isEqualTo(InstanceStatus.FAILED);
        assertThat(meta(m.id()).error().message()).contains("最大运行次数");
        assertThat(meta(m.id()).error().nodeId()).isEqualTo("a");
        assertThat(eventNames(m.id())).contains("node.error", "wf.status");
        assertThat(meta(m.id()).nodeStates().get("a").runCount()).isEqualTo(2);
        assertThat(runs(m.id()).stream().filter(r -> "a".equals(r.nodeId()))).hasSize(2);
    }

    // ══════════════════════════ 错误策略三态 ══════════════════════════

    @Test
    void errorPolicyFailFailsInstance() throws Exception {
        factory.script("writer", key -> Fakes.Script.failing(
                new IllegalStateException("模型调用失败")));
        Definition d = saveOnError(OnError.FAIL,
                DefinitionValidatorTest.node("s", "start"),
                DefinitionValidatorTest.node("n", "ai").agent("writer"),
                DefinitionValidatorTest.edge("e1", "s", "n"));
        InstanceMeta m = start(d);
        awaitTerminal(m.id());
        assertThat(meta(m.id()).status()).isEqualTo(InstanceStatus.FAILED);
        assertThat(meta(m.id()).error().message()).contains("模型调用失败");
        // runs 留档 error + node.error 事件
        RunRecord rec = runs(m.id()).stream().filter(r -> "n".equals(r.nodeId()))
                .findFirst().orElseThrow();
        assertThat(rec.error().message()).contains("模型调用失败");
        assertThat(eventNames(m.id())).contains("node.error");
        // 指令轮已落任务档案（会话线自包含），无 message 事件
        assertThat(task.agentLines("writer#" + m.id()))
                .extracting(l -> l.event()).containsExactly("user.message");
    }

    @Test
    void errorPolicyAskUserParksAndDecideWakes() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        factory.script("writer", key -> calls.incrementAndGet() == 1
                ? Fakes.Script.failing(new RuntimeException("第一次失败"))
                : Fakes.Script.echo("重试成功"));
        Definition d = saveOnError(OnError.ASK_USER,
                DefinitionValidatorTest.node("s", "start"),
                DefinitionValidatorTest.node("n", "ai").agent("writer").maxRuns(10),
                DefinitionValidatorTest.node("t", "ai").agent("tail").maxRuns(10),
                DefinitionValidatorTest.edge("e1", "s", "n"),
                DefinitionValidatorTest.edge("e2", "n", "t"));
        InstanceMeta m = start(d);
        awaitStatus(m.id(), InstanceStatus.WAITING_USER);

        InstanceMeta parked = meta(m.id());
        assertThat(parked.pendingDecision()).isNotNull();
        assertThat(parked.pendingDecision().nodeId()).isEqualTo("n");
        assertThat(parked.pendingDecision().options())
                .containsExactly("retry", "skip", "fail", "retryWithInput");
        assertThat(eventNames(m.id())).contains("wf.askUser");

        // 决策不匹配被拒
        int wrongRunNo = parked.pendingDecision().runNo() + 100;
        assertThatThrownBy(() -> engine.decide(m.id(), wrongRunNo, "retry", null))
                .isInstanceOf(BadParamsException.class);
        assertThatThrownBy(() -> engine.decide(m.id(), parked.pendingDecision().runNo(), "自创", null))
                .isInstanceOf(BadParamsException.class);

        engine.decide(m.id(), parked.pendingDecision().runNo(), "retry", null);
        awaitTerminal(m.id());
        assertThat(meta(m.id()).status()).isEqualTo(InstanceStatus.DONE);
        assertThat(meta(m.id()).pendingDecision()).isNull();
        assertThat(runs(m.id()).stream().filter(r -> "n".equals(r.nodeId()))).hasSize(2);
        assertThat(startedNodes(m.id())).contains("t");
        assertThat(eventNames(m.id())).filteredOn("wf.status"::equals).hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    void onErrorSupervisorBehavesAsFailFast() throws Exception {
        // fail-fast（§7.20）：独立督导已删除，SUPERVISOR（含缺省）语义=FAIL——
        // 不起 supervisor 会话线、无 supervisor.decision 事件，实例直接 failed
        factory.script("writer", key -> Fakes.Script.failing(new RuntimeException("命令性失败")));
        Definition d = saveOnError(OnError.SUPERVISOR,
                DefinitionValidatorTest.node("s", "start"),
                DefinitionValidatorTest.node("n", "ai").agent("writer").maxRuns(10),
                DefinitionValidatorTest.node("t", "ai").agent("tail").maxRuns(10),
                DefinitionValidatorTest.edge("e1", "s", "n"),
                DefinitionValidatorTest.edge("e2", "n", "t"));
        InstanceMeta m = start(d);
        awaitTerminal(m.id());
        assertThat(meta(m.id()).status()).isEqualTo(InstanceStatus.FAILED);
        assertThat(meta(m.id()).error().message()).contains("失败");
        assertThat(meta(m.id()).error().nodeId()).isEqualTo("n");

        // 无督导会话、无决策事件；下游不被调度（fail-fast 收口）
        assertThat(eventNames(m.id())).doesNotContain("supervisor.decision", "node.skipped", "wf.askUser");
        assertThat(task.agentLinesByPrefix("supervisor#")).isEmpty();
        assertThat(startedNodes(m.id())).doesNotContain("t");
        // runs 留档失败（supervisor 介入链恒空）
        RunRecord rec = runs(m.id()).stream().filter(r -> "n".equals(r.nodeId()))
                .findFirst().orElseThrow();
        assertThat(rec.error()).isNotNull();
        assertThat(rec.supervisor()).isNull();
    }

    @Test
    void askUserRetryWithInputReplacesNodeInput() throws Exception {
        // 决策内核保留：retryWithInput 经 askUser 挂起 + decide RPC 落地（rerun 同源语义）
        AtomicInteger calls = new AtomicInteger();
        factory.script("writer", key -> calls.incrementAndGet() == 1
                ? Fakes.Script.failing(new RuntimeException("输入不当"))
                : Fakes.Script.echo("按新输入完成"));
        Definition d = saveOnError(OnError.ASK_USER,
                DefinitionValidatorTest.node("s", "start"),
                DefinitionValidatorTest.node("n", "ai").agent("writer").maxRuns(10).input("原始输入"),
                DefinitionValidatorTest.edge("e1", "s", "n"));
        InstanceMeta m = start(d);
        awaitStatus(m.id(), InstanceStatus.WAITING_USER);

        InstanceMeta parked = meta(m.id());
        engine.decide(m.id(), parked.pendingDecision().runNo(), "retryWithInput", "改用英文重写");
        awaitTerminal(m.id());
        assertThat(meta(m.id()).status()).isEqualTo(InstanceStatus.DONE);

        List<RunRecord> recs = runs(m.id()).stream()
                .filter(r -> "n".equals(r.nodeId())).sorted((x, y) -> x.runNo() - y.runNo()).toList();
        assertThat(recs).hasSize(2);
        assertThat(recs.get(0).input().rendered()).isEqualTo("原始输入");
        assertThat(recs.get(1).input().rendered()).isEqualTo("改用英文重写");
        // 第二次运行实际收到新输入
        List<Fakes.FakeAgent> writers = factory.built.stream()
                .filter(a -> a.agentId().startsWith("writer#")).toList();
        assertThat(writers.get(1).userInput).isEqualTo("改用英文重写");
    }

    // ══════════════════════════ 取消 ══════════════════════════

    @Test
    void cancelInterruptsRunningNode() throws Exception {
        factory.script("writer", key -> Fakes.Script.delayed("不该完成", 30_000));
        Definition d = save(
                DefinitionValidatorTest.node("s", "start"),
                DefinitionValidatorTest.node("n", "ai").agent("writer"),
                DefinitionValidatorTest.edge("e1", "s", "n"));
        InstanceMeta m = start(d);
        latest = m.id();
        await(() -> eventNames(m.id()).contains("node.started"), "node.started");

        engine.cancel(m.id(), "tester");
        awaitTerminal(m.id());
        assertThat(meta(m.id()).status()).isEqualTo(InstanceStatus.CANCELLED);
        assertThat(eventNames(m.id())).contains("wf.cancelled");
        var cancelledEv = events(m.id()).stream()
                .filter(e -> "wf.cancelled".equals(e.event())).findFirst().orElseThrow();
        assertThat(cancelledEv.payload().path("by").asString()).isEqualTo("tester");
        // 已终态再取消被拒
        assertThatThrownBy(() -> engine.cancel(m.id(), "again")).isInstanceOf(BadParamsException.class);
        // 被取消节点未完成：无该节点的 node.output（start 节点的照常有）
        assertThat(events(m.id()).stream().filter(e -> "node.output".equals(e.event())
                && "n".equals(e.payload().path("nodeId").asString()))).isEmpty();
    }

    // ══════════════════════════ 匿名 vs 命名 agent ══════════════════════════

    @Test
    void anonymousAiNodeWritesTaskArchiveOnly() throws Exception {
        Definition base = DefinitionValidatorTest.def(
                DefinitionValidatorTest.node("s", "start"),
                DefinitionValidatorTest.node("anon", "ai").input("匿名一次性"),
                DefinitionValidatorTest.node("named", "ai").agent("writer").input("命名会话"),

                DefinitionValidatorTest.edge("e1", "s", "anon"),

                DefinitionValidatorTest.edge("e2", "s", "named"));
        // 旧结构兼容通道：匿名 AI 节点已过不了保存校验，直构绕过（挂注册工作区）
        Definition d = new Definition(base.schemaVersion(), null, base.name(), base.description(), "ws_run",
                null, null, base.variables(), OnError.SUPERVISOR, base.supervisor(), base.nodes(), base.edges());
        InstanceMeta m = start(d);
        awaitTerminal(m.id());
        assertThat(meta(m.id()).status()).isEqualTo(InstanceStatus.DONE);

        // 实例目录不再创建 agents/（会话单写任务域）
        assertThat(instanceAgentsDir(m.id())).doesNotExist();
        // 匿名一次性：agentId=<nodeId>.<runNo>，指令轮 + 回复轮落任务档案（任务页可见）
        var anonLines = task.agentLinesByPrefix("anon.");
        assertThat(anonLines).isNotEmpty();
        assertThat(anonLines.stream().map(l -> l.event()).toList())
                .contains("user.message", "message", "usage");
        assertThat(anonLines.get(0).agentId()).startsWith("anon.");
        // 命名会话：agentId=<name>#<wi>
        assertThat(task.agentLines("writer#" + m.id())).isNotEmpty();
        RunRecord anon = runs(m.id()).stream().filter(r -> "anon".equals(r.nodeId()))
                .findFirst().orElseThrow();
        assertThat(anon.agent()).isNull();
        assertThat(anon.output().text()).isEqualTo("ok:anon.2"); // 默认脚本按 agentKey 回声
        RunRecord named = runs(m.id()).stream().filter(r -> "named".equals(r.nodeId()))
                .findFirst().orElseThrow();
        assertThat(named.agent()).isNotNull();
    }

    /** 实例目录下的 agents/ 路径（应永不创建）。 */
    private Path instanceAgentsDir(String instanceId) {
        return home.resolve("workspaces").resolve("ws_run").resolve("workflows")
                .resolve("instances").resolve(instanceId).resolve("agents");
    }

    // ══════════════════════════ 工具剔除 + 事件查询 ══════════════════════════

    @Test
    void aiAgentsGetAgentFamilyAndWorkflowToolsRemoved() throws Exception {
        Definition d = save(
                DefinitionValidatorTest.node("s", "start"),
                DefinitionValidatorTest.node("n", "ai").agent("writer"),
                DefinitionValidatorTest.edge("e1", "s", "n"));
        InstanceMeta m = start(d);
        awaitTerminal(m.id());

        List<Fakes.FakeAgent> writers = factory.built.stream()
                .filter(a -> a.agentId().startsWith("writer#")).toList();
        assertThat(writers).isNotEmpty();
        // 断言在 builder 层：REMOVE 表收到了十五个名字（agent 族四件 + workflow CRUD 五件
        // + 运行/查询五件 + rerun 防递归先占名）
        assertThat(factory.removeTables).isNotEmpty();
        List<String> removed = factory.removeTables.get(factory.removeTables.size() - 1).stream()
                .map(tc -> tc.getToolDefinition().name())
                .toList();
        assertThat(removed).containsExactlyInAnyOrder(
                "run_agent", "list_agents", "wait_agents", "stop_agent",
                "workflow_create", "workflow_get", "workflow_update",
                "workflow_delete", "workflow_list",
                "workflow_run", "workflow_instance_status", "workflow_instance_log",
                "workflow_instance_events", "workflow_instance_cancel", "workflow_rerun");
    }

    @Test
    void queryEventsServesTailWithAfterSeqCursor() throws Exception {
        Definition d = save(
                DefinitionValidatorTest.node("s", "start"),
                DefinitionValidatorTest.node("c", "command").command("echo tail"),
                DefinitionValidatorTest.edge("e1", "s", "c"));
        InstanceMeta m = start(d);
        awaitTerminal(m.id());

        List<EventLine> all = engine.queryEvents(m.id(), 0, 0, 0);
        assertThat(all.size()).isGreaterThanOrEqualTo(5);
        // afterSeq 游标
        long mid = all.get(all.size() - 3).seqValue();
        List<EventLine> tail = engine.queryEvents(m.id(), mid, 0, 0);
        assertThat(tail).hasSize(2);
        assertThat(tail.get(0).seqValue()).isGreaterThan(mid);
        // limit
        List<EventLine> limited = engine.queryEvents(m.id(), 0, 0, 2);
        assertThat(limited).hasSize(2);
        // 终态实例：等待立即返回空（游标之后无事件）
        long last = all.get(all.size() - 1).seqValue();
        assertThat(engine.queryEvents(m.id(), last, 5_000, 0)).isEmpty();
    }

    // ══════════════════════════ 命名会话线续跑（链式） ══════════════════════════

    @Test
    void chainedSameAgentNodesShareConversationLine() throws Exception {
        AtomicInteger round = new AtomicInteger();
        factory.script("writer", key -> Fakes.Script.echo("第" + round.incrementAndGet() + "稿"));
        Definition d = save(
                DefinitionValidatorTest.node("s", "start"),
                DefinitionValidatorTest.node("a", "ai").agent("writer").input("第一段"),
                DefinitionValidatorTest.node("b", "ai").agent("writer").input("第二段"),
                DefinitionValidatorTest.edge("e1", "s", "a"),
                DefinitionValidatorTest.edge("e2", "a", "b"));
        InstanceMeta m = start(d);
        awaitTerminal(m.id());
        assertThat(meta(m.id()).status()).isEqualTo(InstanceStatus.DONE);

        List<Fakes.FakeAgent> writers = factory.built.stream()
                .filter(a -> a.agentId().startsWith("writer#")).toList();
        assertThat(writers).hasSize(2);
        // 第二节点种子 = 第一节点的 指令轮 + 回复轮
        assertThat(writers.get(1).seed).hasSize(2);
        assertThat(writers.get(1).seed.get(1).getText()).isEqualTo(writers.get(0).lastText);
        // 会话线单文件两轮有序（usage 也随事件流落任务档案）
        var lines = task.agentLines("writer#" + m.id());
        assertThat(lines.stream().map(l -> l.event()).toList())
                .containsExactly("user.message", "message", "usage",
                        "user.message", "message", "usage");
        assertThat(lines.get(0).payload().path("content").asString()).isEqualTo("第一段");
        assertThat(lines.get(3).payload().path("content").asString()).isEqualTo("第二段");
    }

    @Test
    void nodeOutputPreviewTruncatedToOneKb() throws Exception {
        Definition d = save(
                DefinitionValidatorTest.node("s", "start"),
                DefinitionValidatorTest.node("c", "command").command("echo " + "A".repeat(2000)),
                DefinitionValidatorTest.edge("e1", "s", "c"));
        InstanceMeta m = start(d);
        awaitTerminal(m.id());
        var ev = events(m.id()).stream().filter(e -> "node.output".equals(e.event())
                && "c".equals(e.payload().path("nodeId").asString())).findFirst().orElseThrow();
        String preview = ev.payload().path("outputPreview").asString();
        assertThat(preview.length()).isLessThanOrEqualTo(1030); // 1024 + 截断后缀
        // stdout 全量入 runs 文件
        RunRecord rec = runs(m.id()).stream().filter(r -> "c".equals(r.nodeId()))
                .findFirst().orElseThrow();
        assertThat(rec.output().stdout().length()).isGreaterThan(1024);
    }
}
