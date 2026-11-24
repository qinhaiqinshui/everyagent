// ea: workflow_run 工具集测试（阻塞至终态/失败上下文回传/查询投影/级联取消/共享校验；桩注入不依赖 worker，§14.9 红线）
package dev.everyagent.plugin.workflow;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.exception.AgentCancelledException;
import dev.everyagent.plugin.api.exception.BadParamsException;
import dev.everyagent.plugin.api.model.ModelConfig;
import dev.everyagent.plugin.api.spi.SandboxBackend;
import dev.everyagent.plugin.api.spi.ToolContext;
import dev.everyagent.plugin.api.spi.WorkspaceManager;
import dev.everyagent.plugin.workflow.engine.WorkflowEngine;
import dev.everyagent.plugin.workflow.model.AgentDef;
import dev.everyagent.plugin.workflow.model.Definition;
import dev.everyagent.plugin.workflow.model.InstanceMeta;
import dev.everyagent.plugin.workflow.model.InstanceStatus;
import dev.everyagent.plugin.workflow.model.OnError;
import dev.everyagent.plugin.workflow.model.VariableDef;
import dev.everyagent.plugin.workflow.store.WorkflowStore;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * workflow_run + 实例查询四件（hidden 工具面，§7.20）：成功/失败/取消/waiting-user 四形
 * 返回、任务停止级联取消（直接中断 + terminal() 轮询兜底两条路径）、查询投影契约
 * （log 截断 4000、events seq 字符串、status 无快照全图）、校验与 RPC 共用
 * {@link WorkflowEngine#validateForRun}。
 */
class WorkflowRunToolTest {

    @TempDir
    Path home;

    FakeWorkspaces workspaces;
    WorkflowStore store;
    Fakes.FakeAgentFactory factory;
    Fakes.FakeWorkerServices services;
    WorkflowEngine engine;
    WorkflowTools tools;
    Fakes.FakeTaskExec task;
    TaskToolContext ctx;

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
        task = new Fakes.FakeTaskExec(home.resolve("workspaces"), "ws_run", "t_tool_1", factory);
        ctx = new TaskToolContext(task, workspaces);
        tools = new WorkflowTools(store, engine, ctx);
    }

    // ── 构造工具 ──

    private Definition save(Object... parts) {
        return store.saveDefinition("ws_run", DefinitionValidatorTest.def(parts));
    }

    private static Definition withOnError(Definition d, OnError onError) {
        return new Definition(d.schemaVersion(), d.id(), d.name(), d.description(), d.workspaceId(),
                d.createdAt(), d.updatedAt(), d.variables(), onError, d.supervisor(),
                d.agents(), d.nodes(), d.edges());
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
                .map(e -> e.event()).toList();
    }

    // ══════════════════════════ workflow_run ══════════════════════════

    @Test
    void runBlocksUntilDoneAndReturnsStructuredSummary() throws Exception {
        Definition d = store.saveDefinition("ws_run", DefinitionValidatorTest.withVariables(
                DefinitionValidatorTest.def(
                        DefinitionValidatorTest.node("s", "start"),
                        DefinitionValidatorTest.node("c", "command").command("echo hello-{{who}}"),
                        DefinitionValidatorTest.edge("e1", "s", "c")),
                new VariableDef("who", null, true, null, null)));

        String raw = tools.workflow_run(d.id(), "{\"who\":\"world\"}", null);
        JsonNode r = Json.parse(raw);
        assertThat(r.path("status").asString()).isEqualTo("done");
        assertThat(r.path("instanceId").asString()).startsWith("wi_");
        assertThat(r.path("definitionName").asString()).isEqualTo("测试定义");
        assertThat(r.path("durationMs").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(r.path("nodes").size()).isEqualTo(2);
        assertThat(r.path("nodes").get(1).path("nodeName").asString()).isEqualTo("c");
        assertThat(r.path("nodes").get(1).path("status").asString()).isEqualTo("done");

        // originTaskId 落 meta（工具主体=任务本身）
        InstanceMeta m = meta(r.path("instanceId").asString());
        assertThat(m.status()).isEqualTo(InstanceStatus.DONE);
        assertThat(m.originTaskId()).isEqualTo("t_tool_1");
        assertThat(m.variablesOrEmpty()).containsEntry("who", "world");
    }

    @Test
    void runFailureReturnsStructuredErrorWithContextAndHint() throws Exception {
        factory.script("writer", key -> Fakes.Script.failing(
                new IllegalStateException("模型调用失败")));
        Definition d = save(
                DefinitionValidatorTest.node("s", "start"),
                DefinitionValidatorTest.node("n", "ai").agent("writer").maxRuns(10),
                DefinitionValidatorTest.node("t", "ai").maxRuns(10),
                DefinitionValidatorTest.edge("e1", "s", "n"),
                DefinitionValidatorTest.edge("e2", "n", "t"),
                new AgentDef("writer", null, null));

        String raw = tools.workflow_run(d.id(), null, null);
        JsonNode r = Json.parse(raw);
        assertThat(r.path("status").asString()).isEqualTo("failed");
        assertThat(r.path("error").asString()).contains("模型调用失败");
        assertThat(r.path("nodeId").asString()).isEqualTo("n");
        assertThat(r.path("runNo").asInt()).isPositive();
        assertThat(r.path("hint").asString()).contains("workflow_rerun");
        // 失败节点历史输出摘要（本次失败 run 留档 error）
        assertThat(r.path("context").size()).isGreaterThanOrEqualTo(1);
        assertThat(r.path("context").get(0).path("status").asString()).isEqualTo("failed");

        InstanceMeta m = meta(r.path("instanceId").asString());
        assertThat(m.status()).isEqualTo(InstanceStatus.FAILED);
        assertThat(m.error().nodeId()).isEqualTo("n");
    }

    @Test
    void runValidatesParamsWithSharedEngineMethod() {
        // 缺参 / 定义不存在 / 坏 variables JSON
        assertThat(Json.parse(tools.workflow_run(null, null, null)).path("error").asString())
                .contains("definitionId");
        assertThat(Json.parse(tools.workflow_run("wf_nope", null, null)).path("error").asString())
                .contains("NOT_FOUND");
        assertThat(Json.parse(tools.workflow_run("wf_x", "[不是对象", null)).path("error").asString())
                .contains("variables");
        assertThat(Json.parse(tools.workflow_run("wf_x", "[1,2]", null)).path("error").asString())
                .contains("variables");

        // 缺必填变量 / 未知模型配置：与 RPC 共用同一校验（异常消息 = 工具 error 全文）
        Definition missing = store.saveDefinition("ws_run", DefinitionValidatorTest.withVariables(
                DefinitionValidatorTest.def(
                        DefinitionValidatorTest.node("s", "start"),
                        DefinitionValidatorTest.node("c", "command").command("echo {{must}}"),
                        DefinitionValidatorTest.edge("e1", "s", "c")),
                new VariableDef("must", null, true, null, null)));
        BadParamsException rpcSide = catchBadParams(() -> engine.validateForRun(missing, Map.of()));
        String toolErr = Json.parse(tools.workflow_run(missing.id(), null, null))
                .path("error").asString();
        assertThat(toolErr).contains("must");
        assertThat(toolErr).isEqualTo(rpcSide.getMessage());

        Definition unknownModel = save(
                DefinitionValidatorTest.node("s", "start"),
                DefinitionValidatorTest.node("n", "ai").agent("writer"),
                DefinitionValidatorTest.edge("e1", "s", "n"),
                new AgentDef("writer", "gpt4-nope", null));
        BadParamsException rpcSide2 = catchBadParams(
                () -> engine.validateForRun(unknownModel, Map.of()));
        String toolErr2 = Json.parse(tools.workflow_run(unknownModel.id(), null, null))
                .path("error").asString();
        assertThat(toolErr2).contains("gpt4-nope");
        assertThat(toolErr2).isEqualTo(rpcSide2.getMessage());
    }

    @Test
    void runHonorsExplicitWorkspaceParam() throws Exception {
        workspaces.register(home.resolve("proj2"), "ws_other");
        Definition d = store.saveDefinition("ws_other",
                DefinitionValidatorTest.def(DefinitionValidatorTest.node("s", "start")));

        String raw = tools.workflow_run(d.id(), null, home.resolve("proj2").toString());
        JsonNode r = Json.parse(raw);
        assertThat(r.path("status").asString()).isEqualTo("done");
        assertThat(meta(r.path("instanceId").asString()).workspaceId()).isEqualTo("ws_other");
        // 未注册路径 → 错误 JSON
        assertThat(Json.parse(tools.workflow_run(d.id(), null, "Z:/nope"))
                .path("error").asString()).contains("未注册");
    }

    @Test
    void waitingUserReturnsStructuredResultAndDecideStillWorks() throws Exception {
        factory.script("writer", key -> Fakes.Script.failing(new RuntimeException("第一次失败")));
        Definition d = store.saveDefinition("ws_run", withOnError(
                DefinitionValidatorTest.def(
                        DefinitionValidatorTest.node("s", "start"),
                        DefinitionValidatorTest.node("n", "ai").agent("writer").maxRuns(10),
                        DefinitionValidatorTest.edge("e1", "s", "n"),
                        new AgentDef("writer", null, null)), OnError.ASK_USER));

        String raw = tools.workflow_run(d.id(), null, null);
        JsonNode r = Json.parse(raw);
        assertThat(r.path("status").asString()).isEqualTo("waiting-user");
        assertThat(r.path("pendingDecision").path("nodeId").asString()).isEqualTo("n");
        assertThat(r.path("note").asString()).contains("决策");

        // askUser 挂起 + decide RPC 决策内核不受损（fail 收口）
        String instanceId = r.path("instanceId").asString();
        InstanceMeta parked = meta(instanceId);
        engine.decide(instanceId, parked.pendingDecision().runNo(), "fail", null);
        assertThat(engine.awaitTerminal(instanceId, 20_000)).isTrue();
        assertThat(meta(instanceId).status()).isEqualTo(InstanceStatus.FAILED);
    }

    // ══════════════════════════ 任务停止级联取消 ══════════════════════════

    @Test
    void taskStopInterruptCascadesCancel() throws Exception {
        factory.script("writer", key -> Fakes.Script.delayed("不该完成", 30_000));
        Definition d = save(
                DefinitionValidatorTest.node("s", "start"),
                DefinitionValidatorTest.node("n", "ai").agent("writer"),
                DefinitionValidatorTest.edge("e1", "s", "n"),
                new AgentDef("writer", null, null));

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicReference<String> result = new AtomicReference<>();
        Thread runner = Thread.ofVirtual().start(() -> {
            try {
                result.set(tools.workflow_run(d.id(), null, null));
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        await(() -> !store.listInstances("ws_run", null).isEmpty(), "实例创建");
        String instanceId = store.listInstances("ws_run", null).get(0).id();
        await(() -> eventNames(instanceId).contains("node.started"), "node.started");

        runner.interrupt(); // 任务停止的中断直达工具阻塞点
        runner.join(20_000);
        assertThat(thrown.get()).isInstanceOf(AgentCancelledException.class);
        assertThat(result.get()).isNull();

        // 实例被级联取消（by=task-stopped）
        assertThat(engine.awaitTerminal(instanceId, 20_000)).isTrue();
        assertThat(meta(instanceId).status()).isEqualTo(InstanceStatus.CANCELLED);
        var cancelledEv = store.readEvents("ws_run", instanceId, 0, 0).stream()
                .filter(e -> "wf.cancelled".equals(e.event())).findFirst().orElseThrow();
        assertThat(cancelledEv.payload().path("by").asString()).isEqualTo("task-stopped");
    }

    @Test
    void taskTerminalPollFallbackCascadesCancel() throws Exception {
        // 工具线程未收到直接中断（reactive 工具执行线程切换的场景）：
        // 任务收口翻 terminal() 后由轮询兜底级联取消
        factory.script("writer", key -> Fakes.Script.delayed("不该完成", 30_000));
        Definition d = save(
                DefinitionValidatorTest.node("s", "start"),
                DefinitionValidatorTest.node("n", "ai").agent("writer"),
                DefinitionValidatorTest.edge("e1", "s", "n"),
                new AgentDef("writer", null, null));

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread runner = Thread.ofVirtual().start(() -> {
            try {
                tools.workflow_run(d.id(), null, null);
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        String instanceId = awaitInstance();
        await(() -> eventNames(instanceId).contains("node.started"), "node.started");

        task.terminal = true; // 任务收口（ExecContext.terminal() 翻真）
        runner.join(20_000);
        assertThat(thrown.get()).isInstanceOf(AgentCancelledException.class);
        assertThat(engine.awaitTerminal(instanceId, 20_000)).isTrue();
        assertThat(meta(instanceId).status()).isEqualTo(InstanceStatus.CANCELLED);
    }

    // ══════════════════════════ 查询四件 ══════════════════════════

    @Test
    void statusProjectsSummaryWithoutDefinitionSnapshot() throws Exception {
        Definition d = save(
                DefinitionValidatorTest.node("s", "start"),
                DefinitionValidatorTest.node("c", "command").command("echo ok"),
                DefinitionValidatorTest.edge("e1", "s", "c"));
        JsonNode run = Json.parse(tools.workflow_run(d.id(), null, null));
        String instanceId = run.path("instanceId").asString();

        JsonNode st = Json.parse(tools.workflow_instance_status(instanceId));
        assertThat(st.path("status").asString()).isEqualTo("done");
        assertThat(st.path("definitionName").asString()).isEqualTo("测试定义");
        assertThat(st.path("originTaskId").asString()).isEqualTo("t_tool_1");
        assertThat(st.path("nodeStates").size()).isEqualTo(2);
        // 不回定义快照全图
        assertThat(st.has("definitionSnapshot")).isFalse();
        // 不存在 / 缺参
        assertThat(Json.parse(tools.workflow_instance_status("wi_nope")).path("error").asString())
                .contains("NOT_FOUND");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> tools.workflow_instance_status(null))
                .isInstanceOf(BadParamsException.class);
    }

    @Test
    void logTruncatesLongOutputTo4000Chars() throws Exception {
        Definition d = save(
                DefinitionValidatorTest.node("s", "start"),
                DefinitionValidatorTest.node("c", "command").command(
                        "echo " + "x".repeat(4500)),
                DefinitionValidatorTest.edge("e1", "s", "c"));
        String instanceId = Json.parse(tools.workflow_run(d.id(), null, null))
                .path("instanceId").asString();

        JsonNode log = Json.parse(tools.workflow_instance_log(instanceId, null, null));
        assertThat(log.path("total").asInt()).isEqualTo(2);
        JsonNode cmdRun = log.path("runs").get(1);
        assertThat(cmdRun.path("nodeId").asString()).isEqualTo("c");
        String stdout = cmdRun.path("output").path("stdout").asString();
        assertThat(stdout.length()).isLessThanOrEqualTo(4000 + "…(截断)".length());
        assertThat(stdout).endsWith("…(截断)");
        // nodeId 过滤 + tail
        assertThat(Json.parse(tools.workflow_instance_log(instanceId, "c", 1))
                .path("runs").size()).isEqualTo(1);
        assertThat(Json.parse(tools.workflow_instance_log(instanceId, "c", 1))
                .path("runs").get(0).path("nodeId").asString()).isEqualTo("c");
    }

    @Test
    void eventsPassThroughSeqAsStringLengths() throws Exception {
        Definition d = save(
                DefinitionValidatorTest.node("s", "start"),
                DefinitionValidatorTest.node("c", "command").command("echo ok"),
                DefinitionValidatorTest.edge("e1", "s", "c"));
        String instanceId = Json.parse(tools.workflow_run(d.id(), null, null))
                .path("instanceId").asString();

        JsonNode first = Json.parse(tools.workflow_instance_events(instanceId, null, null));
        assertThat(first.path("events").size()).isLessThanOrEqualTo(50);
        for (JsonNode e : first.path("events")) {
            assertThat(e.path("seq").isString()).as("seq 必须是字符串").isTrue();
        }
        assertThat(first.path("nextSeq").isString()).isTrue();
        // 游标续读：afterSeq=nextSeq → 空
        JsonNode next = Json.parse(tools.workflow_instance_events(instanceId,
                Long.parseLong(first.path("nextSeq").asString()), null));
        assertThat(next.path("events").size()).isZero();
        assertThat(next.path("nextSeq").asString())
                .isEqualTo(first.path("nextSeq").asString());
        // limit 生效
        assertThat(Json.parse(tools.workflow_instance_events(instanceId, null, 2))
                .path("events").size()).isEqualTo(2);
    }

    @Test
    void cancelToolCancelsRunningInstanceAndRunReturnsCancelled() throws Exception {
        factory.script("writer", key -> Fakes.Script.delayed("不该完成", 30_000));
        Definition d = save(
                DefinitionValidatorTest.node("s", "start"),
                DefinitionValidatorTest.node("n", "ai").agent("writer"),
                DefinitionValidatorTest.edge("e1", "s", "n"),
                new AgentDef("writer", null, null));

        AtomicReference<String> result = new AtomicReference<>();
        Thread runner = Thread.ofVirtual().start(() ->
                result.set(tools.workflow_run(d.id(), null, null)));
        String instanceId = awaitInstance();
        await(() -> eventNames(instanceId).contains("node.started"), "node.started");

        JsonNode cancel = Json.parse(tools.workflow_instance_cancel(instanceId));
        assertThat(cancel.path("ok").asBoolean()).isTrue();
        assertThat(cancel.path("status").asString()).isEqualTo("cancelled");

        runner.join(20_000);
        JsonNode r = Json.parse(result.get());
        assertThat(r.path("status").asString()).isEqualTo("cancelled");
        assertThat(r.path("note").asString()).contains("取消");
        assertThat(meta(instanceId).status()).isEqualTo(InstanceStatus.CANCELLED);
        // 已终态再取消 → 错误 JSON
        assertThat(Json.parse(tools.workflow_instance_cancel(instanceId))
                .path("error").asString()).isNotEmpty();
    }

    // ── 工具 ──

    private static BadParamsException catchBadParams(Runnable r) {
        try {
            r.run();
        } catch (BadParamsException e) {
            return e;
        }
        throw new AssertionError("期望 BadParamsException");
    }

    /** 等待并返回本工作区（本测试方法唯一）实例 id——级联/取消测试从工具线程外取目标。 */
    private String awaitInstance() throws InterruptedException {
        await(() -> !store.listInstances("ws_run", null).isEmpty(), "实例创建");
        return store.listInstances("ws_run", null).get(0).id(); // createdAt 倒序：最新在前
    }

    // ── 任务 ToolContext 桩（ExecContext 面委托 FakeTaskExec） ──

    static final class TaskToolContext implements ToolContext {

        final Fakes.FakeTaskExec exec;
        final WorkspaceManager workspaces;

        TaskToolContext(Fakes.FakeTaskExec exec, WorkspaceManager workspaces) {
            this.exec = exec;
            this.workspaces = workspaces;
        }

        @Override public String subjectId() { return exec.subjectId(); }
        @Override public String workspaceRoot() { return exec.workspaceRoot(); }
        @Override public String workspaceId() { return exec.workspaceId(); }
        @Override public ModelConfig snapshot() { return null; }
        @Override public dev.everyagent.plugin.api.model.EventEmitter emitter() { return exec.emitter(); }
        @Override public dev.everyagent.plugin.api.agent.AgentFactory agentFactory() { return exec.agentFactory(); }
        @Override public Map<String, Object> metadata() { return exec.metadata(); }
        @Override public Path dataDir() { return exec.dataDir(); }
        @Override public boolean terminal() { return exec.terminal(); }
        @Override public dev.everyagent.plugin.api.interaction.InteractionService interaction() { return null; }
        @Override public Map<String, dev.everyagent.plugin.api.agent.AgentContext> agents() { return exec.agents(); }
        @Override public dev.everyagent.plugin.api.agent.ConversationArchive conversationArchive() {
            return exec.conversationArchive();
        }
        @Override public String agentId() { return "main"; }
        @Override public SandboxBackend sandbox() { return null; }
        @Override public WorkspaceManager workspaces() { return workspaces; }
    }
}
